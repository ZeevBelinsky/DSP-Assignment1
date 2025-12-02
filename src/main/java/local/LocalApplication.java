package local;

import common.Config;

import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LocalApplication {

    private static final String S3_BUCKET_NAME = Config.S3_BUCKET_NAME;
    // Replace with the actual HTTPS URL where your Manager JAR is hosted
    // (S3/GitHub)
    private static final String MANAGER_JAR_URL = Config.MANAGER_JAR_URL;

    public static void main(String[] args) {

        if (args.length < 3) {
            System.err.println("Usage: java -jar yourjar.jar inputFileName outputFileName n [terminate]");
            return;
        }

        String inputFileName = args[0];
        String outputFileName = args[1];
        int n = Integer.parseInt(args[2]);
        String nString = String.valueOf(n);
        boolean terminate = args.length > 3 && "terminate".equalsIgnoreCase(args[3]);

        SQSHandler sqsHandler = new SQSHandler();

        // Manager -> Worker queue with longer visibility timeout
        Map<QueueAttributeName, String> workerQueueAttributes = Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "7200");
        String mwQueueUrl = sqsHandler.createQueue("Manager_Worker_Queue", workerQueueAttributes);

        // Local -> Manager, Worker -> Manager, Manager -> App (new)
        Map<QueueAttributeName, String> defaultAttributes = Map.of();
        String lmQueueUrl = sqsHandler.createQueue("Local_Manager_Queue", defaultAttributes);
        String wmQueueUrl = sqsHandler.createQueue("Worker_Manager_Queue", defaultAttributes);
        String maQueueUrl = sqsHandler.createQueue("Manager_App_Queue", defaultAttributes);

        if (mwQueueUrl == null || lmQueueUrl == null || wmQueueUrl == null || maQueueUrl == null) {
            System.err.println("Failed to initialize all SQS queues. Aborting.");
            return;
        }

        // Build Manager args (NOW 7 args): LMQ MWQ WMQ MAQ bucket n terminate
        String managerArguments = String.join(" ",
                lmQueueUrl,
                mwQueueUrl,
                wmQueueUrl,
                maQueueUrl,
                S3_BUCKET_NAME,
                nString,
                String.valueOf(terminate));

        // Upload input file to S3
        S3Handler s3Handler = new S3Handler(S3_BUCKET_NAME);
        String s3Key = s3Handler.uploadFile(inputFileName, "input-tasks");
        if (s3Key == null) {
            System.err.println("Failed to upload input file to S3. Aborting.");
            return;
        }

        // Build the "new job" JSON for Local -> Manager
        String jobId = "job-" + System.currentTimeMillis();
        String s3InputUrl = "s3://" + S3_BUCKET_NAME + "/" + s3Key;
        String newTaskJson = String.format(
                "{\"jobId\":\"%s\",\"inputS3\":\"%s\",\"outputFile\":\"%s\",\"terminate\":%s}",
                jobId, s3InputUrl, outputFileName, terminate);

        // Send the task to LMQ
        boolean sent = sqsHandler.sendMessage(lmQueueUrl, newTaskJson);
        if (!sent) {
            System.err.println("Failed to send task message to Manager. Aborting.");
            return;
        }
        System.out.println("Task submitted. Waiting for summary...");

        // Ensure a Manager EC2 is running; if not, start one with user-data
        EC2Manager ec2Manager = new EC2Manager();
        Optional<Instance> runningManager = ec2Manager.getRunningManagerInstance();
        if (runningManager.isEmpty()) {
            System.out.println("No manager found. Starting a new instance...");
            // IMPORTANT: if your user-data uses 'java -jar', pass ONLY the args (no class
            // name).
            String managerInstanceId = ec2Manager.startManagerInstance(MANAGER_JAR_URL, managerArguments);
            if (managerInstanceId == null) {
                System.err.println("Failed to start Manager instance. Aborting.");
                return;
            }
        }

        // === Wait for Manager -> App "done" message, then download summary ===
        while (true) {
            List<Message> msgs = sqsHandler.receive(maQueueUrl, 10, 20, 10);
            if (msgs.isEmpty())
                continue;

            Message m = msgs.get(0);
            String body = m.body(); // Expect: {"jobId":"...","summaryHtmlS3":"s3://.../summary.html"}
            String idMarker = "\"jobId\":\"";
            int idStart = body.indexOf(idMarker);
            if (idStart < 0) {
                System.err.println("Bad message format (no jobId): " + body);
                sqsHandler.delete(maQueueUrl, m.receiptHandle());
                continue;
            }

            int idEnd = body.indexOf('"', idStart + idMarker.length());
            String msgJobId = body.substring(idStart + idMarker.length(), idEnd);

            // is this message for OUR job?
            if (!msgJobId.equals(jobId)) {
                System.out.println("Ignoring message for different Job: " + msgJobId + " (I am: " + jobId + ")");
                continue;
            }

            String marker = "\"summaryHtmlS3\":\"";
            int i = body.indexOf(marker);
            if (i < 0) {
                System.err.println("Unexpected MAQ message: " + body);
                sqsHandler.delete(maQueueUrl, m.receiptHandle());
                continue;
            }
            int j = body.indexOf('"', i + marker.length());
            String summaryS3 = body.substring(i + marker.length(), j);

            // Download summary HTML to the user-specified output file
            s3Handler.downloadS3UrlToFile(summaryS3, outputFileName);
            sqsHandler.delete(maQueueUrl, m.receiptHandle());
            System.out.println("Saved summary to " + outputFileName);

            // If terminate flag was set, send terminate signal to Manager
            if (terminate) {
                System.out.println("Sending termination signal to Manager...");
                String termMsg = "{\"action\":\"terminate\", \"terminate\":true}";
                sqsHandler.sendMessage(lmQueueUrl, termMsg);
            }

            break;
        }
    }
}
