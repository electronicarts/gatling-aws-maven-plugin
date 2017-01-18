/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling;

import net.schmizz.sshj.Config;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

import java.io.IOException;
import java.io.InputStream;

public class SshClient {

    private static final long MAX_ATTEMPTS = 8;
    private static final long INITIAL_SLEEP_TIME_MS = 100;
    private static final long BACKOFF_FACTOR = 2;

    private static final Config DEFAULT_CONFIG = new DefaultConfig();

    public static void scpUpload(String host, String user, String privateKeyPath, String localFile, String remoteDir) throws IOException {
        SSHClient ssh = getSshClient(host, user, privateKeyPath);

        try {
            Session session = ssh.startSession();
            session.allocateDefaultPTY();
            try {
                ssh.newSCPFileTransfer().upload(localFile, remoteDir);
            } finally {
                session.close();
            }
        } finally {
            ssh.disconnect();
            ssh.close();
        }
    }

    public static void scpDownload(String host, String user, String privateKeyPath, String remoteFile, String localDir) throws IOException {
        SSHClient ssh = getSshClient(host, user, privateKeyPath);

        try {
            Session session = ssh.startSession();
            session.allocateDefaultPTY();
            try {
                ssh.newSCPFileTransfer().download(remoteFile, localDir);
            } finally {
                session.close();
            }
        } finally {
            ssh.disconnect();
            ssh.close();
        }
    }

    public static void executeCommand(String host, String user, String privateKeyPath, String command, boolean debugOutputEnabled) throws IOException {
        SSHClient ssh = getSshClient(host, user, privateKeyPath);

        try {
            Session session = ssh.startSession();
            session.allocateDefaultPTY();
            try {
                if (debugOutputEnabled) {
                    System.out.println("About to run: " + command);
                }
                Command cmd = session.exec(command);
                readCommandOutput(cmd);
                cmd.join();
                printExitCode(cmd.getExitStatus());
            } finally {
                session.close();
            }
        } finally {
            ssh.disconnect();
            ssh.close();
        }
    }

    public static boolean printExitCode(int exitCode) {
        boolean success = exitCode == 0;
        if (!success) {
            System.out.format("%nexit code: %d%n", exitCode);
        }
        return success;
    }

    private static void readCommandOutput(Command cmd) throws IOException {
        byte[] tmp = new byte[1024];
        InputStream is = cmd.getInputStream();
        while (true) {
            while (is.available() > 0) {
                int i = is.read(tmp, 0, 1024);
                if (i < 0) {
                    break;
                }
                System.out.print(new String(tmp, 0, i));
            }
            if (!cmd.isOpen()) {
                if (is.available() > 0) {
                    continue;
                }
                printExitCode(cmd.getExitStatus());
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (Exception ee) {
            }
        }
    }

    private static SSHClient getSshClient(String host, String user, String privateKeyPath) throws IOException {
        long sleepTimeMs = INITIAL_SLEEP_TIME_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                SSHClient ssh = new SSHClient(DEFAULT_CONFIG);
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
                ssh.connect(host);
                ssh.authPublickey(user, privateKeyPath);
                ssh.useCompression();
                return ssh;
            } catch (IOException exception) {
                System.out.format("Failed to login to host %s as user %s. Exception: %s.%n", host, user, exception.getMessage());
                System.out.format("Attempt %d of %d. Sleeping for %d ms.%n", attempt, MAX_ATTEMPTS, sleepTimeMs);

                boolean lastAttempt = attempt == MAX_ATTEMPTS;

                if (lastAttempt) {
                    throw exception;
                }

                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException e) {
                }
                sleepTimeMs *= BACKOFF_FACTOR;
            }
        }

        throw new RuntimeException(String.format("Unable to login to host %s as user %s after %d attempts.", host, user, MAX_ATTEMPTS));
    }
}
