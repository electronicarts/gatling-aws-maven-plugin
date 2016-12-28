/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling;

import com.amazonaws.auth.*;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class AwsGatlingRunner {

    private static final int INSTANCE_STATUS_SLEEP_MS = 16 * 1000;
    private final AmazonEC2Client ec2client;
    private TransferManager transferManager;
    private Tag instanceTag = new Tag("Name", "Gatling Load Generator");

    public AwsGatlingRunner(final String endpoint) {
        final AWSCredentialsProviderChain credentials = new AWSCredentialsProviderChain(
                new PropertiesFileCredentialsProvider("aws.properties"),
                new EnvironmentVariableCredentialsProvider(),
                new SystemPropertiesCredentialsProvider(),
                new ProfileCredentialsProvider(),
                new InstanceProfileCredentialsProvider());
        ec2client = new AmazonEC2Client(credentials);
        ec2client.setEndpoint(endpoint);
        transferManager = new TransferManager(credentials);
    }

    public Map<String, Instance> launchEC2Instances(final String instanceType, final int instanceCount, final String ec2KeyPairName, final String ec2SecurityGroup, final String amiId) {
        System.out.println(String.format("Did not find any existing instances, starting new ones with security group: '%s'", ec2SecurityGroup));
        return launchEC2Instances(instanceType,
                new RunInstancesRequestBuilder() {
                    public RunInstancesRequest build() {
                        return new RunInstancesRequest()
                                .withImageId(amiId)
                                .withInstanceType(instanceType)
                                .withMinCount(instanceCount)
                                .withMaxCount(instanceCount)
                                .withKeyName(ec2KeyPairName)
                                .withSecurityGroups(ec2SecurityGroup);

                    }
                }
        );
    }

    public Map<String, Instance> launchEC2Instances(final String instanceType, final int instanceCount, final String ec2KeyPairName, final String ec2SecurityGroupId, final String ec2SubnetId, final String amiId) {
        System.out.println(String.format("Did not find any existing instances, starting new ones with security group id: '%s' and subnet: '%s'", ec2SecurityGroupId, ec2SubnetId));
        return launchEC2Instances(instanceType,
                new RunInstancesRequestBuilder() {
                    public RunInstancesRequest build() {
                        return new RunInstancesRequest()
                                .withImageId(amiId)
                                .withInstanceType(instanceType)
                                .withMinCount(instanceCount)
                                .withMaxCount(instanceCount)
                                .withKeyName(ec2KeyPairName)
                                .withSecurityGroupIds(ec2SecurityGroupId)
                                .withSubnetId(ec2SubnetId);
                    }
                }
        );
    }

    private Map<String, Instance> launchEC2Instances(String instanceType, RunInstancesRequestBuilder runInstancesRequestBuilder) {
        Map<String, Instance> instances = new HashMap<String, Instance>();

        DescribeInstancesResult describeInstancesResult = ec2client.describeInstances(new DescribeInstancesRequest()
                .withFilters(getInstanceFilters(instanceType)));

        // Check for existing EC2 instances that fit the filter criteria and use those.
        for (Reservation reservation : describeInstancesResult.getReservations()) {
            for (Instance instance : reservation.getInstances()) {
                // If we found any existing EC2 instances put them into the instances variable.
                System.out.format("Reservations %s (%s): %s%n", instance.getInstanceId(), instance.getState().getName(), instance.getSecurityGroups().get(0).getGroupName());
                instances.put(instance.getInstanceId(), instance);
            }
        }

        // If instances is empty, that means we did not find any to reuse so let's create them
        if (instances.isEmpty()) {
            RunInstancesResult runInstancesResult = ec2client.runInstances(runInstancesRequestBuilder.build());

            for (Instance instance : runInstancesResult.getReservation().getInstances()) {
                System.out.println(instance.getInstanceId() + " launched");
                instances.put(instance.getInstanceId(), instance);
            }

            // Tag instances on creation. Adding the tag enables us to ensure we are terminating a load generator instance.
            ec2client.createTags(new CreateTagsRequest()
                    .withResources(instances.keySet())
                    .withTags(instanceTag));

            DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest().withInstanceIds(instances.keySet());

            startAllInstances(instances, describeInstancesRequest);
        }

        return instances;
    }

    private void startAllInstances(Map<String, Instance> instances, DescribeInstancesRequest describeInstancesRequest) {
        boolean allStarted = false;
        DescribeInstancesResult describeInstancesResult;

        while (!allStarted) {
            sleep(INSTANCE_STATUS_SLEEP_MS);
            allStarted = true;
            describeInstancesResult = ec2client.describeInstances(describeInstancesRequest);
            for (Reservation reservation : describeInstancesResult.getReservations()) {
                for (Instance instance : reservation.getInstances()) {
                    System.out.format("%s %s%n", instance.getInstanceId(), instance.getState().getName());
                    if (!instance.getState().getName().equals("running")) {
                        allStarted = false;
                    } else {
                        instances.put(instance.getInstanceId(), instance);
                    }
                }
            }

            DescribeInstanceStatusRequest describeInstanceStatusRequest = new DescribeInstanceStatusRequest().withInstanceIds(instances.keySet());
            boolean allInitialized = false;
            while (!allInitialized) {
                sleep(INSTANCE_STATUS_SLEEP_MS);
                DescribeInstanceStatusResult describeInstanceStatus = ec2client.describeInstanceStatus(describeInstanceStatusRequest);
                allInitialized = true;
                for (InstanceStatus instanceStatus : describeInstanceStatus.getInstanceStatuses()) {
                    System.out.format("%s %s%n", instanceStatus.getInstanceId(), instanceStatus.getInstanceStatus().getStatus());
                    if (!instanceStatus.getInstanceStatus().getStatus().equals("ok")) {
                        allInitialized = false;
                    }
                }
            }
        }
    }

    private Filter[] getInstanceFilters(String instanceType) {
        // Setup a filter to find any previously generated EC2 instances.
        Filter[] filters = new Filter[3];

        filters[0] = new Filter("tag:" + instanceTag.getKey()).withValues(instanceTag.getValue());
        filters[1] = new Filter("instance-state-name").withValues("running");
        filters[2] = new Filter("instance-type").withValues(instanceType);

        return filters;
    }

    public void terminateInstances(Collection<String> instanceIds) {
        DescribeInstancesResult describeInstancesResult = ec2client.describeInstances(new DescribeInstancesRequest().withInstanceIds(instanceIds));

        for (Reservation reservation : describeInstancesResult.getReservations()) {
            for (Instance instance : reservation.getInstances()) {
                if (!hasTag(instance, instanceTag)) {
                    System.out.format("Aborting since instance %s does not look like a gatling load generator.%n", instance.getInstanceId());
                    return;
                }
                System.out.format("Instance %s looks like a gatling load generator.%n", instance.getInstanceId());
            }
        }

        System.out.println("Terminating " + instanceIds);
        ec2client.terminateInstances(new TerminateInstancesRequest(new ArrayList<String>(instanceIds)));
    }

    public void uploadToS3(String s3bucket, String targetDirectory, File sourceDirectory) {
        // Recursively upload sourceDirectory to targetDirectory.
        for (File file : sourceDirectory.listFiles()) {
            if (file.isDirectory()) {
                uploadToS3(s3bucket, targetDirectory + "/" + file.getName(), file);
            } else if (file.isFile()) {
                Upload upload = transferManager.upload(new PutObjectRequest(s3bucket, targetDirectory + "/" + file.getName(), file).withCannedAcl(CannedAccessControlList.PublicRead));
                System.out.format("Uploading %s%n", file.getAbsolutePath());
                int statusChecks = 0;

                while (!upload.isDone()) {
                    sleep(100);
                    if (++statusChecks % 100 == 0) {
                        System.out.format("Still uploading %s%n", file.getAbsolutePath());
                    }
                }
                try {
                    upload.waitForCompletion();
                } catch (Exception e) {
                    System.out.format("Failed to upload to S3 %s/%s/%s%n", s3bucket, targetDirectory, file.getName());
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean hasTag(Instance instance, Tag theTag) {
        for (Tag tag : instance.getTags()) {
            if (tag.equals(theTag)) {
                return true;
            }
        }
        return false;
    }

    public void setInstanceTag(final Tag instanceTag)
    {
        this.instanceTag = instanceTag;
    }

    private void sleep(long timeMs) {
        try {
            Thread.sleep(timeMs);
        } catch (InterruptedException e) {
        }
    }

    private interface RunInstancesRequestBuilder {
        RunInstancesRequest build();
    }
}
