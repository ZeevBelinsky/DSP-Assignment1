package worker;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class WorkerApplication {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: WorkerApplication <MWQ_URL> <WMQ_URL> <S3_BUCKET>");
            return;
        }

        String MWQ = args[0];
        String WMQ = args[1];
        String BUCKET = args[2];

        SqsClient sqs = SqsClient.builder().region(Region.US_EAST_1).build();
        S3Client s3 = S3Client.builder().region(Region.US_EAST_1).build();

        System.out.println("Worker up. MWQ=" + MWQ + " WMQ=" + WMQ + " BUCKET=" + BUCKET);

        while (true) {
            List<Message> msgs = sqs.receiveMessage(
                    ReceiveMessageRequest.builder()
                            .queueUrl(MWQ)
                            .maxNumberOfMessages(1)
                            .waitTimeSeconds(20) // long polling
                            .visibilityTimeout(3600)
                            .build())
                    .messages();

            if (msgs.isEmpty())
                continue;

            Message m = msgs.get(0);
            String body = m.body(); // {"jobId","url","analysis"}
            String jobId = extract(body, "jobId");
            String url = extract(body, "url");
            String anal = extract(body, "analysis");

            try {
                // 1) download URL -> tmp file
                Path input = Files.createTempFile("in-", ".txt");
                HttpDownloader.download(url, input);

                // 2) analyze (stub for now) -> tmp out
                Path out = Files.createTempFile("out-", ".txt");
                ParserHandler.run(anal, input, out);

                // 3) upload to S3
                String key = "results/" + jobId + "/" + System.currentTimeMillis() + ".txt";
                s3.putObject(
                        PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                        RequestBody.fromFile(out));
                String resultS3 = "s3://" + BUCKET + "/" + key;

                // 4) report success to WMQ
                String doneJson = String.format(
                        "{\"jobId\":\"%s\",\"url\":\"%s\",\"analysis\":\"%s\",\"resultS3\":\"%s\",\"ok\":true}",
                        escape(jobId), escape(url), escape(anal), escape(resultS3));
                sqs.sendMessage(SendMessageRequest.builder().queueUrl(WMQ).messageBody(doneJson).build());

                // 5) delete task from MWQ
                sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(MWQ).receiptHandle(m.receiptHandle()).build());

            } catch (Exception e) {
                // report failure and delete task (to avoid infinite retries for now)
                String errorMsg = e.getMessage() == null ? "" : e.getMessage().replace("\"", "'");
                String failJson = String.format(
                        "{\"jobId\":\"%s\",\"url\":\"%s\",\"analysis\":\"%s\",\"ok\":false,\"error\":\"%s\"}",
                        escape(jobId), escape(url), escape(anal), escape(errorMsg));
                sqs.sendMessage(SendMessageRequest.builder().queueUrl(WMQ).messageBody(failJson).build());

                sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(MWQ).receiptHandle(m.receiptHandle()).build());
            }
        }
    }

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

    private static String escape(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
