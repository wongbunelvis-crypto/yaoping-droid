package com.suchuan.yaopingdroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 MQTT 3.1.1 客户端（QoS0，明文 1883），不依赖任何第三方库。
 * 只做：连上、订阅、发字节；收到消息回调。
 */
public class MqttLite {

    public interface Listener {
        void onMessage(byte[] payload);
    }

    private final String host;
    private final int port;
    private final String clientId;
    private final String subscribeTopic;
    private final String publishTopic;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean running = false;
    private Listener listener;

    public MqttLite(String host, int port, String clientId, String subscribeTopic, String publishTopic) {
        this.host = host;
        this.port = port;
        this.clientId = clientId;
        this.subscribeTopic = subscribeTopic;
        this.publishTopic = publishTopic;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void start() {
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), 6000);
                    socket.setTcpNoDelay(true);
                    in = socket.getInputStream();
                    out = socket.getOutputStream();
                    sendConnect();
                    waitConnAck();
                    sendSubscribe();
                    readLoop();
                } catch (Exception e) {
                    // 断线重连
                } finally {
                    closeQuietly();
                }
                if (running) {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                }
            }
        }, "mqtt").start();
    }

    public void stop() {
        running = false;
        closeQuietly();
    }

    public void publish(byte[] payload) {
        if (out == null) return;
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(topicBytes(publishTopic));
            body.write(payload);
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x30);
            pkt.write(encodeLen(body.size()));
            body.writeTo(pkt);
            synchronized (this) {
                out.write(pkt.toByteArray());
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- 内部 ----------

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null;
        in = null;
        out = null;
    }

    private byte[] topicBytes(String t) throws IOException {
        byte[] b = t.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((b.length >> 8) & 0xFF);
        o.write(b.length & 0xFF);
        o.write(b);
        return o.toByteArray();
    }

    private void sendConnect() throws IOException {
        byte[] cid = clientId.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        v.write(0x00); v.write(0x04);           // 协议名长度
        v.write("MQTT".getBytes(StandardCharsets.US_ASCII));
        v.write(0x04);                          // level 3.1.1
        v.write(0x02);                          // clean session
        v.write(0x00); v.write(60);             // keepalive 60s
        v.write((cid.length >> 8) & 0xFF); v.write(cid.length & 0xFF);
        v.write(cid);
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x10);
        pkt.write(encodeLen(v.size()));
        v.writeTo(pkt);
        out.write(pkt.toByteArray());
        out.flush();
    }

    private void waitConnAck() throws IOException {
        byte[] head = readN(2);
        if (head == null) throw new IOException("no connack");
        byte[] rest = readN(head[1] & 0xFF);
        if ((head[0] & 0xF0) != 0x20) throw new IOException("bad connack");
    }

    private void sendSubscribe() throws IOException {
        byte[] t = subscribeTopic.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        v.write(0x00); v.write(0x01);           // packet id
        v.write((t.length >> 8) & 0xFF); v.write(t.length & 0xFF);
        v.write(t);
        v.write(0x00);                          // QoS0
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x82);
        pkt.write(encodeLen(v.size()));
        v.writeTo(pkt);
        out.write(pkt.toByteArray());
        out.flush();
    }

    private void readLoop() throws IOException {
        long lastPing = System.currentTimeMillis();
        while (running) {
            int b0 = in.read();
            if (b0 < 0) throw new IOException("closed");
            int remaining = readLen();
            byte[] body = readN(remaining);
            if (body == null) throw new IOException("short read");
            int type = (b0 & 0xF0) >> 4;
            if (type == 3) { // PUBLISH（QoS0，无 packet id）
                int tl = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                byte[] payload = new byte[body.length - 2 - tl];
                System.arraycopy(body, 2 + tl, payload, 0, payload.length);
                if (listener != null) listener.onMessage(payload);
            }
            long now = System.currentTimeMillis();
            if (now - lastPing > 30000) {
                lastPing = now;
                synchronized (this) { out.write(0xC0); out.write(0x00); out.flush(); }
            }
        }
    }

    private int readLen() throws IOException {
        int multiplier = 1, value = 0, n, i = 0;
        do {
            n = in.read();
            if (n < 0) throw new IOException("eof");
            value += (n & 127) * multiplier;
            multiplier *= 128;
            if (++i > 4) throw new IOException("bad len");
        } while ((n & 128) != 0);
        return value;
    }

    private byte[] readN(int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new IOException("eof");
            got += r;
        }
        return b;
    }

    private byte[] encodeLen(int len) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) d |= 128;
            o.write(d);
        } while (len > 0);
        return o.toByteArray();
    }
}
