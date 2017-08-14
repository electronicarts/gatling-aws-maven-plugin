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
import java.util.Arrays;
import java.util.List;

public class SshClient {

    private static final long MAX_ATTEMPTS = 8;
    private static final long INITIAL_SLEEP_TIME_MS = 100;
    private static final long BACKOFF_FACTOR = 2;

    private static final Config DEFAULT_CONFIG = new DefaultConfig();

    public static void scpUpload(HostInfo hostInfo, FromTo fromTo) throws IOException {
        scpUpload(hostInfo, Arrays.asList(fromTo));
    }

    /**
     * Upload one or more files via the same SSH/SCP connection to a remote host.
     */
    public static void scpUpload(HostInfo hostInfo, List<FromTo> fromTos) throws IOException {
        SSHClient ssh = getSshClient(hostInfo);

        try {
            Session session = ssh.startSession();
            session.allocateDefaultPTY();
            try {
                for (FromTo ft: fromTos) {
                    System.out.format("SCP cp %s -> %s/%s%n", ft.from, hostInfo.host, ft.to);
                    ssh.newSCPFileTransfer().upload(ft.from, ft.to);
                }
            } finally {
                session.close();
            }
        } finally {
            ssh.disconnect();
            ssh.close();
        }
    }


    public static void scpDownload(HostInfo hostInfo, FromTo fromTo) throws IOException {
        SSHClient ssh = getSshClient(hostInfo);

        try {
            Session session = ssh.startSession();
            session.allocateDefaultPTY();
            try {
                ssh.newSCPFileTransfer().download(fromTo.from, fromTo.to);
            } finally {
                session.close();
            }
        } finally {
            ssh.disconnect();
            ssh.close();
        }
    }

    public static int executeCommand(HostInfo hostInfo, String command, boolean debugOutputEnabled) throws IOException {
        SSHClient ssh = getSshClient(hostInfo);

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
                return cmd.getExitStatus();
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

    private static SSHClient getSshClient(HostInfo hostInfo) throws IOException {
        long sleepTimeMs = INITIAL_SLEEP_TIME_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                SSHClient ssh = new SSHClient(DEFAULT_CONFIG);
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
                ssh.connect(hostInfo.host);
                ssh.authPublickey(hostInfo.user, hostInfo.privateKeyPath);
                ssh.useCompression();
                return ssh;
            } catch (IOException exception) {
                System.out.format("Failed to login to host %s as user %s. Exception: %s.%n", hostInfo.host, hostInfo.user, exception.getMessage());
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

        throw new RuntimeException(String.format("Unable to login to host %s as user %s after %d attempts.", hostInfo.host, hostInfo.user, MAX_ATTEMPTS));
    }

    static class HostInfo {
        private final String host;
        private final String user;
        private final String privateKeyPath;

        public HostInfo(String host, String user, String privateKeyPath) {
            this.host = host;
            this.user = user;
            this.privateKeyPath = privateKeyPath;
        }
    }

    static class FromTo {
        private final String from;
        private final String to;

        public FromTo(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }
}
