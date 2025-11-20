package manager;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.ArrayList;
import java.util.HashMap;

public class ManagerApplication {

    // ===== CLI args =====
    private static String LM_QUEUE_URL; // Local -> Manager
    private static String MW_QUEUE_URL; // Manager -> Worker
    private static String WM_QUEUE_URL; // Worker -> Manager
    private static String MA_QUEUE_URL; // Manager -> App  (NEW)
    private static String S3_BUCKET_NAME;
    private static int N_WORKERS_RATIO;
    private static boolean TERMINATE_MODE;

    // ===== state =====
    private final SqsClient sqs;
    private final ConcurrentHashMap<String, ManagerJob> activeJobs;
    private final ConcurrentHashMap<String, List<TaskResult>> jobResults; // per-job aggregation
    private final ExecutorService taskExecutor;

    public ManagerApplication() {
        this.sqs = SqsClient.builder().region(Region.US_EAST_1).build();
        this.activeJobs = new ConcurrentHashMap<>();
        this.jobResults = new ConcurrentHashMap<>();
        this.taskExecutor = Executors.newFixedThreadPool(10);
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
            TERMINATE_MODE = Boolean.parseBoolean(args[6]);
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
            // 1) New jobs from Local -> Manager (use long polling)
            List<Message> newTasks = receiveMessages(LM_QUEUE_URL, 1, 20, 30);
            for (Message message : newTasks) {
                taskExecutor.submit(() -> handleNewTask(message));
            }

            // 2) Results from Worker -> Manager
            List<Message> results = receiveMessages(WM_QUEUE_URL, 10, 20, 30);
            for (Message result : results) {
                try {
                    handleWorkerResult(result);
                } finally {
                    // always delete WMQ message after accounting it
                    deleteMessage(WM_QUEUE_URL, result.receiptHandle());
                }
            }

            // 3) Terminate condition
            if (TERMINATE_MODE && allJobsCompleted()) {
                System.out.println("All jobs complete and Terminate mode is set. Shutting down...");
                terminateSystem();
                break;
            }

            // short sleep to reduce SQS churn
            try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        taskExecutor.shutdown();
    }

    // ==== CORE HANDLERS ====

    // Local -> Manager new job
    private void handleNewTask(Message message) {
        String body = message.body(); // {"jobId","inputS3","outputFile","terminate"}
        String jobId = extract(body, "jobId");
        String inputS3 = extract(body, "inputS3");
        String outputFile = extract(body, "outputFile");

        if (jobId.isBlank() || inputS3.isBlank()) {
            System.err.println("Bad job message: " + body);
            return;
        }

        // 1) Read input file (ANALYSIS \t URL per line) and fan-out tasks to MWQ
        List<String> lines = InputDownloader.readAllLinesFromS3(inputS3);
        int total = 0;
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue; // skip malformed
            String analysis = line.substring(0, tab).trim();
            String url = line.substring(tab + 1).trim();
            if (analysis.isEmpty() || url.isEmpty()) continue;

            String taskJson = String.format(
                    "{\"jobId\":\"%s\",\"url\":\"%s\",\"analysis\":\"%s\"}",
                    escapeJson(jobId), escapeJson(url), escapeJson(analysis)
            );
            send(MW_QUEUE_URL, taskJson);
            total++;
        }

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

        String url  = extract(body, "url");
        String anal = extract(body, "analysis");
        boolean ok  = body.contains("\"ok\":true");
        String resultS3 = ok ? extract(body, "resultS3") : null;
        String error    = ok ? null : extract(body, "error");

        jobResults.get(jobId).add(new TaskResult(url, anal, resultS3, ok, error));
        if (ok) job.incrementCompleted(); else job.incrementFailed();

        if (job.isCompleted()) {
            // Build and upload summary HTML
            String summaryS3 = SummaryBuilder.buildAndUpload(S3_BUCKET_NAME, jobId, jobResults.get(jobId));

            // Notify Local via Manager -> App queue
            String doneJson = String.format(
                    "{\"jobId\":\"%s\",\"summaryHtmlS3\":\"%s\"}",
                    escapeJson(jobId), escapeJson(summaryS3)
            );
            send(MA_QUEUE_URL, doneJson);

            // Delete the original Local message (acknowledge job)
            deleteMessage(LM_QUEUE_URL, job.getLocalAppReceiptHandle());

            // Cleanup
            activeJobs.remove(jobId);
            jobResults.remove(jobId);
        }
    }

    // ==== QUEUE HELPERS ====

    private List<Message> receiveMessages(String queueUrl, int maxMessages, int waitSeconds, int visibilityTimeout) {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitSeconds)      // long polling
                .visibilityTimeout(visibilityTimeout)
                .build();
        return sqs.receiveMessage(receiveRequest).messages();
    }

    private void deleteMessage(String queueUrl, String receiptHandle) {
        if (receiptHandle == null || receiptHandle.isEmpty()) return;
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
        if (activeJobs.isEmpty()) return true;
        for (ManagerJob job : activeJobs.values()) {
            if (!job.isCompleted()) return false;
        }
        return true;
    }

    private void terminateSystem() {
        // TODO (optional nice-to-have):
        // - broadcast a shutdown signal on MWQ (if your Worker respects it)
        // - terminate EC2 workers tagged Role=Worker
        // - then exit (loop already breaks)
    }

    private void scaleWorkersIfNeeded() {
        // Very simple heuristic: approximate backlog -> desired workers = ceil(backlog / N_WORKERS_RATIO)
        try {
            Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(MW_QUEUE_URL)
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                    .build()).attributes();

            int backlog = 0;
            if (attrs.containsKey(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)) {
                backlog = Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
            }
            int desired = Math.max(1, (int)Math.ceil(backlog / (double)Math.max(1, N_WORKERS_RATIO)));
            ensureWorkers(desired); // implement with EC2; cap at 19 total
        } catch (Exception ignored) {}
    }

    private void ensureWorkers(int desired) {
        // TODO: use EC2 API to count Role=Worker instances; if < desired and total < 19 -> launch more
        //       attach IAM role, pass MWQ/WMQ/Bucket in user-data, tag Role=Worker
    }

    // ==== SIMPLE JSON UTILS ====

    private static String extract(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int i = json.indexOf(marker);
        if (i < 0) return "";
        int j = json.indexOf('"', i + marker.length());
        if (j < 0) return "";
        return json.substring(i + marker.length(), j);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==== RESULT DTO ====

    static class TaskResult {
        final String url;
        final String analysis;
        final String resultS3; // null if failed
        final boolean ok;
        final String error;    // null if ok

        TaskResult(String url, String analysis, String resultS3, boolean ok, String error) {
            this.url = url;
            this.analysis = analysis;
            this.resultS3 = resultS3;
            this.ok = ok;
            this.error = error;
        }
    }
}
