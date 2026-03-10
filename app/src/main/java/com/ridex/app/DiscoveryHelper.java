package com.ridex.app;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DiscoveryHelper {
    public interface Listener {
        void onFound(InetAddress addr, String hostName);
        void onTimeout();
    }
    private static final int    PORT   = 51000;
    private static final String BEACON = "RIDEX_HOST";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread beaconThread;
    private DatagramSocket beaconSock;

    public void startBeacon(String hostName) {
        running.set(true);
        beaconThread = new Thread(() -> {
            try {
                beaconSock = new DatagramSocket(PORT);
                byte[] msg = (BEACON + ":" + hostName).getBytes("UTF-8");
                while (running.get()) {
                    // Reply to any probe that arrives
                    try {
                        beaconSock.setSoTimeout(1000);
                        byte[] buf = new byte[64];
                        DatagramPacket probe = new DatagramPacket(buf, buf.length);
                        beaconSock.receive(probe);
                        String txt = new String(probe.getData(), 0, probe.getLength(), "UTF-8");
                        if (txt.equals("RIDEX_PROBE")) {
                            DatagramPacket reply = new DatagramPacket(msg, msg.length,
                                probe.getAddress(), probe.getPort());
                            beaconSock.send(reply);
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (Exception e) { e.printStackTrace(); }
            finally { if (beaconSock != null && !beaconSock.isClosed()) beaconSock.close(); }
        });
        beaconThread.setDaemon(true);
        beaconThread.start();
    }

    public void searchForHost(Listener listener) {
        running.set(true);
        new Thread(() -> {
            try {
                // Get own IP to derive subnet
                String myIp = getLocalIp();
                if (myIp == null) { listener.onTimeout(); return; }
                String subnet = myIp.substring(0, myIp.lastIndexOf('.') + 1);

                // Listen for replies on a random port
                DatagramSocket recvSock = new DatagramSocket();
                recvSock.setSoTimeout(300);
                int myPort = recvSock.getLocalPort();

                byte[] probe = "RIDEX_PROBE".getBytes("UTF-8");
                AtomicBoolean found = new AtomicBoolean(false);
                AtomicInteger pending = new AtomicInteger(254);
                ExecutorService pool = Executors.newFixedThreadPool(30);

                // Scan all 254 hosts in parallel
                for (int i = 1; i <= 254 && running.get(); i++) {
                    final String ip = subnet + i;
                    if (ip.equals(myIp)) { pending.decrementAndGet(); continue; }
                    pool.execute(() -> {
                        try {
                            DatagramSocket s = new DatagramSocket();
                            s.setSoTimeout(500);
                            InetAddress addr = InetAddress.getByName(ip);
                            s.send(new DatagramPacket(probe, probe.length, addr, PORT));
                            s.close();
                        } catch (Exception ignored) {}
                        finally { pending.decrementAndGet(); }
                    });
                }
                pool.shutdown();

                // Listen for replies while probes are being sent
                long deadline = System.currentTimeMillis() + 8000;
                while (!found.get() && running.get() && System.currentTimeMillis() < deadline) {
                    try {
                        byte[] buf = new byte[128];
                        DatagramPacket reply = new DatagramPacket(buf, buf.length);
                        recvSock.receive(reply);
                        String msg = new String(reply.getData(), 0, reply.getLength(), "UTF-8");
                        if (msg.startsWith(BEACON + ":")) {
                            String name = msg.substring(BEACON.length() + 1);
                            found.set(true);
                            recvSock.close();
                            listener.onFound(reply.getAddress(), name);
                            return;
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
                recvSock.close();
                if (!found.get()) listener.onTimeout();
            } catch (Exception e) { e.printStackTrace(); listener.onTimeout(); }
        }).start();
    }

    private String getLocalIp() {
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    public void stop() {
        running.set(false);
        if (beaconThread != null) beaconThread.interrupt();
        if (beaconSock != null && !beaconSock.isClosed()) beaconSock.close();
    }
}
