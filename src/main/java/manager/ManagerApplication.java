package manager;

import common.Config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class ManagerApplication {

    // ===== CLI args =====
    private static String LM_QUEUE_URL; // Local -> Manager
    private static String MW_QUEUE_URL; // Manager -> Worker
    private static String WM_QUEUE_URL; // Worker -> Manager
    private static String MA_QUEUE_URL; // Manager -> App
    private static String S3_BUCKET_NAME;
    private static int N_WORKERS_RATIO;
    private static boolean TERMINATE_MODE;

    // ===== state =====
    private final SqsClient sqs;
    private final Ec2Client ec2;
    private final ConcurrentHashMap<String, ManagerJob> activeJobs;
    private final ConcurrentHashMap<String, List<TaskResult>> jobResults; // per-job aggregation
    private final ExecutorService taskExecutor;

    // Worker JAR URL needs to be defined
    private static final String WORKER_JAR_URL = Config.WORKER_JAR_URL;
    private static final String WORKER_CLASS_NAME = "worker.WorkerApplication";
    private static final String INSTANCE_PROFILE_NAME = "LabInstanceProfile";
    private volatile boolean shouldTerminate = false;

    // ==== tagging Workers ====
    private static final String WORKER_TAG_KEY = "Role";
    private static final String WORKER_TAG_VALUE = "Worker";
    private static final int MAX_TOTAL_INSTANCES = 19;

    private static final String AMI_ID = "ami-0fa3fe0fa7920f68e";
    private static final String KEY_PAIR_NAME = "assignment1-key";

    public ManagerApplication() {
        this.sqs = SqsClient.builder().region(Region.US_EAST_1).build();
        this.activeJobs = new ConcurrentHashMap<>();
        this.jobResults = new ConcurrentHashMap<>();
        this.taskExecutor = Executors.newFixedThreadPool(10);
        this.ec2 = Ec2Client.builder().region(Region.US_EAST_1).build();
    }

    public static void main(String[] args) {
        // Expect 7 args: LMQ MWQ WMQ MAQ bucket n terminate
        if (args.length < 7) {
            System.err.println("Manager requires 7 args: LMQ MWQ WMQ MAQ BUCKET N TERMINATE");
            return;
        }

        try {
            LM_QUEUE_URL = args[0];
            MW_QUEUE_URL = args[1];
            WM_QUEUE_URL = args[2];
            MA_QUEUE_URL = args[3];
            S3_BUCKET_NAME = args[4];
            N_WORKERS_RATIO = Integer.parseInt(args[5]);

            // Accept "true" or "terminate" as enabling terminate mode
            String termArg = args[6];
            TERMINATE_MODE = "true".equalsIgnoreCase(termArg) || "terminate".equalsIgnoreCase(termArg);
        } catch (Exception e) {
            System.err.println("Error parsing manager arguments. Aborting. " + e.getMessage());
            return;
        }

        ManagerApplication manager = new ManagerApplication();
        manager.startManagerLoop();
    }

public void startManagerLoop() {
    System.out.println("Manager started. Listening to queues...");

    while (true) {
        // 1) New jobs from Local -> Manager (use long polling) - ONLY if not terminating
        if (!shouldTerminate) {
            List<Message> newTasks = receiveMessages(LM_QUEUE_URL, 10, 20, 3600);
            for (Message message : newTasks) {
                try {
                    // process synchronously to avoid races with termination
                    handleNewTask(message);
                } catch (Exception e) {
                    System.err.println("[Manager] handleNewTask failed: " + e.getMessage());
                    e.printStackTrace();
                    // Note: do NOT delete the message here; it will reappear after visibility timeout.
                }
            }
        }

        // 2) Results from Worker -> Manager
        List<Message> results = receiveMessages(WM_QUEUE_URL, 10, 20, 3600);
        for (Message result : results) {
            try {
                handleWorkerResult(result);
            } finally {
                // always delete WMQ message after accounting it
                deleteMessage(WM_QUEUE_URL, result.receiptHandle());
            }
        }

        // 3) Terminate condition
        if (shouldTerminate && TERMINATE_MODE && allJobsCompleted()) {
            System.out.println("All jobs complete and Terminate mode is set. Shutting down...");
            terminateSystem();
            break;
        }

        // short sleep to reduce SQS churn
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    taskExecutor.shutdown();
}
    // ==== CORE HANDLERS ====

    // Local -> Manager new job
// Local -> Manager new job
private void handleNewTask(Message message) {
    String body = message.body(); // expected: {"jobId","inputS3","outputFile", ...}
    System.out.println("[Manager] LMQ body: " + body);

    boolean isTerminateMessage =
            body.contains("\"terminate\":true") && body.contains("\"action\":\"terminate\"");

    // If we are already terminating, ignore any *new* non-termination jobs
    if (shouldTerminate && !isTerminateMessage) {
        System.out.println("[Manager] Ignoring new job while in termination mode.");
        // Don't delete the message so another manager (if any) could potentially handle it
        return;
    }

    // Check for terminate signal
    if (isTerminateMessage) {
        System.out.println("[Manager] Received TERMINATION signal from Local App.");
        shouldTerminate = true;
        deleteMessage(LM_QUEUE_URL, message.receiptHandle());
        return;
    }

    String jobId = extract(body, "jobId");
    String inputS3 = extract(body, "inputS3");
    String outputFile = extract(body, "outputFile");

    if (activeJobs.containsKey(jobId)) {
        System.out.println("Job " + jobId + " is already active. Skipping duplicate.");
        deleteMessage(LM_QUEUE_URL, message.receiptHandle());
        return;
    }

    // Fallback: build from bucket+key if inputS3 missing
    if (inputS3 == null || inputS3.isBlank()) {
        String bucket = extract(body, "bucket");
        String key = extract(body, "key");
        if (!bucket.isBlank() && !key.isBlank()) {
            inputS3 = "s3://" + bucket + "/" + key;
            System.out.println("[Manager] Built inputS3 from bucket/key: " + inputS3);
        }
    }

    if (jobId.isBlank() || inputS3.isBlank()) {
        System.err.println("[Manager] Bad job message (missing jobId/inputS3). Body: " + body);
        // Optional: deleteMessage(LM_QUEUE_URL, message.receiptHandle());
        return;
    }

    // 1) Read input file (ANALYSIS \t URL per line) and fan-out tasks to MWQ
    List<String> lines = InputDownloader.readAllLinesFromS3(inputS3);
    int total = 0;
    for (String line : lines) {
        int tab = line.indexOf('\t');
        if (tab < 0)
            continue; // skip malformed
        String analysis = line.substring(0, tab).trim();
        String url = line.substring(tab + 1).trim();
        if (analysis.isEmpty() || url.isEmpty())
            continue;

        String taskJson = String.format(
                "{\"jobId\":\"%s\",\"url\":\"%s\",\"analysis\":\"%s\"}",
                escapeJson(jobId), escapeJson(url), escapeJson(analysis));
        send(MW_QUEUE_URL, taskJson);
        total++;
    }
    System.out.println("[Manager] Fanned out " + total + " tasks to MWQ for job " + jobId);

    // 2) Track job and init result list
    activeJobs.put(jobId, new ManagerJob(jobId, total, message.receiptHandle(), outputFile));
    jobResults.put(jobId, new CopyOnWriteArrayList<>());

    // 3) Scale workers by N (cap handled in ensureWorkers)
    scaleWorkersIfNeeded();
}
    // Worker -> Manager result
    private void handleWorkerResult(Message msg) {
        String body = msg.body(); // {"jobId","url","analysis","resultS3","ok":true/false,"error":...}
        String jobId = extract(body, "jobId");
        ManagerJob job = activeJobs.get(jobId);
        if (job == null) {
            // Could be a late result after job closed; ignore safely
            return;
        }

        String url = extract(body, "url");
        String anal = extract(body, "analysis");
        boolean ok = body.contains("\"ok\":true");
        String resultS3 = ok ? extract(body, "resultS3") : null;
        String error = ok ? null : extract(body, "error");

        jobResults.get(jobId).add(new TaskResult(url, anal, resultS3, ok, error));
        if (ok)
            job.incrementCompleted();
        else
            job.incrementFailed();

        if (job.isCompleted()) {
            // Build and upload summary HTML
            String summaryS3 = SummaryBuilder.buildAndUpload(S3_BUCKET_NAME, jobId, jobResults.get(jobId));

            // Notify Local via Manager -> App queue
            String doneJson = String.format(
                    "{\"jobId\":\"%s\",\"summaryHtmlS3\":\"%s\"}",
                    escapeJson(jobId), escapeJson(summaryS3));
            send(MA_QUEUE_URL, doneJson);

            // Delete the original Local message (acknowledge job)
            deleteMessage(LM_QUEUE_URL, job.getLocalAppReceiptHandle());

            // Cleanup
            activeJobs.remove(jobId);
            jobResults.remove(jobId);
            System.out.println("[Manager] Job " + jobId + " completed. Summary at " + summaryS3);
        }
    }

    // ==== QUEUE HELPERS ====

    private List<Message> receiveMessages(String queueUrl, int maxMessages, int waitSeconds, int visibilityTimeout) {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitSeconds) // long polling
                .visibilityTimeout(visibilityTimeout)
                .build();
        return sqs.receiveMessage(receiveRequest).messages();
    }

    private void deleteMessage(String queueUrl, String receiptHandle) {
        if (receiptHandle == null || receiptHandle.isEmpty())
            return;
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }

    private void send(String queueUrl, String body) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build());
    }

    // ==== JOB/SHUTDOWN ====

    private boolean allJobsCompleted() {
        if (activeJobs.isEmpty())
            return true;
        for (ManagerJob job : activeJobs.values()) {
            if (!job.isCompleted())
                return false;
        }
        return true;
    }

    private void terminateSystem() {
        System.out.println("Terminating system via EC2 API...");

        try {
            List<String> workerIds = getActiveInstanceIds(WORKER_TAG_VALUE);
            if (!workerIds.isEmpty()) {
                System.out.println("Terminating " + workerIds.size() + " workers...");
                ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(workerIds).build());
            }
        } catch (Exception e) {
            System.err.println("Error terminating workers: " + e.getMessage());
        }

        try {
            String myId = retrieveInstanceId();
            if (myId != null) {
                System.out.println("Terminating self: " + myId);
                ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(myId).build());
            }
        } catch (Exception e) {
            System.err.println("Error terminating manager: " + e.getMessage());
        }
    }

    private void scaleWorkersIfNeeded() {
        // Very simple heuristic: approximate backlog -> desired workers = ceil(backlog
        // / N_WORKERS_RATIO)
        try {
            Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(MW_QUEUE_URL)
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                    .build()).attributes();

            int backlog = 0;
            if (attrs.containsKey(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)) {
                backlog = Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
            }
            int desired = Math.max(1, (int) Math.ceil(backlog / (double) Math.max(1, N_WORKERS_RATIO)));
            ensureWorkers(desired); // implement with EC2; cap at 19 total
        } catch (Exception ignored) {
        }
    }

    private int getActiveInstanceCount(String tagValue) {
        // Count EC2 instances in RUNNING or PENDING state, optionally filtered by tag
        try {
            Filter runningFilter = Filter.builder()
                    .name("instance-state-name")
                    .values(InstanceStateName.RUNNING.toString(), InstanceStateName.PENDING.toString()) // Include //
                                                                                                        // PENDING
                    .build();

            DescribeInstancesRequest request;

            if (tagValue != null) {
                Filter tagFilter = Filter.builder()
                        .name("tag:" + WORKER_TAG_KEY)
                        .values(tagValue)
                        .build();
                request = DescribeInstancesRequest.builder().filters(runningFilter, tagFilter).build();
            } else {
                request = DescribeInstancesRequest.builder().filters(runningFilter).build();
            }

            return (int) ec2.describeInstances(request).reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .count();

        } catch (Exception e) {
            System.err.println("Error counting active instances: " + e.getMessage());
            return 0;
        }
    }

    private List<String> getActiveInstanceIds(String tagValue) {
        List<String> ids = new ArrayList<>();
        try {
            Filter runningFilter = Filter.builder()
                    .name("instance-state-name")
                    .values(InstanceStateName.RUNNING.toString(), InstanceStateName.PENDING.toString())
                    .build();

            Filter tagFilter = Filter.builder()
                    .name("tag:" + WORKER_TAG_KEY)
                    .values(tagValue)
                    .build();

            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(runningFilter, tagFilter)
                    .build();

            ec2.describeInstances(request).reservations().forEach(
                    reservation -> reservation.instances().forEach(instance -> ids.add(instance.instanceId())));
        } catch (Exception e) {
            System.err.println("Error fetching instance IDs: " + e.getMessage());
        }
        return ids;
    }

    private String retrieveInstanceId() {
        try {
            HttpClient client = HttpClient.newHttpClient();

            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://169.254.169.254/latest/api/token"))
                    .header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
                    .method("PUT", HttpRequest.BodyPublishers.noBody())
                    .build();

            String token = client.send(tokenReq, HttpResponse.BodyHandlers.ofString()).body();
            HttpRequest idReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://169.254.169.254/latest/meta-data/instance-id"))
                    .header("X-aws-ec2-metadata-token", token)
                    .GET()
                    .build();

            return client.send(idReq, HttpResponse.BodyHandlers.ofString()).body();

        } catch (Exception e) {
            System.err.println("Failed to retrieve own instance ID via IMDSv2: " + e.getMessage());
            return null;
        }
    }

    private void ensureWorkers(int desiredWorkers) {
        // TODO: use EC2 API to count Role=Worker instances; if < desired and total < 19
        // -> launch more
        // attach IAM role, pass MWQ/WMQ/Bucket in user-data, tag Role=Worker
        int currentWorkers = getActiveInstanceCount(WORKER_TAG_VALUE);
        int totalInstances = getActiveInstanceCount(null);

        int workersToLaunch = desiredWorkers - currentWorkers;

        if (workersToLaunch <= 0) {
            return;
        }

        int availableSlots = MAX_TOTAL_INSTANCES - totalInstances;
        int actualToLaunch = Math.min(workersToLaunch, availableSlots);

        if (actualToLaunch <= 0) {
            System.out.println("[EC2] Cannot launch more workers. Reached max limit of " + MAX_TOTAL_INSTANCES);
            return;
        }

        System.out.println("[EC2] Launching " + actualToLaunch + " new worker instances.");

        String workerArguments = String.join(" ", MW_QUEUE_URL, WM_QUEUE_URL, S3_BUCKET_NAME);

        String userDataScript = String.join("\n",
                "#!/bin/bash",
                "set -euxo pipefail",
                "exec > /var/log/worker-boot.log 2>&1",
                "yum update -y",
                "yum install -y java-17-amazon-corretto-headless awscli jq",
                "aws configure set default.region us-east-1",
                (WORKER_JAR_URL.startsWith("s3://")
                        ? "aws s3 cp \"" + WORKER_JAR_URL + "\" /home/ec2-user/app.jar"
                        : "curl -L -o /home/ec2-user/app.jar \"" + WORKER_JAR_URL + "\""),
                "chown ec2-user:ec2-user /home/ec2-user/app.jar",
                // Run Worker
                "nohup java -Xmx1500m -cp /home/ec2-user/app.jar " + WORKER_CLASS_NAME + " " + workerArguments
                        + " > /var/log/worker.log 2>&1 &");

        String userDataBase64 = Base64.getEncoder().encodeToString(userDataScript.getBytes(StandardCharsets.UTF_8));

        try {
            RunInstancesRequest runRequest = RunInstancesRequest.builder()
                    .instanceType(InstanceType.T3_MEDIUM)
                    .imageId(AMI_ID)
                    .maxCount(actualToLaunch)
                    .minCount(actualToLaunch)
                    .userData(userDataBase64)
                    // .keyName(KEY_PAIR_NAME)
                    .iamInstanceProfile(
                            IamInstanceProfileSpecification.builder().name(INSTANCE_PROFILE_NAME).build()) // מנגנון IAM
                                                                                                           // Role
                    .tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE)
                            .tags(Tag.builder().key(WORKER_TAG_KEY).value(WORKER_TAG_VALUE).build())
                            .build())
                    .build();

            ec2.runInstances(runRequest);

        } catch (Exception e) {
            System.err.println("Error launching Worker instances: " + e.getMessage());
        }
    }

    // ==== SIMPLE JSON UTILS ====

    private static String extract(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int i = json.indexOf(marker);
        if (i < 0)
            return "";
        int j = json.indexOf('"', i + marker.length());
        if (j < 0)
            return "";
        return json.substring(i + marker.length(), j);
    }

    private static String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==== RESULT DTO ====

    static class TaskResult {
        final String url;
        final String analysis;
        final String resultS3; // null if failed
        final boolean ok;
        final String error; // null if ok

        TaskResult(String url, String analysis, String resultS3, boolean ok, String error) {
            this.url = url;
            this.analysis = analysis;
            this.resultS3 = resultS3;
            this.ok = ok;
            this.error = error;
        }
    }
}
