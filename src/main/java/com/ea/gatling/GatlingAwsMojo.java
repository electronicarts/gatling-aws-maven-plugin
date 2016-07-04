/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling;

import com.amazonaws.services.ec2.model.Instance;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs gatling script on remote EC2 instances.
 */
@Mojo(name = "execute")
public class GatlingAwsMojo extends AbstractMojo {
    /**
     * The time in milliseconds between checking for the termination of all executors running load tests.
     */
    private static final int SLEEP_TIME_TERMINATION_MS = 1000;

    @Parameter(property = "ec2.instance.count", defaultValue = "1")
    private Integer instanceCount;

    @Parameter(property = "ec2.instance.type", defaultValue = "m3.medium")
    private String instanceType;

    /**
     * ID of the Amazon Machine Image to be used. Defaults to Amazon Linux.
     */
    @Parameter(property = "ec2.ami.id", defaultValue = "ami-b66ed3de")
    private String ec2AmiId;

    @Parameter(property = "ec2.key.pair.name", defaultValue = "gatling-key-pair")
    private String ec2KeyPairName;

    /**
     * Create a security group for the Gatling EC2 instances. Ensure it allows inbound SSH traffic to your IP address range.
     */
    @Parameter(property = "ec2.security.group", defaultValue = "gatling-security-group")
    private String ec2SecurityGroup;

    @Parameter(property = "ec2.force.termination", defaultValue = "false")
    private boolean ec2ForceTermination = false;

    // This parameter, when set to true, will override the ec2ForceTermination and allow the EC2 instance to continue running for reuse
    @Parameter(property = "ec2.keep.alive", defaultValue="false")
    private boolean ec2KeepAlive = false;

    @Parameter(property = "ec2.end.point", defaultValue="https://ec2.us-east-1.amazonaws.com")
    private String ec2EndPoint;

    @Parameter(property = "ssh.private.key", defaultValue = "${user.home}/gatling-private-key.pem")
    private File sshPrivateKey;

    @Parameter(property = "debug.output.enabled", defaultValue = "false")
    private boolean debugOutputEnabled = false;

    @Parameter(property = "gatling.install.script", defaultValue = "${project.basedir}/src/test/resources/scripts/install-gatling.sh")
    private File installScript;

    @Parameter(defaultValue = "${project.basedir}/src/test/scala")
    private File gatlingSourceDir;

    @Parameter(property = "gatling.simulation", defaultValue = "Simulation")
    private String gatlingSimulation;

    @Parameter(defaultValue = "${project.basedir}/src/test/resources")
    private File gatlingResourcesDir;

    @Parameter(property = "gatling.test.name", defaultValue = "")
    private String testName = "";

    @Parameter(property = "path.config.file", defaultValue = "${project.basedir}/src/test/resources/config.properties")
    private File simulationConfig;

    @Parameter
    private Map<String, String> simulationOptions;

    @Parameter(property = "gatling.local.results", defaultValue = "${project.build.directory}/gatling/results")
    private File gatlingLocalResultsDir;

    @Parameter(property = "gatling.local.home", defaultValue = "${user.home}/gatling/gatling-charts-highcharts-bundle-2.1.4/bin/gatling.sh")
    private String gatlingLocalHome;

    @Parameter(property = "gatling.root", defaultValue = "gatling-charts-highcharts-bundle-2.1.4")
    private String gatlingRoot;

    /**
     * The JAVA_OPTS used when launching Gatling on the remote load generator. This allows users of the plugin to increase the heap space or change any other JVM settings.
     */
    @Parameter(property = "gatling.java.opts", defaultValue = "-Xms1g -Xmx6g")
    private String gatlingJavaOpts;

    @Parameter(property = "files")
    private List<String> files;

    @Parameter(property = "s3.upload.enabled", defaultValue = "false")
    private boolean s3UploadEnabled;

    @Parameter(property = "s3.region", defaultValue = "us-west-1")
    private String s3Region;

    @Parameter(property = "s3.bucket", defaultValue = "loadtest-results")
    private String s3Bucket;

    @Parameter(property = "s3.subfolder", defaultValue = "")
    private String s3Subfolder;

