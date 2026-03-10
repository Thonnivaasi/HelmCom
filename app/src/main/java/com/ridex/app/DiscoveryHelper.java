package com.ridex.app;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class DiscoveryHelper {
    public interface Listener {
        void onFound(InetAddress addr, String code);
        void onTimeout();
    }
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    public void startBeacon(String code) {
        running.set(true);
        thread = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                byte[] msg = ("RIDEX_HOST:" + code).getBytes();
                DatagramPacket pkt = new DatagramPacket(msg, msg.length,
                    InetAddress.getByName("255.255.255.255"), Protocol.PORT_DISCOVERY);
                while (running.get()) { s.send(pkt); Thread.sleep(1000); }
            } catch (Exception e) {}
        });
        thread.setDaemon(true); thread.start();
    }
    public void searchForHost(String code, Listener listener) {
        running.set(true);
        thread = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(Protocol.PORT_DISCOVERY)) {
                s.setSoTimeout(500);
                long deadline = System.currentTimeMillis() + 10000;
                byte[] buf = new byte[64];
                while (running.get() && System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        String m = new String(p.getData(), 0, p.getLength());
                        if (m.startsWith("RIDEX_HOST:") && m.substring(11).equals(code)) {
                            running.set(false);
                            listener.onFound(p.getAddress(), code);
                            return;
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
                listener.onTimeout();
            } catch (Exception e) { listener.onTimeout(); }
        });
        thread.setDaemon(true); thread.start();
    }
    public void stop() { running.set(false); if (thread != null) thread.interrupt(); }
}
