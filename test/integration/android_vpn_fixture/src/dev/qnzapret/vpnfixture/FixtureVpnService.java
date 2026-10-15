package dev.qnzapret.vpnfixture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.FileOutputStream;

public class FixtureVpnService extends VpnService {
    private ParcelFileDescriptor descriptor;
    private boolean revoked;
    private void state(String value) {
        Log.i("QNZVpnFixture", value);
        try (FileOutputStream file = openFileOutput("state", MODE_PRIVATE)) {
            file.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("fixture", "VPN regression fixture", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "fixture")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("VPN regression fixture").build());
        if (descriptor == null) {
            descriptor = new Builder().setSession("QNZapret competing VPN fixture")
                .addAddress("10.253.0.1", 32).addRoute("10.253.0.0", 24).establish();
        }
        state(descriptor == null ? "FAILED" : "ACTIVE");
        return START_NOT_STICKY;
    }
    @Override public void onRevoke() {
        revoked = true;
        state("REVOKED");
        stopSelf();
    }
    @Override public void onDestroy() {
        try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) {}
        if (!revoked) state("STOPPED");
        super.onDestroy();
    }
}
