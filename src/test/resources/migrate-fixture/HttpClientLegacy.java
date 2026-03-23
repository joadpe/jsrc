package test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;

/**
 * Uses HttpURLConnection — should migrate to java.net.http.HttpClient (Java 11+).
 */
public class HttpClientLegacy {
    public String fetch(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);

        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes());
        } finally {
            conn.disconnect();
        }
    }
}
