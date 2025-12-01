package worker;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

class HttpDownloader {
    static void download(String url, Path to) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode() + " for " + url);
        try (InputStream in = res.body()) {
            Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
