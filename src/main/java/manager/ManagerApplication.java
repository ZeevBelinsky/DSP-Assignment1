package manager;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.util.concurrent.*;
import java.util.List;
import java.util.ArrayList;

public class ManagerApplication {

    private static String LM_QUEUE_URL; // Local -> Manager
    private static String MW_QUEUE_URL; // Manager -> Worker
    private static String WM_QUEUE_URL; // Worker -> Manager
    private static String S3_BUCKET_NAME;
    private static int N_WORKERS_RATIO;
    private static boolean TERMINATE_MODE;

    private final SqsClient sqs;
    private final ConcurrentHashMap<String, ManagerJob> activeJobs;
    private final ExecutorService taskExecutor;

    public ManagerApplication() {
        this.sqs = SqsClient.builder().region(Region.US_EAST_1).build();
        this.activeJobs = new ConcurrentHashMap<>();
        this.taskExecutor = Executors.newFixedThreadPool(10);
    }

    public static void main(String[] args) {
        if (args.length < 6) {
            System.err.println("Manager requires SQS URLs, Bucket Name, N, and terminate flag.");
            return;
        }

        try {
            LM_QUEUE_URL = args[0];
            MW_QUEUE_URL = args[1];
            WM_QUEUE_URL = args[2];
            S3_BUCKET_NAME = args[3];
            N_WORKERS_RATIO = Integer.parseInt(args[4]);
            TERMINATE_MODE = Boolean.parseBoolean(args[5]);
        } catch (Exception e) {
            System.err.println("Error parsing manager arguments. Aborting.");
            return;
        }

        ManagerApplication manager = new ManagerApplication();
        manager.startManagerLoop();
    }

    public void startManagerLoop() {
        System.out.println("Manager started. Listening to queues...");

        while (true) {
            // 1. בדיקה להודעות חדשות מלקוחות (LMQ)
            // נשתמש ב-short polling ו-Visibility Timeout קצר כי הטיפול נשלח ל-Thread Pool
            List<Message> newTasks = receiveMessages(LM_QUEUE_URL, 1, 30); // קבל עד 1 הודעה

            for (Message message : newTasks) {
                // שלח את הטיפול במשימה ל-Thread Pool לביצוע מקבילי (Concurrent)
                taskExecutor.submit(() -> handleNewTask(message));
            }

            // 2. בדיקה לתוצאות מפועלים (WMQ)
            List<Message> results = receiveMessages(WM_QUEUE_URL, 10, 30); // קבל עד 10 תוצאות

            for (Message result : results) {
                handleWorkerResult(result);
                // חובה למחוק את ההודעה לאחר עיבוד מוצלח
                deleteMessage(WM_QUEUE_URL, result.receiptHandle());
            }

            // 3. בדיקת תנאי סיום
            // אם הלקוח דרש סיום (TERMINATE_MODE) וכל העבודות הושלמו
            if (TERMINATE_MODE && allJobsCompleted()) {
                System.out.println("All jobs complete and Terminate mode is set. Shutting down...");
                terminateSystem();
                break; // יציאה מהלולאה וסיום המנהל
            }

            // מנוחה קצרה למניעת עלויות גבוהות מדי ב-SQS
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // שיטה לוגיקת לקליטת הודעות (נדרשת גם ב-Worker וגם ב-Local)
    private List<Message> receiveMessages(String queueUrl, int maxMessages, int visibilityTimeout) {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .visibilityTimeout(visibilityTimeout)
                .waitTimeSeconds(0)
                .build();
        return sqs.receiveMessage(receiveRequest).messages();
    }

    // שיטה ללוגיקת מחיקת הודעות
    private void deleteMessage(String queueUrl, String receiptHandle) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build();
        sqs.deleteMessage(deleteRequest);
    }

    private boolean allJobsCompleted() {
        if (activeJobs.isEmpty())
            return true;
        for (ManagerJob job : activeJobs.values()) {
            if (!job.isCompleted()) {
                return false;
            }
        }
        return true;
    }

    private void terminateSystem() {
        // 1. שלח הודעת סיום לכל ה-Workers דרך ה-MWQ
        // 2. סגור את כל תהליכי ה-EC2 של ה-Workers (EC2 client)
        // 3. סגור את המופע של ה-Manager עצמו

        // ... implementation required ...
    }
}
