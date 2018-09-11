package com.ea.gatling;

import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Tag;

/**
 * Kill instances with the tag in "ec2.tag.value"
 */
@Mojo(name = "kill")
public class KillGatlingAwsMojo extends BaseAwsMojo {

    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        final AwsGatlingRunner runner = new AwsGatlingRunner(this.ec2EndPoint);
        runner.setInstanceTag(new Tag(this.ec2TagName, this.ec2TagValue));

        final Map<String, Instance> instances = runner.findExistingInstances(this.instanceType);

        runner.terminateInstances(instances.keySet());
    }
}
