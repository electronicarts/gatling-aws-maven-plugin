/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AwsGatlingExecutor implements Runnable {

    private static final String SSH_USER = "ec2-user";
    private static final String[] GATLING_RESOURCES = {"data", "bodies"};
    private static final String DEFAULT_JVM_ARGS = "-Dsun.net.inetaddr.ttl=60";

    private final String host;
    private final String sshPrivateKey;
    private final String testName;
    private final File installScript;
    private final File gatlingSourceDir;
    private final String gatlingSimulation;
    private final Map<String, String> simulationOptions;
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

    public AwsGatlingExecutor(String host, File sshPrivateKey, String testName, File installScript, File gatlingSourceDir, String gatlingSimulation, File simulationConfig, Map<String, String> simulationOptions, File gatlingResourcesDir, File gatlingLocalResultsDir, List<String> additionalFiles, int numInstance, int instanceCount, ConcurrentHashMap<String, Integer> completedHosts, String gatlingRoot, String inheritedGatlingJavaOpts, boolean debugOutputEnabled) {
        this.host = host;
        this.sshPrivateKey = sshPrivateKey.getAbsolutePath();
        this.testName = testName;
        this.installScript = installScript;
        this.additionalFiles = additionalFiles;
        this.gatlingSourceDir = gatlingSourceDir;
        this.gatlingSimulation = gatlingSimulation;
        this.simulationConfig = simulationConfig;
        this.simulationOptions = simulationOptions;
        this.gatlingResourcesDir = gatlingResourcesDir;
        this.gatlingLocalResultsDir = gatlingLocalResultsDir;
        this.numInstance = numInstance;
        this.instanceCount = instanceCount;
        this.completedHosts = completedHosts;
        this.gatlingRoot = gatlingRoot;
        this.inheritedGatlingJavaOpts = inheritedGatlingJavaOpts;
        this.debugOutputEnabled = debugOutputEnabled;
    }

    public void runGatlingTest() throws IOException {
        log("started");
        final SshClient.HostInfo hostInfo = new SshClient.HostInfo(host, SSH_USER, sshPrivateKey);

        // copy scripts
        SshClient.scpUpload(hostInfo, new SshClient.FromTo(installScript.getAbsolutePath(), ""));
        SshClient.executeCommand(hostInfo, "chmod +x install-gatling.sh; ./install-gatling.sh", debugOutputEnabled);
        // write information about the instance into a text file to allow the load test to read it if necessary.
        SshClient.executeCommand(hostInfo, String.format("echo \"num_instance=%s%ninstance_count=%s\" >> instance.txt", numInstance, instanceCount), debugOutputEnabled);

        final List<SshClient.FromTo> files = new ArrayList<>();
        files.addAll(additionalFiles.stream().map(path -> new SshClient.FromTo(path, "")).collect(Collectors.toList()));

        final File targetFolder = new File("target");
        if (isValidDirectory(targetFolder)) {
            log("Copying additional JAR files");

            for (File file : targetFolder.listFiles()) {
                String path = file.getAbsolutePath();
                if (path.endsWith("-jar-with-dependencies.jar")) {
                    log("Copying JAR file " + path);
                    files.add(new SshClient.FromTo(path, gatlingRoot + "/lib"));
                }
            }
        }

        // copy resource files
        for (String resource : GATLING_RESOURCES) {
            log("Copying resource " + resource);
            String resourceDir = gatlingResourcesDir.getAbsolutePath() + "/" + resource;
            files.add(new SshClient.FromTo(resourceDir, gatlingRoot + "/user-files"));
        }

        // copy simulation config
        files.add(new SshClient.FromTo(simulationConfig.getAbsolutePath(), ""));

        // copy simulation files
        if (isValidDirectory(gatlingSourceDir)) {
            log("Copying simulation files");
            files.addAll(filesToFromToList(gatlingSourceDir.listFiles(), gatlingRoot + "/user-files/simulations"));
        }

        // copy simulation gatling configuration files if it exists
        final File configFolder = new File("src/test/resources/conf");
        if (isValidDirectory(configFolder)) {
            log("Copying gatling configuration files");
            files.addAll(filesToFromToList(configFolder.listFiles(), gatlingRoot + "/conf"));
        }

        // Copy all files via a single SCP session.
        SshClient.scpUpload(hostInfo, files);

        // start test
        // TODO add parameters for test name and description
        int resultCode = SshClient.executeCommand(hostInfo, String.format("%s %s/bin/gatling.sh -s %s -on %s -rd test -nr -rf results/%s", getJavaOpts(), gatlingRoot, gatlingSimulation, testName, testName), debugOutputEnabled);

        // download report
        log(testName);
        SshClient.executeCommand(hostInfo, String.format("mv %s/results/%s/*/simulation.log simulation.log", gatlingRoot, testName), debugOutputEnabled);
        SshClient.scpDownload(hostInfo, new SshClient.FromTo("simulation.log", String.format("%s/%s/simulation-%s.log", gatlingLocalResultsDir.getAbsolutePath(), testName, host)));

        // Indicate success to the caller. This key will be missing from the map if there were any exceptions.
        completedHosts.put(host, resultCode);
    }

    private String getJavaOpts() {
        return String.format("JAVA_OPTS=\"%s %s\"", DEFAULT_JVM_ARGS, inheritedGatlingJavaOpts);
    }

    private void log(String message) {
        if (debugOutputEnabled) {
            System.out.format("%s > %s%n", host, message);
        }
    }

    private boolean isValidDirectory(File directory) {
        return directory.exists() && directory.isDirectory() && directory.listFiles() != null && directory.listFiles().length > 0;
    }

    public void run() {
        try {
            this.runGatlingTest();
        } catch (IOException e) {
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