    public void execute() throws MojoExecutionException {
        AwsGatlingRunner runner = new AwsGatlingRunner(ec2EndPoint);

        Map<String, Instance> instances = runner.launchEC2Instances(instanceType, instanceCount, ec2KeyPairName, ec2SecurityGroup, ec2AmiId);
        ConcurrentHashMap<String, Boolean> successfulHosts = new ConcurrentHashMap<String, Boolean>();

        // launch all tests in parallel
        ExecutorService executor = Executors.newFixedThreadPool(instanceCount);

        long timeStamp = System.currentTimeMillis();
        testName = testName.equals("") ? gatlingSimulation.toLowerCase() + "-" + timeStamp : testName + "-" + timeStamp;
        File resultsDir = new File(gatlingLocalResultsDir, testName);
        boolean success = resultsDir.mkdirs();
        System.out.format("created result dir %s: %s%n", resultsDir.getAbsolutePath(), success);

        Collection<Instance> values = instances.values();
        int numInstance = 0;
        for (Instance instance : values) {
            String host = instance.getPublicDnsName();
            Runnable worker = new AwsGatlingExecutor(
                    host,
                    sshPrivateKey,
                    testName,
                    installScript,
                    gatlingSourceDir,
                    gatlingSimulation,
                    simulationConfig,
                    simulationOptions,
                    gatlingResourcesDir,
                    gatlingLocalResultsDir,
                    files,
                    numInstance++,
                    instanceCount,
                    successfulHosts,
                    gatlingRoot,
                    gatlingJavaOpts,
                    debugOutputEnabled);
            executor.execute(worker);
        }
        executor.shutdown();

        while (!executor.isTerminated()) {
            try {
                Thread.sleep(SLEEP_TIME_TERMINATION_MS);
            } catch (InterruptedException e) {
            }
        }
        System.out.println("Finished all threads");

        boolean allHostsSuccessful = successfulHosts.size() == instances.size();

        // If the ec2KeepAlive value is true then we need to skip terminating.
        if ((allHostsSuccessful || ec2ForceTermination) && !ec2KeepAlive) {
            runner.terminateInstances(instances.keySet());
        } else if (ec2KeepAlive) {
            // Send a message out stating the machines are still running
            System.out.println("EC2 instances are still running for the next load test");
        } else {
            // One or more hosts were unsuccessful. Keep the load generators running so that we can debug them and download the simulation results manually.
            listFailedInstances(instances, successfulHosts);
        }


        // Build report
        // TODO Parameterize heap space to allow generating larger reports
        String reportCommand = String.format("%s -ro %s/%s", gatlingLocalHome, gatlingLocalResultsDir, testName);
        System.out.format("Report command: %s%n", reportCommand);
        System.out.println(executeCommand(reportCommand));

        // Upload report to S3
        if (s3UploadEnabled) {
            System.out.format("Trying to upload simulation to S3 location %s/%s/%s%n", s3Bucket, s3Subfolder, testName);
            runner.uploadToS3(s3Bucket, s3Subfolder + "/" + testName, new File(gatlingLocalResultsDir + File.separator + testName));

            final String url = String.format("https://s3-%s.amazonaws.com/%s/%s/%s/index.html", s3Region, s3Bucket, s3Subfolder, testName);
            System.out.format("Results are on %s%n", url);

            // Write the results URL into a file. This provides the URL to external tools which might want to link to the results.
            try {
                FileUtils.fileWrite("results.txt", url);
            } catch (IOException e){
                System.err.println("Can't write result address: " + e);
            }
        } else {
            System.out.println("Skipping upload to S3.");
        }
    }

    private String executeCommand(String command) {
        StringBuffer output = new StringBuffer();

        try {
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            SshClient.printExitCode(exitCode);

            output.append(read(new BufferedReader(new InputStreamReader(process.getInputStream()))));
            output.append(read(new BufferedReader(new InputStreamReader(process.getErrorStream()))));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return output.toString();
    }

    private StringBuffer read(BufferedReader reader) throws IOException {
        StringBuffer output = new StringBuffer();
        String line;

        while ((line = reader.readLine()) != null) {
            output.append(line);
            output.append('\n');
        }

        return output;
    }

    private void listFailedInstances(Map<String, Instance> instances, ConcurrentHashMap<String, Boolean> successfulHosts) {
        System.out.format("%d load generators were unsuccessful. Hostnames: %n", instances.size() - successfulHosts.size());

        for (Instance instance : instances.values()) {
            String host = instance.getPrivateDnsName();

            if (!successfulHosts.containsKey(host)) {
                System.out.println(host);
            }
        }

        System.out.println();
    }
}
