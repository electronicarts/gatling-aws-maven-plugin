# Gatling AWS Maven Plugin

[![Join the chat at https://gitter.im/electronicarts/gatling-aws-maven-plugin](https://badges.gitter.im/electronicarts/gatling-aws-maven-plugin.svg)](https://gitter.im/electronicarts/gatling-aws-maven-plugin?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

[![Build Status](https://img.shields.io/travis/electronicarts/gatling-aws-maven-plugin.svg)](https://travis-ci.org/electronicarts/gatling-aws-maven-plugin)
[![Gitter](https://img.shields.io/badge/style-Join_Chat-ff69b4.svg?style=flat&label=gitter)](https://gitter.im/electronicarts/gatling-aws-maven-plugin?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)

The Gatling AWS Maven plugin takes the pain out of scaling up your Gatling tests. It runs your load test on a configurable number of EC2 instances, aggregates a single load test report, and uploads the results to S3. All EC2 instances are terminated at the end of the test to ensure you are only paying for what you need.

# Getting Started

This section shows you which setup is required to use the plugin, how to configure your existing Maven project, how to run tests locally for testing, and how to let Jenkins start a cluster of EC2 instances that will run your load test.

## AWS Setup
Make the following changes in AWS to allow the Gatling AWS Maven plugin to launch EC2 instances and upload the results to S3 on your behalf.

1. Create a S3 bucket to upload results to e.g. loadtest-results.
2. Create new access/secret key in IAM with the following permissions. Ensure that the S3 bucket you picked is referenced in the `Resource` section of `"Action":"s3:*"`.

```javascript
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ec2:RunInstances",
        "ec2:StopInstances",
        "ec2:CreateTags",
        "ec2:TerminateInstances",
        "ec2:Describe*"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": "autoscaling:Describe*",
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:*",
      "Resource": "arn:aws:s3:::loadtest-results/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:Get*",
        "s3:List*"
      ],
      "Resource": "*"
    }
  ]
}
```

This example policy is very permissive. [Consider restricting it](http://docs.aws.amazon.com/IAM/latest/UserGuide/introduction.html) more depending on your needs.

The plugin expects that the access/secret key are provided at runtime to interact with EC2 and S3. Please refer to the [AWS documentation](http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/credentials.html) on how to provide the keys to an application. To use the plugin on Jenkins, we recommend setting up password parameters for both values. You can then inject both as environment variables or write them into a temporary `aws.properties` file.

3. In your pom.xml file, set the Maven property `${ec2.key.pair.name}` to the name of the new EC2 key pair you created for the access/secret key (or use the default "gatling-key-pair").

## Maven integration

Create a new Maven project that follows the following structure:

1. Create a Gatling simulation (e.g. com.FooTest) in `src/test/scala/com/FooTest.scala`. See <a href="http://gatling.io/docs/2.1.7/general/concepts.html">the Gatling concepts docs</a> for more information.
2. Put a gatling.conf and logback.xml file in `src/test/resources`. See <a href="http://gatling.io/docs/2.1.7/general/configuration.html">the Gatling configuration docs</a> for more information.
3. Create a `install-gatling.sh` script in `src/test/resources` This script will run on each load generator to install Gatling and do any other setup necessary before starting your test. Make sure **the script is executable** and looks similar to the following:

```
#!/bin/sh
# Increase the maximum number of open files
sudo ulimit -n 65536
echo "*       soft    nofile  65535" | sudo tee --append /etc/security/limits.conf
echo "*       hard    nofile  65535" | sudo tee --append /etc/security/limits.conf

# Replace Java 7 with 8 and install other requirements
sudo yum remove  --quiet --assumeyes java-1.7.0-openjdk.x86_64
sudo yum install --quiet --assumeyes java-1.8.0-openjdk-devel.x86_64 htop screen

# Install Gatling
GATLING_VERSION=2.1.7
URL=https://repo1.maven.org/maven2/io/gatling/highcharts/gatling-charts-highcharts-bundle/${GATLING_VERSION}/gatling-charts-highcharts-bundle-${GATLING_VERSION}-bundle.zip
GATLING_ARCHIVE=gatling-charts-highcharts-bundle-${GATLING_VERSION}-bundle.zip

wget --quiet ${URL} -O ${GATLING_ARCHIVE}
unzip -q -o ${GATLING_ARCHIVE}

# Remove example code to reduce Scala compilation time at the beginning of load test
rm -rf gatling-charts-highcharts-bundle-${GATLING_VERSION}/user-files/simulations/computerdatabase/
```

4. Make sure your pom.xml contains the following properties, dependencies, and plugins. Start by setting the following properties to configure the access to AWS and control how Gatling will be executed on the remote load generators.

```xml
  <properties>
    <gatling.version>2.1.7</gatling.version>
    <gatling-plugin.version>2.1.7</gatling-plugin.version>
    <gatling.skip>false</gatling.skip>

    <!-- Information required to start EC2 instances and control them via SSH -->
    <ssh.private.key>${user.home}/.ssh/loadtest.pem</ssh.private.key>
    <ec2.key.pair.name>loadtest-keypair</ec2.key.pair.name>
    <ec2.security.group>default</ec2.security.group>
    <ec2.instance.count>1</ec2.instance.count>

    <gatling.local.home>${project.basedir}/gatling/gatling-charts-highcharts-bundle-2.1.7/bin/gatling.sh</gatling.local.home>
    <gatling.install.script>${project.basedir}/src/test/resources/install-gatling.sh</gatling.install.script>
    <gatling.root>gatling-charts-highcharts-bundle-2.1.7</gatling.root>
    <gatling.java.opts> -Xms1g -Xmx25g -Xss8M -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=256M</gatling.java.opts>

    <!-- Fully qualified name of the Gatling simulation and a name describing the test -->
    <gatling.simulation>com.FooTest</gatling.simulation>
    <gatling.test.name>LoadTest</gatling.test.name>

    <!-- S3 integration settings -->
    <s3.upload.enabled>true</s3.upload.enabled>
    <s3.bucket>loadtest-results</s3.bucket>
    <s3.subfolder>my-loadtest</s3.subfolder>

    <!-- Any additional properties you might have -->
  </properties>
```

5. Add the following two dependencies:

```xml
  <dependencies>
    <dependency>
      <groupId>org.scala-lang</groupId>
      <artifactId>scala-library</artifactId>
      <version>2.11.4</version>
    </dependency>
    <dependency>
      <groupId>io.gatling.highcharts</groupId>
      <artifactId>gatling-charts-highcharts</artifactId>
      <version>${gatling.version}</version>
      <scope>test</scope>
    </dependency>
    <!-- Any additional dependencies you might have -->
  <dependencies>
```

6. Add the following two plugins to your `<build>` section.

`io.gatling:gatling-maven-plugin` will allow you to run `com.FooTest` locally for fast testing and troubleshooting. Consider enabling the JVM arguments for debugging. If you have seen your test fail remotely, this is a great way to quickly understand and fix problems on your local dev environment. Specify additional `<jvmArg>` elements to customize the heap size to allow you to run bigger tests locally.

`com.ea.gatling:gatling-aws-maven-plugin` will allow you to run `com.FooTest` at scale on a cluster of EC2 instances based on the properties you configured above.

```xml
  <build>
    <sourceDirectory>src/test/scala</sourceDirectory>
    <testSourceDirectory>src/test/scala</testSourceDirectory>
    <plugins>
      <!-- Required for running smaller Gatling simulations locally for debugging purposes -->
      <plugin>
        <groupId>io.gatling</groupId>
        <artifactId>gatling-maven-plugin</artifactId>
        <version>${gatling-plugin.version}</version>
        <executions>
          <execution>
            <phase>compile</phase>
            <goals>
              <goal>execute</goal>
            </goals>
          </execution>
        </executions>
        <configuration>
          <dataFolder>src/test/resources/data</dataFolder>
          <resultsFolder>target/gatling/results</resultsFolder>
          <simulationsFolder>src/test/scala</simulationsFolder>
          <simulationClass>${gatling.simulationClass}</simulationClass>
          <jvmArgs>
            <!-- Enable this for debugging: -->
            <!--jvmArg>-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=7000</jvmArg-->
          </jvmArgs>
        </configuration>
      </plugin>
      <!-- Required for running large scale Gatling simulations on EC2 instances -->
      <plugin>
        <groupId>com.ea.gatling</groupId>
        <artifactId>gatling-aws-maven-plugin</artifactId>
        <version>0.23</version>
        <configuration>
          <simulationOptions>
            <custom.simulation.option>some value</custom.simulation.option>
          </simulationOptions>
        </configuration>
      </plugin>

      <!-- Any additional plugins you might have -->
    </plugins>
  </build>
```

You should now be able to run tests locally and launch EC2 instances to run your test remotely. The next two section will show you how.

## Running tests locally

To generate load from your local dev environment, run a normal `mvn clean install`. Ensure `gatling.simulationClass` points to the correct class name and set `gatling.skip` set to false.

    $ mvn                                   \
    -Dgatling.simulationClass=com.FooTest \
    -Dgatling.skip=false -DskipTests      \
    clean install

## Running tests remotely

Use the `com.ea.gatling:gatling-aws-maven-plugin:execute` goal to launch EC2 instances to generate load. You will want to use this goal when integrating the Gatling AWS Maven plugin with Jenkins.

If your test has dependencies required to run, consider adding the `assembly:single` goal. This will assemble all of your dependencies in a single artifact which will be distributed to all load generators during the setup phase. A typical use case for this would be running a test which depends on POJOs or any other existing code from your client/server codebase which is represented by Maven artifacts.

Example:

    $ mvn                                 \
    -Dec2.instance.count=3                \
    -Dec2.instance.type=c3.large          \
    -Dgatling.simulationClass=com.FooTest \
    -Dgatling.skip=true -DskipTests       \
    clean install                         \
    assembly:single com.ea.gatling:gatling-aws-maven-plugin:execute

This will spin up 3 c3.large instances and start the com.FooTest simulation on each instance.

## Maven properties

| Name | Default | Description | Example |
|------|---------|-------------|---------|
| ec2.instance.count | 1 | Number of instances that will be launched | 2 |
| ec2.instance.type | m3.medium | Type of the EC2 instances that will be launched | m3.medium |
| ec2.ami.id | ami-b66ed3de |  |  |
| ec2.key.pair.name | gatling-key-pair |  |  |
| ec2.security.group | gatling-security-group |  |  |
| ssh.private.key | ${user.home}/gatling-private-key.pem |  |  |
| gatling.install.script | ${project.basedir}/src/test/resources/scripts/install-gatling.sh | Path to the script used to install Gatling on each load generator and configure the instance accordingly. |  |
| gatling.simulation | Simulation | Fully qualified class name of the Gatling simulation that will be executed on each load generator |  |
| gatling.test.name | (empty) | Short description of the test that is going to run. |  |
| path.config.file | ${project.basedir}/src/test/resources/config.properties | Path to your tests configuration file. |  |
| gatling.local.results |  |  |  |
| gatling.local.home |  |  |  |
| gatling.root | gatling-charts-highcharts-bundle-2.1.4 | The name of the gatling root directory on the load generator instances. Update this if your installation script is installing a custom version of Gatling resulting in a different folder name. |  |
| gatling.java.opts | -Xms1g -Xmx6g | Any additional JVM arguments you want to pass through to Gatling. Use this to increase the heap space, open ports for debugging, set environment variables, etc. |  |
| s3.upload.enabled | false | Enable or disable the upload of the final report to S3. |  |
| s3.bucket | loadtest-results | Name of the S3 bucket that the load test results will be uploaded to. |  |
| s3.subfolder | (empty string) | Name of the subfolder within ${s3.bucket} to which the load test results will be uploaded to. Consider using this to organize your reports within S3. |  |

# Best Practices

## Logging
* While you are developing and troubleshooting your load test script, consider a combination of debug messages and actual local debugging. If you leave the logging statements in your script, consider their impact on CPU utilization and disk space. Minimize logging for large scale tests to the absolute minimum.

## Monitoring
* Monitor the load test while it is running to verify it is behaving the way you expect. Take advantage of the AWS CloudWatch metrics of your load generator instances. Also note that you can configure monitoring each load generator with Graphite through the `gatling.conf` configuration file. Take advantage of additional monitoring by SSH-ing into the load generators during the test using the same SSH key pair used by Jenkins.

## Jenkins
* Pipelines: Consider using [Jenkins pipelines](https://wiki.jenkins-ci.org/display/JENKINS/Build+Pipeline+Plugin) for the different tasks involved in load testing. We recommend creating a pipeline of the following jobs: (1) Update the load test environment with the latest code. (2) Kick off a load test against the freshly updated environment. (3) Append the results link to an external system like Confluence.
 Consider using build parameters to configure your load test. Among other things, we frequently wanted to parameterize the following values: URL of the server you want to test (this allows you to vary the load test environments), number and type of load generators, name of the specific scenario you want to load test (your simulation file can pickup this value to send different kinds of traffic patterns e.g. cold spike, stair case, soak, sine-wave), etc.
* Utilization: The Gatling AWS Maven has a very specific performance profile. For the majority of the test, the plugin is network IO heavy (phase 1 and 2). Once the actual test is over, the aggregation of the results is very CPU intensive. As a result, we recommend having a dedicated [Jenkins slave](https://wiki.jenkins-ci.org/display/JENKINS/Distributed+builds) for running the tests. Ideally this slave is an EC2 instance itself. The network IO between the Jenkins build slave and the load generators will benefit from that and it simplifies changing the size of the slave as necessary to reduce costs.
* Disk Space: Wipe the workspace at the start of the test to reduce the disk space requirements on Jenkins. Especially when long-running tests are logging errors and warnings for long periods of time, you will need a lot of temporary disk space on the Jenkins slave. This space can be reclaimed once the simulation results have been archived to S3.

# Phases

This section describes the different phases the Gatling AWS Maven plugin goes through. Understanding this should enable you to troubleshoot and customize the plugin's behaviour more easily.

## Phase 1: Setup
1. Launch EC2 instances according to the EC2 parameters e.g. ec2.instance.count and ec2.instance.type.
2. Distribute dependencies (JAR files, simulation files, configuration files, Gatling install script) to each load generator.
3. Configure each load generator to ensure it is ready to run the test (e.g. install the Java SDK, raise the max number of open files).

## Phase 2: Run test
Start the test on each load generator instance. Send status updates back to Jenkins. Optionally report status of each load generator to Graphite. Wait for the completion of the test. In most cases this will be the longest phase by far.

## Phase 3: Data aggregation
1. Download results from all load generators
2. Consume all simulation files to generate a single Gatling report
3. Upload the report to S3 and ensure it is publicly accessible
4. Share configuration parameters and a link to the results with other systems e.g. Confluence.

# Credits

We want to thank the Gatling team for creating a great load testing tool and maintaining a very active community around it.

The main authors of the plugin are Yuriy Gapon and Ingo Jaeckel.

# License

Copyright (C) 2016 Electronic Arts Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1.  Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
2.  Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
    its contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
