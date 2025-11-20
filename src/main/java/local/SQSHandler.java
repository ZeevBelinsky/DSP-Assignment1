package local;

import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import java.util.Map;
import java.util.List;

public class SQSHandler {
    private final SqsClient sqs;
    private final Region region = Region.US_EAST_1; // Consistent region setting

    public SQSHandler() {
        this.sqs = SqsClient.builder().region(region).build();
    }

    public String createQueue(String queueName, Map<QueueAttributeName, String> attributes) {
        // Creates the queue if it doesn't exist, and returns its URL
        try {
            CreateQueueRequest createRequest = CreateQueueRequest.builder()
                    .queueName(queueName)
                    .attributes(attributes)
                    .build();
            sqs.createQueue(createRequest);

            // Fetch the queue URL
            GetQueueUrlResponse getUrlResponse = sqs
                    .getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build());
            return getUrlResponse.queueUrl();

        } catch (Exception e) {
            System.err.println("Error creating or getting queue URL: " + e.getMessage());
            return null;
        }
    }

    public boolean sendMessage(String queueUrl, String messageBody) {
        // Sends a message to the specified SQS queue
        try {
            SendMessageRequest sendMsgRequest = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build();
            sqs.sendMessage(sendMsgRequest);
            System.out.println("Message successfully sent to SQS queue: " + queueUrl);
            return true;
        } catch (Exception e) {
            System.err.println("Error sending message to SQS queue " + queueUrl + ": " + e.getMessage());
            return false;
        }
    }

    public List<Message> receive(String queueUrl, int maxMessages, int waitSeconds, int visibilityTimeout) {
        ReceiveMessageRequest req = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitSeconds) // long polling
                .visibilityTimeout(visibilityTimeout)
                .build();
        return sqs.receiveMessage(req).messages();
    }

    public void delete(String queueUrl, String receiptHandle) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }

    public int getApproximateMessageCount(String queueUrl) {
        GetQueueAttributesResponse attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                .build());
        return Integer.parseInt(attrs.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
    }

}