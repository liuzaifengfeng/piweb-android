package com.piweb.launcher;

import android.content.res.AssetManager;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.*;

/** Temporary authenticated, fixed-file loopback transport. No file browser or shell endpoint. */
final class ProvisionServer implements Closeable {
    private static final String[] FILES = {"bootstrap.sh", "provision.py", "manifest.json", "piweb.tar.gz"};
    private final AssetManager assets;
    private final ServerSocket socket;
    private final ExecutorService workers = new ThreadPoolExecutor(1, 3, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(8), new ThreadPoolExecutor.AbortPolicy());
    private final String token;
    private final long deadline = System.currentTimeMillis() + 30 * 60 * 1000;
    private volatile boolean closed, cancelled;
    private volatile JSONObject state;

    ProvisionServer(AssetManager assets) throws Exception {
        this.assets = assets;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        token = hex(bytes);
        socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        socket.setSoTimeout(1000);
        state = new JSONObject().put("phase", "waiting").put("message", "等待 Termux 安装器回调");
        new Thread(new Runnable() { public void run() { acceptLoop(); } }, "provision-loopback").start();
    }
    String base() { return "http://127.0.0.1:" + socket.getLocalPort() + "/" + token; }
    synchronized JSONObject state() { return state; }
    synchronized void localState(String phase, String message) {
        try { state = new JSONObject().put("phase", phase).put("message", message); } catch (Exception ignored) {}
    }
    void cancel() { cancelled = true; }
    boolean terminal() {
        String p = state.optString("phase");
        return p.equals("ready") || p.equals("existing") || p.equals("missing") || p.equals("failed") || p.equals("cancelled");
    }
    private void acceptLoop() {
        while (!closed) {
            if (System.currentTimeMillis() > deadline) {
                localState("failed", "安装任务超过 30 分钟；传输服务已关闭，可检查 Termux 日志后重试");
                close();
                break;
            }
            try {
                final Socket client = socket.accept();
                try { workers.execute(new Runnable() { public void run() { handle(client); } }); }
                catch (RejectedExecutionException e) { client.close(); }
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) { if (!closed) localState("failed", "资源服务连接失败"); }
        }
    }
    private String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (b.size() >= 4096) throw new IOException("Header too long");
            if (c != '\r') b.write(c);
        }
        return b.toString("US-ASCII");
    }
    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            String[] request = line(in).split(" ");
            if (request.length != 3) { reply(out, 400, "{}"); return; }
            String name = request[1];
            String prefix = "/" + token + "/";
            if (!name.startsWith(prefix)) { reply(out, 404, "{}"); return; }
            name = name.substring(prefix.length());
            int length = 0, total = 0;
            for (;;) {
                String h = line(in);
                total += h.length();
                if (total > 16384) throw new IOException("Headers too long");
                if (h.isEmpty()) break;
                if (h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
                if (h.toLowerCase(java.util.Locale.ROOT).startsWith("transfer-encoding:")) throw new IOException("Chunked requests unsupported");
            }
            if ("POST".equals(request[0]) && "status".equals(name)) {
                if (length < 2 || length > 8192) { reply(out, 413, "{}"); return; }
                byte[] bytes = new byte[length];
                for (int n = 0; n < length;) { int r = in.read(bytes, n, length-n); if (r < 0) throw new EOFException(); n += r; }
                JSONObject next = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                String phase = next.optString("phase");
                if (!phase.matches("waiting|checking|manifest|dependencies|transfer|extract|runtime|commit|starting|ready|existing|missing|failed|cancelled")) {
                    reply(out, 400, "{}"); return;
                }
                synchronized (this) { state = next; }
                reply(out, 200, new JSONObject().put("cancelled", cancelled).toString());
                return;
            }
            if (!"GET".equals(request[0])) { reply(out, 405, "{}"); return; }
            boolean allowed = false;
            for (String f : FILES) if (f.equals(name)) allowed = true;
            if (!allowed) { reply(out, 404, "{}"); return; }
            try (InputStream file = assets.open("provision/" + name, AssetManager.ACCESS_STREAMING)) {
                // Connection-close framing allows streaming a large compressed asset without buffering it.
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                byte[] buf = new byte[65536];
                int n;
                while ((n = file.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
            // Do not log URLs, tokens or request bodies. A disconnect is safe to retry.
        }
    }
    private void reply(OutputStream out, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " Response\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body); out.flush();
    }
    static String assetHash(AssetManager assets, String name) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = assets.open("provision/" + name)) {
            byte[] bytes = new byte[65536]; int n;
            while ((n = in.read(bytes)) != -1) d.update(bytes, 0, n);
        }
        return hex(d.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte x : bytes) b.append(String.format(java.util.Locale.ROOT, "%02x", x & 255));
        return b.toString();
    }
    public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }
}
