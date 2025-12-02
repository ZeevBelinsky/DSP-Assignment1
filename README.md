# This is a readme for assignment 1 in DSP course

# Assignment 1: Distributed Text Analysis System

### Submitted by:
* **Name:** Zeev Vladimir Belinsky, **ID:** 213995384
* **Name:** Noa Herschcovitz, **ID:** 322351305

---

In this assignment we have implemented a parsing system by using an existing standalone parser (Stanford Parser).  
While the algorithm itself is “just” using the parser, our main core logic is how we orchestrate and scale the parsing in a distributed way.

The goal is to complete many parsing tasks by using AWS services:

- **S3** for storing input and output files
- **SQS** for sending tasks and collecting results
- **EC2** for running a Manager instance and multiple Worker instances

---

## System Architecture

Our system consists of three main components communicating via SQS queues and S3 storage.

### 1. Components
* **Local Application:** The client-side application. It uploads the input file to S3, starts the Manager (if not running), and waits for the summary HTML.
* **Manager:** The central controller running on EC2. It downloads the task list, splits it into sub-tasks, scales out Worker nodes, and aggregates the results into a final HTML summary.
* **Workers:** Transient EC2 nodes that pull tasks from SQS, download the specific text file, run the Stanford Parser (POS/Constituency/Dependency), and upload the result to S3.

### 2. Communication (SQS Queues)
We used **4 SQS queues** to manage the flow and ensure scalability:
1.  **Local_Manager_Queue (LMQ):** Incoming jobs from clients to the Manager.
2.  **Manager_Worker_Queue (MWQ):** Tasks distributed from Manager to Workers.
3.  **Worker_Manager_Queue (WMQ):** Completed task notifications from Workers to Manager.
4.  **Manager_App_Queue (MAQ):** Final summary notification from Manager to the specific Local Application.

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

---

## Implementation Details & Considerations

### 1. Performance Statistics

* **Input:** 9 text files 
* **N (Workers Ratio):** 1
    * **Reason:** We chose `n=1` to aim for maximum parallelism (1 worker per file). However, the actual number of concurrent workers was constrained by the AWS Lab vCPU limit.
* **Total Processing Time:** ~60 minutes.

### 2. EC2 Instances (AMI & Type)
* **AMI:** Amazon Linux 2023 (`ami-0fa3fe0fa7920f68e` in `us-east-1`).
* **Instance Types:**
    * **Manager:** `t2.micro`. The Manager is mostly I/O bound (communicating with SQS/EC2 API), so a micro instance is sufficient.
    * **Workers:** `t3.large` (2 vCPUs, 4GB RAM). We selected this instance type because the **Stanford Parser** is highly CPU and memory-intensive. Using smaller instances (like `t2.micro` or `t2.small`) resulted in `OutOfMemoryError` or extremely slow processing times. `t3.large` provided the necessary stability and performance.

### 3. Security
* **Credentials:** We do not store AWS keys in the code. The Local App uses the local credentials file, and EC2 instances use **IAM Roles** (`LabInstanceProfile`) to access S3/SQS securely.

### 4. Scalability
* **Dynamic Scaling:** The Manager calculates the required number of workers based on the workload ($Messages / n$) and launches them dynamically.
* **Limit:** We implemented a hard cap of **19 instances** to comply with AWS Academy limits.

### 5. Persistence & Fault Tolerance
* **Worker Failure:** We use SQS **Visibility Timeout**. If a Worker crashes or stalls (e.g., due to a heavy file), the message becomes visible again in the queue after a set timeout (1 hour), allowing another worker to pick it up.
* **Manager Failure / State:** The Manager holds job state (`activeJobs`) in memory. 
    * **No automatic restart:** A new Manager is only started when a new Local App runs.
    * **State loss:** If the Manager crashes, partial progress is lost. A restarted Manager will re-fan out all tasks from the original input file stored in S3.
    * **Duplicate work:** Due to SQS "at-least-once" delivery and potential Manager restarts, some tasks may be executed more than once. The system tolerates this (idempotency) and produces a correct final summary.

### 6. Threads Usage
* **Manager:** Heavily multi-threaded. It uses a thread for listening to LMQ, another for WMQ, and a thread pool for processing incoming jobs (downloading & splitting files) without blocking the main flow.
* **Worker:** Single-threaded processing. Since each parsing task fully utilizes the CPU and memory of the `t3.large` instance, running multiple threads on a single Worker would degrade performance. Therefore, we launch more instances rather than more threads per instance.

### 7. Termination Protocol
We implemented a graceful shutdown mechanism based on a dedicated message.
* The Local Application sends a JSON termination message to the Manager **only after** it has successfully downloaded the summary:
  ```json
  {"action":"terminate", "terminate":true}

