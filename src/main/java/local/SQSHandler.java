import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import java.util.Map; // ייבוא חדש לשימוש במפה

public class SQSHandler {
    private final SqsClient sqs;
    private final Region region = Region.US_EAST_1;

    public SQSHandler() {
        this.sqs = SqsClient.builder().region(region).build();
    }

    // השיטה המעודכנת מקבלת Map<String, String> עבור תכונות התור
    public String createQueue(String queueName, Map<String, String> attributes) {
        try {
            // יצירת הבקשה כוללת כעת את ה-attributes
            CreateQueueRequest createRequest = CreateQueueRequest.builder()
                    .queueName(queueName)
                    .attributes(attributes) // הוספת התכונות
                    .build();
            sqs.createQueue(createRequest);

            // אחזור ה-URL של התור
            GetQueueUrlResponse getUrlResponse = sqs
                    .getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build());
            return getUrlResponse.queueUrl();

        } catch (Exception e) {
            System.err.println("Error creating or getting queue URL: " + e.getMessage());
            return null;
        }
    }
}