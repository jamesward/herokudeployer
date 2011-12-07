package com.heroku.herokudeployer;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.eclipse.jgit.JGitText;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.util.FS;

import java.io.File;
import java.net.ConnectException;
import java.net.UnknownHostException;

public class HerokuSshSessionFactory extends SshSessionFactory {

    @Override
    public RemoteSession getSession(URIish uri, CredentialsProvider credentialsProvider, FS fs, int tms) throws TransportException {

        String user = uri.getUser();
        final String pass = uri.getPass();
        String host = uri.getHost();
        int port = uri.getPort();

        try {
            File herokuKey = new File(System.getProperty("user.home") + File.separator + ".ssh", "heroku_rsa");
            
            JSch jsch = new JSch();
            jsch.addIdentity(herokuKey.getAbsolutePath());
            jsch.setKnownHosts(getClass().getClassLoader().getResourceAsStream("known_hosts"));
            
            final Session session = jsch.getSession(user, host);
            
            if (!session.isConnected())
                session.connect(tms);

            return new JschSession(session, uri);

        } catch (JSchException je) {
            final Throwable c = je.getCause();
            if (c instanceof UnknownHostException)
                throw new TransportException(uri, JGitText.get().unknownHost);
            if (c instanceof ConnectException)
                throw new TransportException(uri, c.getMessage());
            throw new TransportException(uri, je.getMessage(), je);
        }
        
    }

}