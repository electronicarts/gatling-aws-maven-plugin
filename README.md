# Gatling AWS Maven Plugin

[![Build Status](https://img.shields.io/travis/electronicarts/gatling-aws-maven-plugin.svg)](https://travis-ci.org/electronicarts/gatling-aws-maven-plugin)
[![Gitter](https://img.shields.io/badge/style-Join_Chat-ff69b4.svg?style=flat&label=gitter)](https://gitter.im/electronicarts/gatling-aws-maven-plugin?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)

The Gatling AWS Maven plugin takes the pain out of scaling up your Gatling tests. It runs your load test on a configurable number of EC2 instances, aggregates a single load test report, and uploads the results to S3. All EC2 instances are terminated at the end of the test to ensure you are only paying for what you need.

# Getting Started

This section shows you which setup is required to use the plugin, how to configure your existing [Maven](https://maven.apache.org/) project, how to run tests locally for testing, and how to let [Jenkins](https://jenkins.io/) start a cluster of [EC2](https://aws.amazon.com/ec2) instances that will run your load test.

For the 5 minute version of this document, take a look at [Quickstart](https://github.com/electronicarts/gatling-aws-maven-plugin/wiki/Quickstart).

## AWS Setup
Make the following changes in AWS to allow the Gatling AWS Maven plugin to launch EC2 instances and upload the results to S3 on your behalf.

1. Create a S3 bucket to upload results to e.g. `loadtest-results`.
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

This will spin up 3 c3.large instances and start the `com.FooTest` simulation on each instance.

# Additional Information

* [Quickstart](https://github.com/electronicarts/gatling-aws-maven-plugin/wiki/Quickstart)
* [Load Testing Best Practices](https://github.com/electronicarts/gatling-aws-maven-plugin/wiki/Load-Testing-Best-Practices)
* [Example Gatling Reports](https://github.com/electronicarts/gatling-aws-maven-plugin/wiki/Example-Gatling-Reports)
* [Plugin Phases](https://github.com/electronicarts/gatling-aws-maven-plugin/wiki/Plugin-Phases)
* [Maven Properties](https://github.com/electronicarts/gatling-aws-maven-plugin/wiki/Maven-properties)

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
