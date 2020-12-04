/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AwsGatlingExecutor implements Runnable {

    private static final String[] GATLING_RESOURCES = {"data", "bodies"};
    private static final String DEFAULT_JVM_ARGS = "-Dsun.net.inetaddr.ttl=60";

    private final String host;
    private final String sshUser;
    private final String sshPrivateKey;
    private final String testName;
    private final File installScript;
    private final File gatlingSourceDir;
    private final String gatlingSimulation;
    private final File gatlingResourcesDir;
    private final File gatlingLocalResultsDir;
    private final File simulationConfig;
    private final List<String> additionalFiles;
    private final int numInstance;
    private final int instanceCount;
    private final ConcurrentHashMap<String, Integer> completedHosts;
    private final String gatlingRoot;
    private final String inheritedGatlingJavaOpts;
    private final boolean debugOutputEnabled;
    private final boolean runDetached;
    private final String remoteLogfile;
    private final boolean getRemoteLogFile;

    public AwsGatlingExecutor(final String host,final String sshUser, final File sshPrivateKey, final String testName, final File installScript, final File gatlingSourceDir, final String gatlingSimulation, final File simulationConfig, final File gatlingResourcesDir, final File gatlingLocalResultsDir, final List<String> additionalFiles, final int numInstance, final int instanceCount, final ConcurrentHashMap<String, Integer> completedHosts, final String gatlingRoot, final String inheritedGatlingJavaOpts, final boolean debugOutputEnabled, final boolean runDetached, final boolean getRemoteLogFile, final String remoteLogFile) {
        this.host = host;
        this.sshUser = sshUser;
        this.sshPrivateKey = sshPrivateKey.getAbsolutePath();
        this.testName = testName;
        this.installScript = installScript;
        this.additionalFiles = additionalFiles;
        this.gatlingSourceDir = gatlingSourceDir;
        this.gatlingSimulation = gatlingSimulation;
        this.simulationConfig = simulationConfig;
        this.gatlingResourcesDir = gatlingResourcesDir;
        this.gatlingLocalResultsDir = gatlingLocalResultsDir;
        this.numInstance = numInstance;
        this.instanceCount = instanceCount;
        this.completedHosts = completedHosts;
        this.gatlingRoot = gatlingRoot;
        this.inheritedGatlingJavaOpts = inheritedGatlingJavaOpts;
        this.debugOutputEnabled = debugOutputEnabled;
        this.runDetached = runDetached;
        this.remoteLogfile = remoteLogFile;
        this.getRemoteLogFile = getRemoteLogFile;
    }

    public void runGatlingTest() throws IOException {
        this.log("started");
        final SshClient.HostInfo hostInfo = new SshClient.HostInfo(this.host, this.sshUser, this.sshPrivateKey);

        int resultCode = -1;
        resultCode = this.runProcess(hostInfo);

        // Indicate success to the caller. This key will be missing from the map if there were any exceptions.
        this.completedHosts.put(this.host, resultCode);
    }

    private int runProcess(final SshClient.HostInfo hostInfo) throws IOException {

        // copy scripts
        SshClient.scpUpload(hostInfo, new SshClient.FromTo(this.installScript.getAbsolutePath(), ""));
        SshClient.executeCommand(hostInfo, "chmod +x install-gatling.sh; ./install-gatling.sh", this.debugOutputEnabled);
        // write information about the instance into a text file to allow the load test to read it if necessary.
        SshClient.executeCommand(hostInfo, String.format("echo \"num_instance=%s%ninstance_count=%s\" >> instance.txt", this.numInstance, this.instanceCount), this.debugOutputEnabled);

        final List<SshClient.FromTo> files = new ArrayList<>();
        files.addAll(this.additionalFiles.stream().map(path -> new SshClient.FromTo(path, "")).collect(Collectors.toList()));

        final File targetFolder = new File("target");
        if (this.isValidDirectory(targetFolder)) {
            this.log("Copying additional JAR files");

            for (final File file : targetFolder.listFiles()) {
                final String path = file.getAbsolutePath();
                if (path.endsWith("-jar-with-dependencies.jar")) {
                    this.log("Copying JAR file " + path);
                    files.add(new SshClient.FromTo(path, this.gatlingRoot + "/lib"));
                }
            }
        }

        // copy resource files
        for (final String resource : GATLING_RESOURCES) {
            this.log("Copying resource " + resource);
            final String resourceDir = this.gatlingResourcesDir.getAbsolutePath() + "/" + resource;
            files.add(new SshClient.FromTo(resourceDir, this.gatlingRoot + "/user-files"));
        }

        // copy simulation config
        files.add(new SshClient.FromTo(this.simulationConfig.getAbsolutePath(), ""));

        // copy simulation files
        if (this.isValidDirectory(this.gatlingSourceDir)) {
            this.log("Copying simulation files");
            files.addAll(this.filesToFromToList(this.gatlingSourceDir.listFiles(), this.gatlingRoot + "/user-files/simulations"));
        }

        // copy simulation gatling configuration files if it exists
        final File configFolder = new File("src/test/resources/conf");
        if (this.isValidDirectory(configFolder)) {
            this.log("Copying gatling configuration files");
            files.addAll(this.filesToFromToList(configFolder.listFiles(), this.gatlingRoot + "/conf"));
        }

        // Copy all files via a single SCP session.
        SshClient.scpUpload(hostInfo, files);

        // start test
        // TODO add parameters for test name and description
        final String coreCommand = String.format("%s %s/bin/gatling.sh -s %s -on %s -rd test -nr -rf results/%s", this.getJavaOpts(), this.gatlingRoot, this.gatlingSimulation, this.testName, this.testName);
        // On CentOS, the CLOSE signal is sent prior to completion of the startup of the nohup.  The sleep call needs the return value of the &, and so causes the wait.  We don't need to sleep, but we do need to guarantee process creation.
        final String command = String.format("%s%s%s", (this.runDetached ? "nohup sh -c '" : ""), coreCommand, (this.runDetached ? "' > /dev/null 2>&1 & sleep 0" : ""));
        final int resultCode = SshClient.executeCommand(hostInfo, command, this.debugOutputEnabled);

        if (!this.runDetached) {
            // download report
            this.log(this.testName);
            SshClient.executeCommand(hostInfo,
                    String.format("mv %s/results/%s/*/simulation.log simulation.log", this.gatlingRoot, this.testName),
                    this.debugOutputEnabled);
            SshClient.scpDownload(hostInfo, new SshClient.FromTo("simulation.log",
                    String.format("%s/%s/simulation-%s.log", this.gatlingLocalResultsDir.getAbsolutePath(), this.testName, this.host)));
            if(this.getRemoteLogFile) {
                this.log("Downloading remote log");
                SshClient.scpDownload(hostInfo, new SshClient.FromTo(remoteLogfile,
                        String.format("%s/%s/remote-log-%s.log", this.gatlingLocalResultsDir.getAbsolutePath(), this.testName, this.host)));
            }
        }

        return resultCode;
    }

    private String getJavaOpts() {
        return String.format("JAVA_OPTS=\"%s %s\"", DEFAULT_JVM_ARGS, this.inheritedGatlingJavaOpts);
    }

    private void log(final String message) {
        if (this.debugOutputEnabled) {
            System.out.format("%s > %s%n", this.host, message);
        }
    }

    private boolean isValidDirectory(final File directory) {
        return directory.exists() && directory.isDirectory() && directory.listFiles() != null && directory.listFiles().length > 0;
    }

    public void run() {
        try {
            this.runGatlingTest();
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private List<SshClient.FromTo> filesToFromToList(final File[] files, final String remoteDir) {
        final List<SshClient.FromTo> fromTos = new ArrayList<>();

        for (final File file : files) {
            fromTos.add(new SshClient.FromTo(file.getAbsolutePath(), remoteDir));
        }

        return fromTos;
    }
}
