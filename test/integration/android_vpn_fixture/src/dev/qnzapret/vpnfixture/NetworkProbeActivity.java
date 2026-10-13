package dev.qnzapret.vpnfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** HTTPS through the fixture UID; bounded overall deadlines include platform retries/DNS. */
public class NetworkProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView view = new TextView(this);
        view.setText("QNZapret HTTPS-over-TUN probe running");
        setContentView(view);
        new Thread(() -> {
            boolean quick = getIntent().getBooleanExtra("quick", false);
            ExecutorService executor = Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "QNZHttpsRequest"); thread.setDaemon(true); return thread;
            });
            JSONArray results = new JSONArray();
            String[] targets = quick ? new String[]{"https://www.google.com", "https://www.youtube.com"} :
                new String[]{"https://example.com", "https://www.google.com", "https://www.youtube.com"};
            for (String target : targets) {
                long started = System.currentTimeMillis();
                Future<JSONObject> request = executor.submit(() -> probe(target));
                JSONObject result;
                try { result = request.get(quick ? 15 : 25, TimeUnit.SECONDS); }
                catch (Exception error) {
                    request.cancel(true); result = new JSONObject();
                    try { result.put("error", error.getClass().getSimpleName()); } catch (Exception ignored) {}
                }
                try { result.put("url", target).put("elapsedMs", System.currentTimeMillis()-started); }
                catch (Exception ignored) {}
                results.put(result);
                save("network-progress.json", results);
            }
            executor.shutdownNow();
            save("network-results.json", results);
            runOnUiThread(() -> view.setText(results.toString()));
        }, "QNZVpnNetworkProbe").start();
    }

    private static JSONObject probe(String target) throws Exception {
        JSONObject result = new JSONObject();
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        try {
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            result.put("status", connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                result.put("bytes", input.read(new byte[8192]));
            }
        } catch (Exception error) { result.put("error", error.getClass().getSimpleName()); }
        finally { connection.disconnect(); }
        return result;
    }

    private void save(String name, JSONArray results) {
        try (FileOutputStream file = openFileOutput(name, MODE_PRIVATE)) {
            file.write(results.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
