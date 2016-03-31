/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling;

import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class AwsGatlingRunner {

    private static final Tag GATLING_NAME_TAG = new Tag("Name", "Gatling Load Generator");
    private static final int INSTANCE_STATUS_SLEEP_MS = 16 * 1000;
    private final AmazonEC2Client ec2client;
    private TransferManager transferManager;

    public AwsGatlingRunner() {
        try {
            PropertiesCredentials credentials = new PropertiesCredentials(new File("aws.properties"));
            ec2client = new AmazonEC2Client(credentials);
            transferManager = new TransferManager(credentials);
        } catch (IOException e) {
            System.err.println("Unable to read AWS credentials from aws.properties file.");
            throw new RuntimeException(e);
        }
    }

    public Map<String, Instance> launchEC2Instances(String instanceType, int instanceCount, String ec2KeyPairName, String ec2SecurityGroup, String amiId) {
        Map<String, Instance> instances = new HashMap<String, Instance>();

        // Setup a filter to find any previously generated EC2 instances
        Filter[] filters = new Filter[2];
        filters[0] = new Filter("tag:Name").withValues(GATLING_NAME_TAG.getValue());
        filters[1] = new Filter("instance-state-name").withValues("running");

        DescribeInstancesResult describeInstancesResult = ec2client.describeInstances(new DescribeInstancesRequest().withFilters(filters));

        // Check for existing EC2 instances that fit the filter criteria and use those
        for (Reservation reservation : describeInstancesResult.getReservations()) {
            for (Instance instance : reservation.getInstances()) {
                System.out.println("Reservations " + instance.getInstanceId() + " (" + instance.getState().getName() + "): " + instance.getSecurityGroups().get(0).getGroupName());
                instances.put(instance.getInstanceId(), instance);
            }

        }

        // Only generate instances if none were found for reuse
        if(instances.isEmpty()) {
            RunInstancesResult runInstancesResult = ec2client.runInstances(new RunInstancesRequest()
                    .withImageId(amiId)
                    .withInstanceType(instanceType)
                    .withMinCount(instanceCount)
                    .withMaxCount(instanceCount)
                    .withKeyName(ec2KeyPairName)
                    .withSecurityGroups(ec2SecurityGroup));

            for (Instance instance : runInstancesResult.getReservation().getInstances()) {
                System.out.println(instance.getInstanceId() + " launched");
                instances.put(instance.getInstanceId(), instance);
            }

            // Tag instances on creation. Adding the tag enables us to ensure we are terminating a load generator instance.
            ec2client.createTags(new CreateTagsRequest()
                    .withResources(instances.keySet())
                    .withTags(GATLING_NAME_TAG));

            DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest().withInstanceIds(instances.keySet());
            boolean allStarted = false;
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

        return instances;
    }

    public void terminateInstances(Collection<String> instanceIds) {
        DescribeInstancesResult describeInstancesResult = ec2client.describeInstances(new DescribeInstancesRequest().withInstanceIds(instanceIds));

        for (Reservation reservation : describeInstancesResult.getReservations()) {
            for (Instance instance : reservation.getInstances()) {
                if (!hasTag(instance, GATLING_NAME_TAG)) {
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

    private void sleep(long timeMs) {
        try {
            Thread.sleep(timeMs);
        } catch (InterruptedException e) {
        }
    }
}
