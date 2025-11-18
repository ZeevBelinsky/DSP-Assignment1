package local;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import java.io.File;

public class S3Handler {
    private final S3Client s3;
    private final String bucketName;
    private final Region region = Region.US_EAST_1;

    public S3Handler(String bucketName) {
        this.bucketName = bucketName;
        this.s3 = S3Client.builder().region(region).build();
        ensureBucketExists();
    }

    // Uploads a local file to S3 under a unique key
    private void ensureBucketExists() {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
            System.out.println("S3 Bucket created successfully: " + bucketName);
        } catch (BucketAlreadyOwnedByYouException e) {
            System.out.println("S3 Bucket already exists and is owned by you: " + bucketName);
        } catch (Exception e) {
            System.err.println("Critical Error: Failed to create or verify S3 Bucket: " + e.getMessage());
        }
    }

    // Uploads a local file to S3 under a unique key
    public String uploadFile(String inputFilePath, String prefix) {
        String keyName = prefix + "/" + new File(inputFilePath).getName() + "-" + System.currentTimeMillis();

        try {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(keyName)
                    .build(),
                    RequestBody.fromFile(new File(inputFilePath))); // מעלה את תוכן הקובץ

            System.out.println("Input file uploaded to S3 key: " + keyName);
            return keyName;

        } catch (Exception e) {
            System.err.println("Error uploading file to S3: " + e.getMessage());
            return null;
        }
    }

    public String getBucketName() {
        return bucketName;
    }

}