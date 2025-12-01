package manager;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;

class SummaryBuilder {

    static String buildAndUpload(String bucket, String jobId, List<ManagerApplication.TaskResult> results) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
            .append("<title>Summary ").append(escape(jobId)).append("</title>")
            .append("<style>body{font-family:sans-serif}table{border-collapse:collapse}td,th{border:1px solid #ddd;padding:6px}</style>")
            .append("</head><body>");
        html.append("<h1>Job ").append(escape(jobId)).append("</h1>");
        html.append("<table><tr><th>#</th><th>Analysis</th><th>URL</th><th>Status</th><th>Result</th></tr>");

        int i = 1;
        for (ManagerApplication.TaskResult r : results) {
            html.append("<tr>");
            html.append("<td>").append(i++).append("</td>");
            html.append("<td>").append(escape(r.analysis)).append("</td>");
            html.append("<td><a href=\"").append(escape(r.url)).append("\">link</a></td>");
            if (r.ok) {
                html.append("<td>OK</td>");
                html.append("<td><code>").append(escape(r.resultS3)).append("</code></td>");
            } else {
                html.append("<td>FAILED</td>");
                html.append("<td><pre>").append(escape(r.error == null ? "" : r.error)).append("</pre></td>");
            }
            html.append("</tr>");
        }
        html.append("</table></body></html>");

        String key = "summaries/" + jobId + ".html";
        S3Client s3 = S3Client.builder().region(Region.US_EAST_1).build();
        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType("text/html").build(),
                RequestBody.fromBytes(html.toString().getBytes(StandardCharsets.UTF_8))
        );
        return "s3://" + bucket + "/" + key;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }
}
