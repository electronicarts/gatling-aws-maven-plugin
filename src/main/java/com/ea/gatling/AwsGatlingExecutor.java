/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ConcurrentHashMap<String, Boolean> successfulHosts;
    private final String gatlingRoot;
    private final String inheritedGatlingJavaOpts;
    private final boolean debugOutputEnabled;

    public AwsGatlingExecutor(String host, File sshPrivateKey, String testName, File installScript, File gatlingSourceDir, String gatlingSimulation, File simulationConfig, Map<String, String> simulationOptions, File gatlingResourcesDir, File gatlingLocalResultsDir, List<String> additionalFiles, int numInstance, int instanceCount, ConcurrentHashMap<String, Boolean> successfulHosts, String gatlingRoot, String inheritedGatlingJavaOpts, boolean debugOutputEnabled) {
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
        this.successfulHosts = successfulHosts;
        this.gatlingRoot = gatlingRoot;
        this.inheritedGatlingJavaOpts = inheritedGatlingJavaOpts;
        this.debugOutputEnabled = debugOutputEnabled;
    }

    public void runGatlingTest() throws IOException {
        log("started");

        // copy scripts
        SshClient.scpUpload(host, SSH_USER, sshPrivateKey, installScript.getAbsolutePath(), "");
        SshClient.executeCommand(host, SSH_USER, sshPrivateKey, "chmod +x install-gatling.sh; ./install-gatling.sh", debugOutputEnabled);
        // write information about the instance into a text file to allow the load test to read it if necessary.
        SshClient.executeCommand(host, SSH_USER, sshPrivateKey, String.format("echo \"num_instance=%s%ninstance_count=%s\" >> instance.txt", numInstance, instanceCount), debugOutputEnabled);

        log("Copying additional files " + additionalFiles);
        for (String path : additionalFiles) {
            log("Copying additional file " + path);
            SshClient.scpUpload(host, SSH_USER, sshPrivateKey, path, "");
        }

        final File targetFolder = new File("target");
        if (isValidDirectory(targetFolder)) {
            log("Copying additional JAR files");
            for (File file : targetFolder.listFiles()) {
                String path = file.getAbsolutePath();
                if (path.endsWith("-jar-with-dependencies.jar")) {
                    log("Copying JAR file " + path);
                    SshClient.scpUpload(host, SSH_USER, sshPrivateKey, path, gatlingRoot + "/lib");
                }
            }
        }

        // copy resource files
        for (String resource : GATLING_RESOURCES) {
            log("Copying resource " + resource);
            String resourceDir = gatlingResourcesDir.getAbsolutePath() + "/" + resource;
            SshClient.scpUpload(host, SSH_USER, sshPrivateKey, resourceDir, gatlingRoot + "/user-files");
        }

        // copy simulation config
        SshClient.scpUpload(host, SSH_USER, sshPrivateKey, simulationConfig.getAbsolutePath(), "");

        // copy simulation files
        if (isValidDirectory(gatlingSourceDir)) {
            log("Copying simulation files");
            for (File file : gatlingSourceDir.listFiles()) {
                SshClient.scpUpload(host, SSH_USER, sshPrivateKey, file.getAbsolutePath(), gatlingRoot + "/user-files/simulations");
            }
        }

        // copy simulation gatling configuration files if it exists
        final File configFolder = new File("src/test/resources/conf");
        if (isValidDirectory(configFolder)) {
            log("Copying gatling configuration files");
            for (File file : configFolder.listFiles()) {
                String path = file.getAbsolutePath();
                log("Copying gatling configuration file: " + path);
                SshClient.scpUpload(host, SSH_USER, sshPrivateKey, path, gatlingRoot + "/conf");
            }
        }

        // start test
        // TODO add parameters for test name and description
        SshClient.executeCommand(host, SSH_USER, sshPrivateKey, String.format("%s %s/bin/gatling.sh -s %s -on %s -rd test -nr -rf results/%s", getJavaOpts(), gatlingRoot, gatlingSimulation, testName, testName), debugOutputEnabled);

        // download report
        log(testName);
        SshClient.executeCommand(host, SSH_USER, sshPrivateKey, String.format("mv %s/results/%s/*/simulation.log simulation.log", gatlingRoot, testName), debugOutputEnabled);
        SshClient.scpDownload(host, SSH_USER, sshPrivateKey, "simulation.log", String.format("%s/%s/simulation-%s.log", gatlingLocalResultsDir.getAbsolutePath(), testName, host));

        // Indicate success to the caller. This key will be missing from the map if there were any exceptions.
        successfulHosts.put(host, true);
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
        return directory.exists() && directory.isDirectory() && directory.listFiles() != null;
    }

    public void run() {
        try {
            this.runGatlingTest();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
