package manager;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class InputDownloader {

    static List<String> readAllLinesFromS3(String s3Url) {
        if (!s3Url.startsWith("s3://")) {
            throw new IllegalArgumentException("Bad S3 URL: " + s3Url);
        }
        String rest = s3Url.substring("s3://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("Bad S3 URL: " + s3Url);
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);

        S3Client s3 = S3Client.builder().region(Region.US_EAST_1).build();

        try (ResponseInputStream<GetObjectResponse> in =
                     s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
             BufferedReader br =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
            return lines;

        } catch (Exception e) {
            throw new RuntimeException("Failed reading " + s3Url + ": " + e.getMessage(), e);
        }
    }
}
