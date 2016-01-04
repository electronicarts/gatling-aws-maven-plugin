#!/bin/sh

# Increase the maximum number of open files
echo "*       soft    nofile  65535" | sudo tee --append /etc/security/limits.conf 
echo "*       hard    nofile  65535" | sudo tee --append /etc/security/limits.conf

# Remove Java 7
sudo yum remove  --quiet --assumeyes java-1.7.0-openjdk.x86_64

# Install Java 8 and other requirements
sudo yum install --quiet --assumeyes java-1.8.0-openjdk-devel.x86_64 htop screen

# Install Gatling
GATLING_VERSION=2.1.7
URL=https://repo1.maven.org/maven2/io/gatling/highcharts/gatling-charts-highcharts-bundle/${GATLING_VERSION}/gatling-charts-highcharts-bundle-${GATLING_VERSION}-bundle.zip
GATLING_ARCHIVE=gatling-charts-highcharts-bundle-${GATLING_VERSION}-bundle.zip

wget --quiet ${URL} -O ${GATLING_ARCHIVE}
unzip -q -o ${GATLING_ARCHIVE}

# Remove example code to reduce Scala compilation time at the beginning of load test
rm -rf gatling-charts-highcharts-bundle-${GATLING_VERSION}/user-files/simulations/computerdatabase/
