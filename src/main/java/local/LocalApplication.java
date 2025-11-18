package local;

import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import java.util.Map;
import java.util.Optional;

public class LocalApplication {

    private static final String S3_BUCKET_NAME = "wolf's-and-amaziah-bucket";
    // NOTE: Replace this with the actual S3 URL where you uploaded your Manager JAR
    private static final String MANAGER_JAR_URL = "https://your-s3-endpoint/manager/yourjar.jar";
    private static final String MANAGER_CLASS_NAME = "manager.ManagerApplication"; // Full path to your Manager's main
                                                                                   // class

    public static void main(String[] args) {

        if (args.length < 3) {
            System.err.println("Usage: java -jar yourjar.jar inputFileName outputFileName n [terminate]");
            return;
        }

        String inputFileName = args[0];
        String outputFileName = args[1];
        int n = Integer.parseInt(args[2]);
        String nString = String.valueOf(n);
        boolean terminate = args.length > 3 && "terminate".equalsIgnoreCase(args[3]); // ארגומנט אופציונלי [cite: 103]

        SQSHandler sqsHandler = new SQSHandler();

        // creating Manager->Worker queue with Visibility Timeout מוגדל (600 שניות)
        Map<QueueAttributeName, String> workerQueueAttributes = Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "600");
        String mwQueueUrl = sqsHandler.createQueue("Manager_Worker_Queue", workerQueueAttributes);

        // creating Local->Manager queue & Worker->Manager queue
        Map<QueueAttributeName, String> defaultAttributes = Map.of();
        String lmQueueUrl = sqsHandler.createQueue("Local_Manager_Queue", defaultAttributes);
        String wmQueueUrl = sqsHandler.createQueue("Worker_Manager_Queue", defaultAttributes);

        // check if all queues were created successfully
        if (mwQueueUrl == null || lmQueueUrl == null || wmQueueUrl == null) {
            System.err.println("Failed to initialize all SQS queues. Aborting.");
            return;
        }

        // Concatenate all SQS URLs and parameters for the Manager's User Data script
        String managerArguments = String.join(" ",
                lmQueueUrl,
                mwQueueUrl,
                wmQueueUrl,
                S3_BUCKET_NAME,
                nString,
                String.valueOf(terminate));

        S3Handler s3Handler = new S3Handler(S3_BUCKET_NAME);
        String s3Key = s3Handler.uploadFile(inputFileName, "input-tasks");

        if (s3Key == null) {
            System.err.println("Failed to upload input file to S3. Aborting.");
            return;
        }

        String s3InputUrl = "s3://" + S3_BUCKET_NAME + "/" + s3Key;

        // Send the task message to the Manager Queue (LMQ)
        boolean sent = sqsHandler.sendMessage(lmQueueUrl, s3InputUrl);

        if (!sent) {
            System.err.println("Failed to send task message to Manager. Aborting.");
            return;
        }

        System.out.println("Task successfully submitted to the Manager");

        // Check and Start the Manager Instance (EC2)
        EC2Manager ec2Manager = new EC2Manager();
        Optional<Instance> runningManager = ec2Manager.getRunningManagerInstance();

        if (!runningManager.isPresent()) {
            System.out.println("No manager found. Starting a new instance...");
            String managerInstanceId = ec2Manager.startManagerInstance(MANAGER_JAR_URL,
                    MANAGER_CLASS_NAME + " " + managerArguments);
            if (managerInstanceId == null) {
                System.err.println("Failed to start Manager instance. Aborting.");
                return;
            }
        }

    }
}