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
    private static final long S3_UPLOAD_TIMEOUT_MS = 60 * 1000;

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
        this.ec2client = new AmazonEC2Client(credentials);
        this.ec2client.setEndpoint(endpoint);
        this.transferManager = new TransferManager(credentials);
    }

    public Map<String, Instance> launchEC2Instances(final String instanceType, final int instanceCount, final String ec2KeyPairName, final String ec2SecurityGroup, final String amiId, final boolean createIfNonExistent) {
        return this.launchEC2Instances(instanceType,
                () -> new RunInstancesRequest()
                        .withImageId(amiId)
                        .withInstanceType(instanceType)
                        .withMinCount(instanceCount)
                        .withMaxCount(instanceCount)
                        .withKeyName(ec2KeyPairName)
                        .withSecurityGroups(ec2SecurityGroup),
                createIfNonExistent
        );
    }

    public Map<String, Instance> launchEC2Instances(final String instanceType, final int instanceCount, final String ec2KeyPairName, final String ec2SecurityGroupId, final String ec2SubnetId, final String amiId, final boolean createIfNonExistent) {
        return this.launchEC2Instances(instanceType,
                () -> new RunInstancesRequest()
                        .withImageId(amiId)
                        .withInstanceType(instanceType)
                        .withMinCount(instanceCount)
                        .withMaxCount(instanceCount)
                        .withKeyName(ec2KeyPairName)
                        .withSecurityGroupIds(ec2SecurityGroupId)
                        .withSubnetId(ec2SubnetId),
                createIfNonExistent
        );
    }

    public Map<String, Instance> findExistingInstances(final String instanceType) {
        final Map<String, Instance> instances = new HashMap<>();

        final DescribeInstancesResult describeInstancesResult = this.ec2client.describeInstances(new DescribeInstancesRequest()
                .withFilters(this.getInstanceFilters(instanceType)));

        // Check for existing EC2 instances that fit the filter criteria and use those.
        for (final Reservation reservation : describeInstancesResult.getReservations()) {
            for (final Instance instance : reservation.getInstances()) {
                // If we found any existing EC2 instances put them into the instances variable.
                System.out.format("Reservations %s (%s): %s%n", instance.getInstanceId(), instance.getState().getName(), instance.getSecurityGroups().get(0).getGroupName());
                instances.put(instance.getInstanceId(), instance);
            }
        }

        return instances;
    }

    private Map<String, Instance> launchEC2Instances(final String instanceType, final RunInstancesRequestBuilder runInstancesRequestBuilder, final boolean createIfNonExistent) {
        final Map<String, Instance> instances = this.findExistingInstances(instanceType);

        // If instances is empty, that means we did not find any to reuse so let's create them
        if (instances.isEmpty() && createIfNonExistent) {
            final RunInstancesRequest request = runInstancesRequestBuilder.build();
            System.out.println(String.format(
                    "Did not find any existing instances, starting new ones with security group: '%s' and subnet: '%s'",
                    request.getSecurityGroups().get(0), request.getSubnetId() == null ? "" : request.getSubnetId()));
            final RunInstancesResult runInstancesResult = this.ec2client.runInstances(request);

            for (final Instance instance : runInstancesResult.getReservation().getInstances()) {
                System.out.println(instance.getInstanceId() + " launched");
                instances.put(instance.getInstanceId(), instance);
            }

            // Tag instances on creation. Adding the tag enables us to ensure we are terminating a load generator instance.
            this.ec2client.createTags(new CreateTagsRequest()
                    .withResources(instances.keySet())
                    .withTags(this.instanceTag));

            final DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest().withInstanceIds(instances.keySet());

            this.startAllInstances(instances, describeInstancesRequest);
        }

        return instances;
    }

    private void startAllInstances(final Map<String, Instance> instances, final DescribeInstancesRequest describeInstancesRequest) {
        boolean allStarted = false;
        DescribeInstancesResult describeInstancesResult;

        while (!allStarted) {
            sleep(INSTANCE_STATUS_SLEEP_MS);
            allStarted = true;
            describeInstancesResult = this.ec2client.describeInstances(describeInstancesRequest);
            for (final Reservation reservation : describeInstancesResult.getReservations()) {
                for (final Instance instance : reservation.getInstances()) {
                    System.out.format("%s %s%n", instance.getInstanceId(), instance.getState().getName());
                    if (!instance.getState().getName().equals("running")) {
                        allStarted = false;
                    } else {
                        instances.put(instance.getInstanceId(), instance);
                    }
                }
            }

            final DescribeInstanceStatusRequest describeInstanceStatusRequest = new DescribeInstanceStatusRequest().withInstanceIds(instances.keySet());
            boolean allInitialized = false;
            while (!allInitialized) {
                sleep(INSTANCE_STATUS_SLEEP_MS);
                final DescribeInstanceStatusResult describeInstanceStatus = this.ec2client.describeInstanceStatus(describeInstanceStatusRequest);
                allInitialized = true;
                for (final InstanceStatus instanceStatus : describeInstanceStatus.getInstanceStatuses()) {
                    System.out.format("%s %s%n", instanceStatus.getInstanceId(), instanceStatus.getInstanceStatus().getStatus());
                    if (!instanceStatus.getInstanceStatus().getStatus().equals("ok")) {
                        allInitialized = false;
                    }
                }
            }
        }
    }

    private Filter[] getInstanceFilters(final String instanceType) {
        // Setup a filter to find any previously generated EC2 instances.
        return new Filter[]{
            new Filter("tag:" + this.instanceTag.getKey()).withValues(this.instanceTag.getValue()),
            new Filter("instance-state-name").withValues("running"),
            new Filter("instance-type").withValues(instanceType)
        };
    }

    public void terminateInstances(final Collection<String> instanceIds) {
        final DescribeInstancesResult describeInstancesResult = this.ec2client.describeInstances(new DescribeInstancesRequest().withInstanceIds(instanceIds));

        for (final Reservation reservation : describeInstancesResult.getReservations()) {
            for (final Instance instance : reservation.getInstances()) {
                if (!this.hasTag(instance, this.instanceTag)) {
                    System.out.format("Aborting since instance %s does not look like a gatling load generator.%n", instance.getInstanceId());
                    return;
                }
                System.out.format("Instance %s looks like a gatling load generator.%n", instance.getInstanceId());
            }
        }

        System.out.println("Terminating " + instanceIds);
        this.ec2client.terminateInstances(new TerminateInstancesRequest(new ArrayList<String>(instanceIds)));
    }

    public void uploadToS3(final String s3bucket, final String targetDirectory, final File sourceDirectory) {
        // Recursively upload sourceDirectory to targetDirectory.
        FOR: for (final File file : sourceDirectory.listFiles()) {
            if (file.isDirectory()) {
                this.uploadToS3(s3bucket, targetDirectory + "/" + file.getName(), file);
            } else if (file.isFile()) {
                final String path = file.getAbsolutePath();
                final long uploadStartTimeMs = System.currentTimeMillis();
                final PutObjectRequest putRequest = new PutObjectRequest(s3bucket, targetDirectory + "/" + file.getName(), file)
                        .withCannedAcl(CannedAccessControlList.PublicRead);

                final Upload upload = this.transferManager.upload(putRequest);
                int statusChecks = 0;

                System.out.println("Uploading " + path);

                while (!upload.isDone()) {
                    if (this.uploadTimedOut(uploadStartTimeMs)) {
                        System.err.format("Timed out uploading file to S3 (%s). Will skip file. Report might be incomplete.%n", path);
                        continue FOR;
                    }

                    sleep(100);
                    if (++statusChecks % 100 == 0) {
                        System.out.format("Still uploading %s%n", file.getAbsolutePath());
                    }
                }
                try {
                    upload.waitForCompletion();
                } catch (final Exception e) {
                    System.out.format("Failed to upload to S3 %s/%s/%s%n", s3bucket, targetDirectory, file.getName());
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * @return True if this S3 file upload has timed out. False otherwise.
     */
    private boolean uploadTimedOut(final long uploadStartTimeMs) {
        final long totalUploadTimeMs = System.currentTimeMillis() - uploadStartTimeMs;
        return totalUploadTimeMs > S3_UPLOAD_TIMEOUT_MS;
    }

    private boolean hasTag(final Instance instance, final Tag theTag) {
        for (final Tag tag : instance.getTags()) {
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

    private void sleep(final long timeMs) {
        try {
            Thread.sleep(timeMs);
        } catch (final InterruptedException e) {
        }
    }

    private interface RunInstancesRequestBuilder {
        RunInstancesRequest build();
    }
}
