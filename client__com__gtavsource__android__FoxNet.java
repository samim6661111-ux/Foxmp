package com.gtavsource.android;

import org.json.JSONObject;
import java.io.*;
import java.net.*;

/** TCP control channel + UDP realtime channel with auto reconnect. */
final class FoxNet {
    interface Listener { void onMsg(JSONObject m); void onUdp(byte[] d, int len); void onState(boolean up); }
    final Listener l; final String host, name;
    volatile int pid; volatile int udpPort = 7777, httpPort = 7779; volatile boolean up, run = true;
    private Socket tcp; private BufferedWriter out; private DatagramSocket udp; private InetAddress addr;

    FoxNet(String host, String name, Listener l) { this.host = host; this.name = name; this.l = l; }

    void start() {
        new Thread(this::tcpLoop, "fox-tcp").start();
    }
    void stop() { run = false; closeAll(); }
    private void closeAll() {
        up = false;
        try { if (tcp != null) tcp.close(); } catch (Exception e) {}
        try { if (udp != null) udp.close(); } catch (Exception e) {}
    }
    synchronized void tx(JSONObject o) {
        try { if (out != null) { out.write(o.toString()); out.write("\n"); out.flush(); } } catch (Exception e) { closeAll(); }
    }
    void tx(String t, Object... kv) {
        try { JSONObject o = new JSONObject().put("t", t); for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); tx(o); } catch (Exception e) {}
    }
    void sendUdp(byte[] b, int len) {
        try { DatagramSocket u = udp; if (u != null && addr != null) u.send(new DatagramPacket(b, len, addr, udpPort)); } catch (Exception e) {}
    }

    private void tcpLoop() {
        int backoff = 1000;
        while (run) {
            try {
                addr = InetAddress.getByName(host);
                tcp = new Socket(); tcp.connect(new InetSocketAddress(addr, 7778), 5000); tcp.setSoTimeout(0); tcp.setTcpNoDelay(true);
                out = new BufferedWriter(new OutputStreamWriter(tcp.getOutputStream(), "UTF-8"));
                BufferedReader in = new BufferedReader(new InputStreamReader(tcp.getInputStream(), "UTF-8"));
                tx("login", "name", name);
                udp = new DatagramSocket(); udp.setSoTimeout(0);
                new Thread(this::udpLoop, "fox-udp").start();
                new Thread(this::keepAlive, "fox-ka").start();
                String line; backoff = 1000;
                while (run && (line = in.readLine()) != null) {
                    JSONObject m = new JSONObject(line);
                    if ("welcome".equals(m.optString("t"))) { pid = m.getInt("pid"); udpPort = m.optInt("udp", 7777); httpPort = m.optInt("http", 7779); up = true; l.onState(true); }
                    l.onMsg(m);
                }
            } catch (Exception e) { /* retry */ }
            boolean was = up; closeAll(); if (was) l.onState(false);
            try { Thread.sleep(backoff); } catch (Exception e) {}
            backoff = Math.min(backoff * 2, 8000);
        }
    }
    private void keepAlive() {
        DatagramSocket mine = udp;
        while (run && udp == mine && !mine.isClosed()) {
            try {
                if (pid != 0) { byte[] k = new byte[5]; k[0] = 'K'; k[1] = (byte) pid; k[2] = (byte) (pid >> 8); k[3] = (byte) (pid >> 16); k[4] = (byte) (pid >> 24); sendUdp(k, 5); tx("ping"); }
                Thread.sleep(4000);
            } catch (Exception e) { return; }
        }
    }
    private void udpLoop() {
        DatagramSocket mine = udp; byte[] buf = new byte[2048];
        while (run && udp == mine) {
            try { DatagramPacket p = new DatagramPacket(buf, buf.length); mine.receive(p); l.onUdp(buf, p.getLength()); } catch (Exception e) { return; }
        }
    }
}
