package local;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

public class EC2Manager {

    private final Ec2Client ec2;

    // Defaults for the AWS Academy lab; override with env vars if needed
    private final Region region = Region.of(
            System.getenv().getOrDefault("EC2_REGION", "us-east-1"));

    private static final String MANAGER_TAG_KEY = "Role";
    private static final String MANAGER_TAG_VALUE = "Manager";

    // Fallbacks (env vars preferred)
    private final String amiId =
            System.getenv().getOrDefault("AMI_ID", "ami-0fa3fe0fa7920f68e");
    private final String keyName =
            System.getenv().getOrDefault("KEY_NAME", "vockey");
    private final String instanceProfileName =
            System.getenv().getOrDefault("INSTANCE_PROFILE", "LabInstanceProfile");

    public EC2Manager() {
        this.ec2 = Ec2Client.builder().region(region).build();
    }

    public Optional<Instance> getRunningManagerInstance() {
        try {
            // Include PENDING to avoid launching duplicates while one is booting
            Filter stateFilter = Filter.builder()
                    .name("instance-state-name")
                    .values(InstanceStateName.PENDING.toString(),
                            InstanceStateName.RUNNING.toString())
                    .build();

            Filter tagFilter = Filter.builder()
                    .name("tag:" + MANAGER_TAG_KEY)
                    .values(MANAGER_TAG_VALUE)
                    .build();

            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(stateFilter, tagFilter)
                    .build();

            return ec2.describeInstances(request)
                    .reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .findFirst();

        } catch (Exception e) {
            System.err.println("Error describing EC2 instances: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Launch the Manager EC2.
     * @param jarUrl e.g. s3://wolfs-amaziah-bucket/manager.jar  (or a presigned https URL)
     * @param managerArguments a single string with 7 args, space-separated:
     *        LMQ MWQ WMQ MAQ bucket nWorkers terminateWhenDone
     */
    public String startManagerInstance(String jarUrl, String managerArguments) {

        // Quote the entire args string so spaces survive in bash
        String quotedArgs = managerArguments.replace("\"", "\\\"");
        String userDataScript = String.join("\n",
                "#!/bin/bash",
                "set -euxo pipefail",
                "exec > /var/log/user-data.log 2>&1",
                "",
                "yum update -y",
                "amazon-linux-extras enable corretto17",
                "yum install -y java-17-amazon-corretto-headless awscli jq curl",
                "",
                // Set region on-instance (helps awscli if account has no default)
                "REGION=$(curl -s http://169.254.169.254/latest/dynamic/instance-identity/document | jq -r .region || echo " + region.id() + ")",
                "aws configure set default.region \"$REGION\"",
                "",
                // Fetch the Manager jar (supports s3:// or https presigned URL)
                (jarUrl.startsWith("s3://")
                        ? "aws s3 cp \"" + jarUrl + "\" /home/ec2-user/app.jar"
                        : "curl -L -o /home/ec2-user/app.jar \"" + jarUrl + "\""),
                "chown ec2-user:ec2-user /home/ec2-user/app.jar",
                "",
                // Run Manager
                "ARGS=\"" + quotedArgs + "\"",
                "nohup java -cp /home/ec2-user/app.jar manager.ManagerApplication $ARGS > /var/log/manager.log 2>&1 &",
                ""
        );

        String userDataBase64 = Base64.getEncoder().encodeToString(
                userDataScript.getBytes(StandardCharsets.UTF_8));

        try {
            RunInstancesRequest runRequest = RunInstancesRequest.builder()
                    .instanceType(InstanceType.T2_MICRO)
                    .imageId(amiId)
                    .maxCount(1).minCount(1)
                    .userData(userDataBase64)
                    .keyName(keyName)
                    .iamInstanceProfile(
                            IamInstanceProfileSpecification.builder()
                                    .name(instanceProfileName)
                                    .build())
                    .tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE)
                            .tags(Tag.builder().key(MANAGER_TAG_KEY).value(MANAGER_TAG_VALUE).build())
                            .build())
                    .build();

            RunInstancesResponse response = ec2.runInstances(runRequest);
            String instanceId = response.instances().get(0).instanceId();
            System.out.println("Manager instance started with ID: " + instanceId);
            return instanceId;

        } catch (Exception e) {
            System.err.println("Error starting Manager instance: " + e.getMessage());
            return null;
        }
    }
}
