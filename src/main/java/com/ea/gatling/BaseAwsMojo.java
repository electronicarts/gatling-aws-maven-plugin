package com.ea.gatling;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

public abstract class BaseAwsMojo extends AbstractMojo {
    @Parameter(property = "ec2.instance.count", defaultValue = "1")
    protected Integer instanceCount;

    @Parameter(property = "ec2.instance.type", defaultValue = "m3.medium")
    protected String instanceType;

    /**
     * ID of the Amazon Machine Image to be used. Defaults to Amazon Linux.
     */
    @Parameter(property = "ec2.ami.id", defaultValue = "ami-b66ed3de")
    protected String ec2AmiId;

    @Parameter(property = "ec2.key.pair.name", defaultValue = "gatling-key-pair")
    protected String ec2KeyPairName;

    /**
     * Create a security group for the Gatling EC2 instances. Ensure it allows inbound SSH traffic to your IP address range.
     */
    @Parameter(property = "ec2.security.group", defaultValue = "gatling-security-group")
    protected String ec2SecurityGroup;

    @Parameter(property = "ec2.security.group.id")
    protected String ec2SecurityGroupId;

    @Parameter(property = "ec2.subnet.id")
    protected String ec2SubnetId;

    @Parameter(property = "ec2.end.point", defaultValue="https://ec2.us-east-1.amazonaws.com")
    protected String ec2EndPoint;

    @Parameter(property = "ec2.tag.name", defaultValue = "Name")
    protected String ec2TagName;

    @Parameter(property = "ec2.tag.value", defaultValue = "Gatling Load Generator")
    protected String ec2TagValue;

}
