package local;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import java.util.Base64;
import java.util.Optional;

public class EC2Manager {

    private final Ec2Client ec2;
    private final Region region = Region.US_EAST_1;

    private static final String MANAGER_TAG_KEY = "Role";
    private static final String MANAGER_TAG_VALUE = "Manager";

    // NOTE: Replace this with your valid AMI ID
    private static final String AMI_ID = "ami-076515f20540e6e0b";

    // NOTE: Replace this with your key pair name (for SSH)
    private static final String KEY_PAIR_NAME = "wolf-key";

    public EC2Manager() {
        this.ec2 = Ec2Client.builder().region(region).build();
    }

    public Optional<Instance> getRunningManagerInstance() {
        // Checks if a Manager instance (Role: Manager) is currently running
        try {
            Filter runningFilter = Filter.builder()
                    .name("instance-state-name")
                    .values(InstanceStateName.RUNNING.toString())
                    .build();

            Filter tagFilter = Filter.builder()
                    .name("tag:" + MANAGER_TAG_KEY)
                    .values(MANAGER_TAG_VALUE)
                    .build();

            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(runningFilter, tagFilter)
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

    public String startManagerInstance(String jarUrl, String managerArguments) {

        // --- 1. Build the User Data script (Bootstrapping) ---
        // Downloads the JAR and executes the Manager class
        String userDataScript = "#!/bin/bash\n"
                + "wget " + jarUrl + " -O /home/ec2-user/manager.jar\n"
                + "java -jar /home/ec2-user/manager.jar " + managerArguments + " &\n";

        // User data MUST be Base64 encoded.
        String userDataBase64 = Base64.getEncoder().encodeToString(userDataScript.getBytes());

        try {
            // --- 2. Build the RunInstances request ---
            RunInstancesRequest runRequest = RunInstancesRequest.builder()
                    .instanceType(InstanceType.T2_MICRO)
                    .imageId(AMI_ID)
                    .maxCount(1)
                    .minCount(1)
                    .userData(userDataBase64)
                    .keyName(KEY_PAIR_NAME)
                    .build();

            // Launch the instance
            RunInstancesResponse response = ec2.runInstances(runRequest);
            String instanceId = response.instances().get(0).instanceId();

            // --- 3. Tag the instance as 'Manager' ---
            Tag managerTag = Tag.builder()
                    .key(MANAGER_TAG_KEY)
                    .value(MANAGER_TAG_VALUE)
                    .build();

            CreateTagsRequest tagRequest = CreateTagsRequest.builder()
                    .resources(instanceId)
                    .tags(managerTag)
                    .build();

            ec2.createTags(tagRequest);

            System.out.println("Manager instance started with ID: " + instanceId);
            return instanceId;

        } catch (Exception e) {
            System.err.println("Error starting Manager instance: " + e.getMessage());
            return null;
        }
    }
}