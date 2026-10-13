package dev.qnzapret.vpnfixture;

import android.app.Activity;
import android.os.Bundle;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;
import org.json.JSONObject;

/** Два независимых SOCKS UDP association к одному echo target; только QA emulator. */
public class UdpAssociationProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        final int targetPort = getIntent().getIntExtra("echoPort", 0);
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try (Socket first = new Socket("127.0.0.1", 1080);
                 Socket second = new Socket("127.0.0.1", 1080);
                 DatagramSocket one = new DatagramSocket();
                 DatagramSocket two = new DatagramSocket()) {
                int portOne = associate(first);
                int portTwo = associate(second);
                byte[] payloadOne = "association-one".getBytes("UTF-8");
                byte[] payloadTwo = "association-two".getBytes("UTF-8");
                send(one, portOne, targetPort, payloadOne);
                send(two, portTwo, targetPort, payloadTwo);
                result.put("distinctRelayPorts", portOne != portTwo);
                result.put("firstReplyCorrect", Arrays.equals(receive(one), payloadOne));
                result.put("secondReplyCorrect", Arrays.equals(receive(two), payloadTwo));
            } catch (Exception error) {
                try { result.put("error", error.getClass().getSimpleName()); } catch (Exception ignored) {}
            }
            try (FileOutputStream output = openFileOutput("udp-results.json", MODE_PRIVATE)) {
                output.write(result.toString().getBytes("UTF-8"));
            } catch (Exception error) { throw new IllegalStateException(error); }
            runOnUiThread(this::finish);
        }, "QNZUdpAssociationProbe").start();
    }

    private static int associate(Socket socket) throws Exception {
        socket.setSoTimeout(5000);
        OutputStream out = socket.getOutputStream();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        out.write(new byte[]{5, 1, 0}); out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0) throw new Exception("Handshake");
        out.write(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0) throw new Exception("Association");
        in.readUnsignedByte();
        int type = in.readUnsignedByte();
        if (type != 1 && type != 4) throw new Exception("Address type");
        byte[] address = new byte[type == 1 ? 4 : 16]; in.readFully(address);
        return in.readUnsignedShort();
    }

    private static void send(DatagramSocket socket, int relayPort, int targetPort, byte[] payload) throws Exception {
        byte[] frame = new byte[10 + payload.length];
        frame[3] = 1; frame[4] = 10; frame[6] = 2; frame[7] = 2;
        frame[8] = (byte)(targetPort >> 8); frame[9] = (byte)targetPort;
        System.arraycopy(payload, 0, frame, 10, payload.length);
        socket.send(new DatagramPacket(frame, frame.length, InetAddress.getByName("127.0.0.1"), relayPort));
    }

    private static byte[] receive(DatagramSocket socket) throws Exception {
        socket.setSoTimeout(7000);
        DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
        socket.receive(packet);
        int offset = packet.getData()[3] == 1 ? 10 : 22;
        return Arrays.copyOfRange(packet.getData(), offset, packet.getLength());
    }
}
