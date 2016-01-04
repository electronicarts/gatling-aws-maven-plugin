#!/bin/sh
# Copyright (C) 2016 Electronic Arts Inc. All rights reserved.

# Before running this, ensure you did the following:
# (1) Insert your AWS IAM secret/access key into the aws.properties file in the current directory.
# (2) Execute the downloadGatling.sh script if you have not already done this.

# Start the load test on a single EC2 instance.
mvn -Dgatling.skip=true clean package com.ea.gatling:gatling-aws-maven-plugin:execute

#
# Same as above but runs test on 3x m3.large EC2 instances instead of just one less powerful instance.
# Keep in mind that this will result in higher EC2 costs (see https://aws.amazon.com/ec2/pricing/).
# 
# mvn package -Dgatling.skip=true -Dec2.instance.type=m3.large -Dec2.instance.count=3 com.ea.gatling:gatling-aws-maven-plugin:execute

