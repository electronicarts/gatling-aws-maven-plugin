/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Tag;

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
public class GatlingAwsMojo extends BaseAwsMojo {
    /**
     * The time in milliseconds between checking for the termination of all executors running load tests.
     */
    private static final int SLEEP_TIME_TERMINATION_MS = 1000;

    @Parameter(property = "ssh.private.key", defaultValue = "${user.home}/gatling-private-key.pem")
    private File sshPrivateKey;

    @Parameter(property = "ssh.user", defaultValue = "ec2-user")
    private String sshUser;

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

    @Parameter(property = "gatling.local.results", defaultValue = "${project.build.directory}/gatling/results")
    private File gatlingLocalResultsDir;

    @Parameter(property = "gatling.remote.log", defaultValue = "gatling-charts-highcharts-bundle-2.1.4/target/logs/logfile.txt")
    private String gatlingRemoteLog;

    @Parameter(property = "gatling.remote.download", defaultValue = "false")
    private boolean downloadRemoteLog;

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

    @Parameter(property = "propagate.gatling.failure", defaultValue = "false")
    private boolean propagateGatlingFailure;

    @Parameter(property = "prefer.private.ip.hostnames", defaultValue = "false")
    private boolean preferPrivateIpHostnames;


    /**
     * When true, this will run Gatling detached, and disconnect from SSH while Gatling is running.  Leaves a
     *    file called 'gatling.pid' with the pid of the java process in it.
     * ec2.keep.alive ignored when set to true
     * ec2.force.termination ignored when set to true
     * All output turned off
     */
    @Parameter(property = "ec2.execute.detached", defaultValue = "false")
    private boolean ec2ExecuteDetached = false;


    public void execute() throws MojoExecutionException {
        final AwsGatlingRunner runner = new AwsGatlingRunner(this.ec2EndPoint);
        runner.setInstanceTag(new Tag(this.ec2TagName, this.ec2TagValue));

        final Map<String, Instance> instances = this.ec2SecurityGroupId != null
                ? runner.launchEC2Instances(this.instanceType, this.instanceCount, this.ec2KeyPairName, this.ec2SecurityGroupId, this.ec2SubnetId, this.ec2AmiId, true)
                : runner.launchEC2Instances(this.instanceType, this.instanceCount, this.ec2KeyPairName, this.ec2SecurityGroup, this.ec2AmiId, true);
        final ConcurrentHashMap<String, Integer> completedHosts = new ConcurrentHashMap<>();

        // launch all tests in parallel
        final ExecutorService executor = Executors.newFixedThreadPool(this.instanceCount);

        final long timeStamp = System.currentTimeMillis();
        this.testName = this.testName.equals("") ? this.gatlingSimulation.toLowerCase() + "-" + timeStamp : this.testName + "-" + timeStamp;
        final File resultsDir = new File(this.gatlingLocalResultsDir, this.testName);
        final boolean success = resultsDir.mkdirs();
        System.out.format("created result dir %s: %s%n", resultsDir.getAbsolutePath(), success);

        final Collection<Instance> values = instances.values();
        int numInstance = 0;
        for (final Instance instance : values) {
            final String host = this.getPreferredHostName(instance);
            final Runnable worker = new AwsGatlingExecutor(
                    host,
                    this.sshUser,
                    this.sshPrivateKey,
                    this.testName,
                    this.installScript,
                    this.gatlingSourceDir,
                    this.gatlingSimulation,
                    this.simulationConfig,
                    this.gatlingResourcesDir,
                    this.gatlingLocalResultsDir,
                    this.files,
                    numInstance++,
                    this.instanceCount,
                    completedHosts,
                    this.gatlingRoot,
                    this.gatlingJavaOpts,
                    this.debugOutputEnabled,
                    this.ec2ExecuteDetached,
                    this.downloadRemoteLog,
                    this.gatlingRemoteLog);
            executor.execute(worker);
        }
        executor.shutdown();

        while (!executor.isTerminated()) {
            try {
                Thread.sleep(SLEEP_TIME_TERMINATION_MS);
            } catch (final InterruptedException e) {
            }
        }
        System.out.println("Finished all threads");

        final int failedInstancesCount = this.listFailedInstances(instances, completedHosts);

        // If the ec2KeepAlive value is true then we need to skip terminating.
        if ((failedInstancesCount == 0 || this.ec2ForceTermination) && !this.ec2KeepAlive && !this.ec2ExecuteDetached) {
            runner.terminateInstances(instances.keySet());
        } else if (this.ec2KeepAlive) {
            // Send a message out stating the machines are still running
            System.out.println("EC2 instances are still running for the next load test");
        } else if (this.ec2ExecuteDetached) {
            System.out.println("EC2 instances are running detached");
        }

        if (!this.ec2ExecuteDetached) {
            // Build report
            final String reportCommand = String.format("%s -ro %s/%s", this.gatlingLocalHome, this.gatlingLocalResultsDir, this.testName);
            System.out.format("Report command: %s%n", reportCommand);
            System.out.println(this.executeCommand(reportCommand));

            // Upload report to S3
            if (this.s3UploadEnabled) {
                System.out.format("Trying to upload simulation to S3 location %s/%s/%s%n", this.s3Bucket, this.s3Subfolder,
                        this.testName);
                runner.uploadToS3(this.s3Bucket, this.s3Subfolder + "/" + this.testName,
                        new File(this.gatlingLocalResultsDir + File.separator + this.testName));

                final String url = this.getS3Url();
                System.out.format("Results are on %s%n", url);

                try {
                    // Write the results URL into a file. This provides the URL to external tools which might want to link to the results.
                    FileUtils.fileWrite("results.txt", url);
                } catch (final IOException e) {
                    System.err.println("Can't write result address: " + e);
                }
            } else {
                System.out.println("Skipping upload to S3.");
            }
        } else {
            System.out.println("Running detached, no reports available.");
        }

        if (this.propagateGatlingFailure && failedInstancesCount > 0) {
            throw new MojoExecutionException("Some gatling simulation failed: " + failedInstancesCount);
        }
    }

