package dev.qnzapret.vpnfixture;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Test-only tg:// handler: verifies setup without installing/login to Telegram. */
public class TelegramUriReceiverActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Uri uri = getIntent().getData();
        try {
            String secret = uri == null ? "" : uri.getQueryParameter("secret");
            String host = uri == null ? "" : uri.getQueryParameter("server");
            String port = uri == null ? "" : uri.getQueryParameter("port");
            boolean valid = "127.0.0.1".equals(host) && secret != null && secret.matches("dd[0-9a-fA-F]{32}");
            JSONObject result = new JSONObject().put("valid", valid).put("host", host).put("port", port);
            try (FileOutputStream output = openFileOutput("telegram-setup-status.json", MODE_PRIVATE)) {
                output.write(result.toString().getBytes(StandardCharsets.UTF_8));
            }
            // Только private storage чистого test fixture; secret никогда не логируется.
            if (valid) try (FileOutputStream output = openFileOutput("telegram-probe-secret", MODE_PRIVATE)) {
                output.write(secret.substring(2).getBytes(StandardCharsets.US_ASCII));
            }
            TextView text = new TextView(this);
            text.setText(valid ? "Telegram setup URI verified" : "Invalid Telegram setup URI");
            setContentView(text);
        } catch (Exception error) { throw new IllegalStateException(error.getClass().getSimpleName()); }
    }
}
