#!/bin/sh
GATLING_VERSION=2.3.1

mkdir -p gatling
cd gatling
curl https://repo1.maven.org/maven2/io/gatling/highcharts/gatling-charts-highcharts-bundle/${GATLING_VERSION}/gatling-charts-highcharts-bundle-${GATLING_VERSION}-bundle.zip -o gatling-charts-highcharts-bundle-${GATLING_VERSION}-bundle.zip
unzip gatling-charts-highcharts-bundle-${GATLING_VERSION}-bundle.zip
