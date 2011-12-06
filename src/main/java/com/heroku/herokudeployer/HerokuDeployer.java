package com.heroku.herokudeployer;

import com.heroku.api.Heroku;
import com.heroku.api.connection.HttpClientConnection;
import com.heroku.api.model.App;
import com.heroku.api.model.Key;
import com.heroku.api.request.app.AppCreate;
import com.heroku.api.request.key.KeyAdd;
import com.heroku.api.request.key.KeyList;
import com.heroku.api.request.key.KeyRemove;
import com.heroku.api.request.login.BasicAuthLogin;
import com.heroku.api.request.sharing.SharingAdd;
import com.heroku.api.request.sharing.SharingRemove;
import com.heroku.api.request.sharing.SharingTransfer;
import com.heroku.api.response.Unit;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class HerokuDeployer {

    private static final String SSH_KEY_COMMENT = "heroku@localhost";

    public static void main(String[] args) {
        
        try {

            File projectDir = getProjectDir(args, true);
            
            String herokuApiKey = getHerokuApiKey(true);
            
            File sshKey = getSshKey(herokuApiKey, true);

            // todo: update the known_hosts file
    
            commitProjectToLocalGit(projectDir, false, true);
    
            String gitUrl = getOrCreateApp(herokuApiKey, projectDir, true);
    
            deployApp(sshKey, projectDir, true);
            
        }
        catch (Exception e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        
    }


    public static String getHerokuApiKey(boolean interactive) throws IOException {
        
        String herokuApiKey = null;
        
        File userHome = new File(System.getProperty("user.home"));
        
        File dotHeroku = new File(userHome.getAbsolutePath() + File.separator + ".heroku");
        
        if (!dotHeroku.exists()) {
            dotHeroku.mkdir();
        }
        
        // check for heroku credentials
        
        File herokuCredentialsFile = new File(dotHeroku.getAbsolutePath() + File.separator + "credentials");

        if (!herokuCredentialsFile.exists()) {

            String herokuUsername = null;
            String herokuPassword = null;
            
            if (interactive) {
                // ask for username / password
                
            }
            else {
                // check env vars

            }
            
            if ((herokuUsername == null) || (herokuPassword == null)) {
                throw new RuntimeException("Could not get the Heroku username and/or password.");
            }

            // login to get API key
            HttpClientConnection herokuConnection = new HttpClientConnection(new BasicAuthLogin(herokuUsername, herokuPassword));
            herokuApiKey = herokuConnection.getApiKey();
            
            if (interactive) {
                System.out.println("Logged into Heroku");
            }

            // store API creds
            herokuCredentialsFile.createNewFile();
            FileUtils.writeStringToFile(herokuCredentialsFile, herokuUsername + IOUtils.LINE_SEPARATOR + herokuApiKey);

            if (interactive) {
                System.out.println("Stored Heroku API key in " + herokuCredentialsFile.getAbsolutePath());
            }
        }
        else {
            herokuApiKey = (String)FileUtils.readLines(herokuCredentialsFile).get(1);

            if (interactive) {
                System.out.println("Read Heroku API key from " + herokuCredentialsFile.getAbsolutePath());
            }
        }
        
        return herokuApiKey;
    }
    


    public static File getProjectDir(String[] args, boolean interactive) {

        File projectDirectory = null;

        if (args.length == 0) {
            projectDirectory = new File(".");
        }
        else if (args.length == 1) {
            projectDirectory = new File(args[0]);
        }

        if (!projectDirectory.exists()) {
            throw new RuntimeException("Project directory does not exist: " + projectDirectory.getAbsolutePath());
        }
        
        if (interactive) {
            System.out.println("Project directory: " + projectDirectory.getAbsolutePath());
        }

        return projectDirectory;
    }

    private static File getSshKey(String herokuApiKey, boolean interactive) throws JSchException, IOException {
        
        File sshKey = null;

        File sshDir = new File(System.getProperty("user.home") + File.separator + ".ssh");
        if (!sshDir.exists()) {
            sshDir.mkdir();
        }

        // check for a heroku user key
        HttpClientConnection herokuConnection = new HttpClientConnection(herokuApiKey);
        KeyList keyListReq = new KeyList();
        List<Key> keys = herokuConnection.execute(keyListReq);

        // see if any of the keys exist on the system
        for (Key key : keys) {
            Collection<File> files = FileUtils.listFiles(sshDir, new String[]{"pub"}, false);
            for (File file : files) {
                String sshKeyString = FileUtils.readFileToString(file);
                if (sshKeyString.equals(key.getContents())) {
                    sshKey = file;
                    
                    if (interactive) {
                        System.out.println("Using ssh key from: " + file.getAbsolutePath());
                    }
                    
                    return sshKey;
                }
            }
        }

        if (sshKey == null) {
            // save it in ~/.ssh/heroku_rsa and ~/.ssh/heroku_rsa.pub
            JSch jsch = new JSch();
            KeyPair keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA);
            keyPair.writePrivateKey(sshDir.getAbsolutePath() + File.separator + "heroku_rsa");
            
            sshKey = new File(sshDir.getAbsolutePath() + File.separator + "heroku_rsa.pub");
            
            keyPair.writePublicKey(sshKey.getAbsolutePath(), SSH_KEY_COMMENT);
            
            if (interactive) {
                System.out.println("Created new ssh key pair (heroku_rsa) in: " + sshDir.getAbsolutePath());
            }

            ByteArrayOutputStream publicKeyOutputStream = new ByteArrayOutputStream();
            keyPair.writePublicKey(publicKeyOutputStream, SSH_KEY_COMMENT);
            publicKeyOutputStream.close();

            String sshPublicKey = new String(publicKeyOutputStream.toByteArray());

            // associate the key with the heroku account
            KeyAdd keyAddReq = new KeyAdd(sshPublicKey);
            Unit keyAddResp = herokuConnection.execute(keyAddReq);

            if (keyAddResp == null) {
                throw new RuntimeException("Could not associate the ssh key with the Heroku account");
            }
            
            if (interactive) {
                System.out.println("Added the heroku_rsa.pub ssh public key to your Heroku account");
            }
        }
        
        return sshKey;
    }

    private static void commitProjectToLocalGit(File projectDir, boolean autoCommit, boolean interactive) {

        // see if a .git exists in the project dir

        // if not, create a git repo

        // check for .gitignore and dirs that should not be committed

        // create .gitignore if needed

        // check for uncommitted changes

        // commit changes

    }

    private static String getOrCreateApp(String herokuApiKey, File projectDir, boolean interactive) throws IOException {

        String gitUrl = null;

        File projectGitDir = new File(projectDir.getAbsoluteFile() + File.separator + ".git");
        
        Repository repository = new RepositoryBuilder().setGitDir(projectGitDir).build();

        // see if a git remote named "heroku" exists

        // if not then create a new one

        HttpClientConnection herokuConnection = new HttpClientConnection(herokuApiKey);

        // create an app on heroku (using heroku credentials specified in ${HEROKU_USERNAME} / ${HEROKU_PASSWORD}
        AppCreate cmd = new AppCreate(Heroku.Stack.Cedar);
        app = herokuConnection.execute(cmd);

            // add the git remote

        return gitUrl;
    }

    public static void deployApp(File sshKey, File projectDirectory, boolean interactive) {

        // git push heroku master
        Repository repository = new RepositoryBuilder().setGitDir(tmpGitDir).build();
        gitRepo = new Git(repository);

        gitRepo.getRepository().getFS().setUserHome(new File(fakeUserHome));
        gitRepo.push().setRemote(app.getGit_url()).call();

    }
    
}