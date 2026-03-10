package com.ridex.app;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class DiscoveryHelper {
    public interface Listener {
        void onFound(InetAddress addr, String hostName);
        void onTimeout();
    }
    private static final String GROUP   = "224.0.0.251";
    private static final int    PORT    = 51000;
    private static final String BEACON  = "RIDEX_HOST";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public void startBeacon(String hostName) {
        running.set(true);
        thread = new Thread(() -> {
            MulticastSocket sock = null;
            try {
                sock = new MulticastSocket();
                sock.setTimeToLive(4);
                InetAddress group = InetAddress.getByName(GROUP);
                byte[] msg = (BEACON + ":" + hostName).getBytes("UTF-8");
                DatagramPacket pkt = new DatagramPacket(msg, msg.length, group, PORT);
                while (running.get()) { sock.send(pkt); Thread.sleep(1000); }
            } catch (Exception e) { e.printStackTrace(); }
            finally { if (sock != null) sock.close(); }
        });
        thread.setDaemon(true); thread.start();
    }

    public void searchForHost(Listener listener) {
        running.set(true);
        thread = new Thread(() -> {
            MulticastSocket sock = null;
            try {
                sock = new MulticastSocket(PORT);
                InetAddress group = InetAddress.getByName(GROUP);
                sock.joinGroup(group);
                sock.setSoTimeout(500);
                long deadline = System.currentTimeMillis() + 15000;
                byte[] buf = new byte[128];
                while (running.get() && System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        String m = new String(p.getData(), 0, p.getLength(), "UTF-8");
                        if (m.startsWith(BEACON + ":")) {
                            String name = m.substring(BEACON.length() + 1);
                            running.set(false);
                            try { sock.leaveGroup(group); } catch (Exception ignored) {}
                            sock.close(); sock = null;
                            listener.onFound(p.getAddress(), name);
                            return;
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
                listener.onTimeout();
            } catch (Exception e) { e.printStackTrace(); listener.onTimeout(); }
            finally { if (sock != null) { try { sock.close(); } catch (Exception ignored) {} } }
        });
        thread.setDaemon(true); thread.start();
    }

    public void stop() { running.set(false); if (thread != null) thread.interrupt(); }
}
