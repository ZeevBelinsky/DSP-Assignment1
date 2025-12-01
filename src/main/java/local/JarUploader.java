package local;

import common.Config;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;

public class JarUploader {

    public static void main(String[] args) {
        // Path to the fat JAR that Maven builds
        Path jarPath = Path.of("target/DistributedTextAnalysis-1.0-SNAPSHOT-jar-with-dependencies.jar");

        String bucket = Config.S3_BUCKET_NAME;

        // Single key in S3 for both Manager and Worker
        String appKey = "app.jar";

        Region region = Region.US_EAST_1; // change if your bucket is in another region

        try (S3Client s3 = S3Client.builder().region(region).build()) {
            uploadOnce(s3, bucket, appKey, jarPath);
            System.out.println("Uploaded app.jar to bucket " + bucket);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Upload failed: " + e.getMessage());
        }
    }

    private static void uploadOnce(S3Client s3, String bucket, String key, Path filePath) {
        System.out.println("Uploading " + filePath + " to s3://" + bucket + "/" + key);

        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        s3.putObject(req, filePath);
    }
}
