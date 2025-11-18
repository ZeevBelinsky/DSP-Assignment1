package manager;

import java.util.concurrent.atomic.AtomicInteger;

public class ManagerJob {
    private final String jobId;
    private final int totalSubtasks; // Total URLs sent to MWQ
    private final String localAppReceiptHandle; // Handle of the message from LMQ (for cleanup)
    private final String localAppOutputFile; // The expected output filename from LocalApplication args

    // AtomicInteger ensures thread-safe counting for concurrent Worker updates
    private final AtomicInteger completedTasks;
    private final AtomicInteger failedTasks;

    public ManagerJob(String jobId, int totalSubtasks, String localAppReceiptHandle, String localAppOutputFile) {
        this.jobId = jobId;
        this.totalSubtasks = totalSubtasks;
        this.localAppReceiptHandle = localAppReceiptHandle;
        this.localAppOutputFile = localAppOutputFile;
        this.completedTasks = new AtomicInteger(0);
        this.failedTasks = new AtomicInteger(0);
    }

    public void incrementCompleted() {
        completedTasks.incrementAndGet();
    }

    public void incrementFailed() {
        failedTasks.incrementAndGet();
    }

    public boolean isCompleted() {
        // A job is complete when all tasks (completed + failed) equal the total sent
        // tasks
        return (completedTasks.get() + failedTasks.get() == totalSubtasks);
    }

    // --- Getters for Manager processing ---

    public String getJobId() {
        return jobId;
    }

    public String getLocalAppReceiptHandle() {
        return localAppReceiptHandle;
    }

    public int getTotalSubtasks() {
        return totalSubtasks;
    }

    public String getLocalAppOutputFile() {
        return localAppOutputFile;
    }

    // You can add methods here to store the S3 links of the analyzed files for the
    // final HTML summary.
}