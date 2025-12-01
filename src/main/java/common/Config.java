package common;

public class Config {
    public static final String S3_BUCKET_NAME = "wolfs-amaziah-bucket-1234-aws"; // <-- change this as needed 
    private static final String APP_JAR_KEY = "/app.jar";
    public static final String MANAGER_JAR_URL = "s3://" + S3_BUCKET_NAME + APP_JAR_KEY;
    public static final String WORKER_JAR_URL  = "s3://" + S3_BUCKET_NAME + APP_JAR_KEY;
}
