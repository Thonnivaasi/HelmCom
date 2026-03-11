package com.ridex.app;
import android.util.Log;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DiscoveryHelper {
    public interface Listener {
        void onFound(InetAddress addr, String hostName);
        void onTimeout();
    }
    private static final String TAG    = "RideX";
    private static final int    PORT   = 51000;
    private static final String BEACON = "RIDEX_HOST";
    private static final String PROBE  = "RIDEX_PROBE";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    // HOST: listens on PORT, replies to probes
    public void startBeacon(String hostName) {
        running.set(true);
        thread = new Thread(() -> {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket(PORT);
                sock.setBroadcast(true);
                sock.setSoTimeout(1000);
                Log.d(TAG, "Beacon started on port " + PORT);
                byte[] reply = (BEACON + ":" + hostName).getBytes("UTF-8");
                while (running.get()) {
                    try {
                        byte[] buf = new byte[64];
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        sock.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                        Log.d(TAG, "Beacon got: " + msg + " from " + pkt.getAddress());
                        if (msg.trim().equals(PROBE)) {
                            // Send reply directly back to sender's IP on PORT
                            DatagramPacket rep = new DatagramPacket(
                                reply, reply.length, pkt.getAddress(), PORT);
                            sock.send(rep);
                            Log.d(TAG, "Beacon replied to " + pkt.getAddress());
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (Exception e) { Log.e(TAG, "Beacon error: " + e); }
            finally { if (sock != null && !sock.isClosed()) sock.close(); }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // GUEST: sends broadcast probe, waits for reply on same socket
    public void searchForHost(Listener listener) {
        running.set(true);
        thread = new Thread(() -> {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket(PORT);
                sock.setBroadcast(true);
                sock.setSoTimeout(1000);
                Log.d(TAG, "Searching for host...");
                byte[] probe = PROBE.getBytes("UTF-8");
                long deadline = System.currentTimeMillis() + 12000;
                // Send probe every second via broadcast
                long lastSend = 0;
                while (running.get() && System.currentTimeMillis() < deadline) {
                    // Send broadcast probe every 1.5s
                    if (System.currentTimeMillis() - lastSend > 1500) {
                        try {
                            InetAddress bcast = InetAddress.getByName("255.255.255.255");
                            sock.send(new DatagramPacket(probe, probe.length, bcast, PORT));
                            Log.d(TAG, "Sent broadcast probe");
                        } catch (Exception e) { Log.e(TAG, "Probe send error: " + e); }
                        lastSend = System.currentTimeMillis();
                    }
                    try {
                        byte[] buf = new byte[128];
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        sock.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                        Log.d(TAG, "Search got: " + msg + " from " + pkt.getAddress());
                        if (msg.startsWith(BEACON + ":")) {
                            String name = msg.substring(BEACON.length() + 1);
                            running.set(false);
                            listener.onFound(pkt.getAddress(), name);
                            return;
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
                Log.d(TAG, "Search timed out");
                listener.onTimeout();
            } catch (Exception e) { Log.e(TAG, "Search error: " + e); listener.onTimeout(); }
            finally { if (sock != null && !sock.isClosed()) sock.close(); }
        });
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) thread.interrupt();
    }
}