    private String getS3Url() {
        if ("us-east-1".equalsIgnoreCase(this.s3Region)) {
            // us-east-1 has no prefix - http://docs.aws.amazon.com/general/latest/gr/rande.html#s3_region
            return String.format("https://s3.amazonaws.com/%s/%s/%s/index.html", this.s3Bucket, this.s3Subfolder, this.testName);
        }
        return String.format("https://s3-%s.amazonaws.com/%s/%s/%s/index.html", this.s3Region, this.s3Bucket, this.s3Subfolder, this.testName);
    }

    private String executeCommand(final String command) {
        final StringBuffer output = new StringBuffer();

        try {
            final Process process = Runtime.getRuntime().exec(command);
            final int exitCode = process.waitFor();
            SshClient.printExitCode(exitCode);

            output.append(this.read(new BufferedReader(new InputStreamReader(process.getInputStream()))));
            output.append(this.read(new BufferedReader(new InputStreamReader(process.getErrorStream()))));
        } catch (final Exception e) {
            e.printStackTrace();
        }

        return output.toString();
    }

    private StringBuffer read(final BufferedReader reader) throws IOException {
        final StringBuffer output = new StringBuffer();
        String line;

        while ((line = reader.readLine()) != null) {
            output.append(line);
            output.append('\n');
        }

        return output;
    }

    private int listFailedInstances(final Map<String, Instance> instances, final ConcurrentHashMap<String, Integer> completedHosts) {
        int failedInstancesCount = instances.size() - completedHosts.size();

        for (final Instance instance : instances.values()) {
            final String host = this.getPreferredHostName(instance);

            if (!completedHosts.containsKey(host)) {
                System.out.format("No result collected from hostname: %s%n", host);
            } else if (completedHosts.get(host) != 0) {
                System.out.format("Unsuccessful result code: %d on hostname: %s%n", completedHosts.get(host), host);
                failedInstancesCount++;
            }
        }
        System.out.format("Load generators were unsuccessful. Failed instances count: %d%n", failedInstancesCount);

        System.out.println();
        return failedInstancesCount;
    }

    private String getPreferredHostName(final Instance instance) {
        if (this.preferPrivateIpHostnames) {
            return instance.getPrivateIpAddress();
        }

        return instance.getPublicDnsName();
    }
}
