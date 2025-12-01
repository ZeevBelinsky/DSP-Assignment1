# This is a readme for assignment 1 in DSP course

In this assignment we have implemented a parsing system by using an existing standalone parser (Stanford Parser).  
While the algorithm itself is “just” using the parser, our main core logic is how we orchestrate and scale the parsing in a distributed way.

The goal is to complete many parsing tasks by using AWS services:

- **S3** for storing input and output files
- **SQS** for sending tasks and collecting results
- **EC2** for running a Manager instance and multiple Worker instances
- A **Local application** that you run on your own machine to coordinate everything

---

## How to run the assignment

### Step 1 – Configure AWS details (credentials & region)

Make sure your AWS credentials are configured on the machine where you run the Local application.  
One common way is to use the `~/.aws/credentials` file:

```ini
[default]
aws_access_key_id = YOUR_KEY_HERE
aws_secret_access_key = YOUR_SECRET_ACCESS_KEY_HERE
aws_session_token = YOUR_SESSION_TOKEN_HERE   # only if required
```

Our default AWS region in the code is:

```text
us-east-1
```

This should match the region where your S3 bucket and EC2/SQS resources will live.

---

### Step 2 – Configure the S3 bucket in `Config.java`

Go to the file:

```text
src/main/java/common/Config.java
```

and change the `S3_BUCKET_NAME` constant as needed. For example:

```java
public class Config {
    public static final String S3_BUCKET_NAME = "your-unique-bucket-name";
    private static final String APP_JAR_KEY = "/app.jar";
    public static final String MANAGER_JAR_URL = "s3://" + S3_BUCKET_NAME + APP_JAR_KEY;
    public static final String WORKER_JAR_URL  = "s3://" + S3_BUCKET_NAME + APP_JAR_KEY;
}
```

Important notes:

- The bucket name must be **globally unique** across all possible S3 buckets in the world.
- The bucket should exist in your AWS account, in region `us-east-1`.
- The IAM credentials you configured in Step 1 must have permission to access this bucket.

---

### Step 3 – Build the project and upload the JARs to S3

From the project root (for example):

```text
Dsp@user:~/DSP-Assignment1$
```

run:

```bash
mvn clean package exec:java
```

This single command does two things:

1. **Build phase (`clean package`)**
   - Cleans previous build output.
   - Compiles all the Java source files.
   - Builds two JARs under `target/`:
     - `DistributedTextAnalysis-1.0-SNAPSHOT.jar`
     - `DistributedTextAnalysis-1.0-SNAPSHOT-jar-with-dependencies.jar` (the **fat JAR** containing all dependencies).

2. **Upload phase (`exec:java`)**
   - Runs the `local.JarUploader` main class using the project runtime classpath.
   - Uploads the fat JAR from:

     ```text
     target/DistributedTextAnalysis-1.0-SNAPSHOT-jar-with-dependencies.jar
     ```

     to your S3 bucket as:

     - `app.jar`

These two objects are later used by the Manager and Worker EC2 instances when they start up.

If everything is configured correctly, you should see output similar to:

```text
Uploading target/DistributedTextAnalysis-1.0-SNAPSHOT-jar-with-dependencies.jar to s3://<your-bucket>/app.jar
Uploaded app.jar to bucket <your-bucket>
```

---

### Step 4 – Run the Local application (this triggers the whole system on AWS)

From the same project root, we now run the Local application.  
This will connect to AWS and start the whole flow:

```bash
java -jar target/DistributedTextAnalysis-1.0-SNAPSHOT-jar-with-dependencies.jar   sample_input.txt final_output.html 1 terminate
```

Explanation of the arguments:

1. `sample_input.txt`  
   - The input file, located in the project root, listing the parsing tasks.

2. `final_output.html`  
   - The name of the output HTML file that will be created locally when the job finishes.

3. `1`  
   - The `n` parameter (worker ratio), as specified in the assignment.  
     This controls how many Worker instances are spawned relative to the number of tasks.

4. `terminate`  
   - Optional flag. When present, once all tasks are completed:
     - The Manager will send a termination signal.
     - Worker instances will be terminated.
     - The Manager instance will also terminate itself.

When you run this command, you should see logs similar to:

```text
S3 Bucket created successfully: <your-bucket>
Input file uploaded to S3 key: input-tasks/...
Message successfully sent to SQS queue: https://sqs.us-east-1.amazonaws.com/.../Local_Manager_Queue
Task submitted. Waiting for summary...
No manager found. Starting a new instance...
Manager instance started with ID: i-XXXXXXXXXXXXXXX
```

The Local application then waits until the Manager and Workers finish processing all tasks, at which point it downloads the summary HTML file from S3 and saves it locally as `final_output.html`.

---

### Note about repeating runs

Step **3** (the combined build + upload step) is only needed if you have **changed the code** and want to rebuild and redeploy the updated JAR to S3.

- If you change code:
  - Repeat **Step 3** (`mvn clean package exec:java`) to rebuild and re-upload the JARs.
  - Then run **Step 4** to start a new job.

- If you **did not change the code** and just want to run the system again with the existing JARs:
  - Make sure your AWS details (Step 1) and `S3_BUCKET_NAME` in `Config.java` (Step 2) are correct.
  - You can skip straight to **Step 4**.
