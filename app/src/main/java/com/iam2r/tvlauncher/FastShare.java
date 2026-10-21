package com.iam2r.tvlauncher;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 快传: a phone on the same Wi-Fi opens a link, picks an APK from its file picker, and it lands here ready to
 * install.
 *
 * <p>Deliberately small and self-contained: one {@link ServerSocket} on the LAN, one thread, plain HTTP with no
 * library, on <code>http://&lt;tv-ip&gt;:&lt;port&gt;/</code> like the launcher this follows. An upload must start
 * with the ZIP magic an APK begins with and its size is capped, which is what keeps a stray request from
 * becoming an install prompt.
 *
 * <p>Nothing here polls or talks to the internet: the socket exists only while the share screen is open.
 */
final class FastShare {
    /** Called on the main thread. */
    interface Listener {
        void onReceived(File apk, String name);

        void onStatus(String status);

        void onFailed(String reason);
    }

    private static final String TAG = "TvHome.FastShare";
    private static final int[] PORTS = {8808, 8809, 8810, 8811, 8812};
    private static final long MAX_BYTES = 300L * 1024 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    private final File inbox;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;
    private String address;

    FastShare(File inbox, Listener listener) {
        this.inbox = inbox;
        this.listener = listener;
    }

    /** Binds the server; returns false when no port could be opened. */
    boolean start() {
        if (running) return true;
        String ip = localAddress();
        if (ip == null) {
            listener.onFailed("no-network");
            return false;
        }
        for (int port : PORTS) {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(port), 4);
                address = "http://" + ip + ":" + port + "/";
                break;
            } catch (IOException e) {
                Log.i(TAG, "port " + port + " busy: " + e.getMessage());
            }
        }
        if (server == null) {
            listener.onFailed("no-port");
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        inbox.mkdirs();
        running = true;
        thread = new Thread(this::acceptLoop, "fast-share");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
        thread = null;
    }

    /** The link to encode in the QR code, or null before {@link #start()}. */
    String url() {
        return address;
    }

    /** This TV's IPv4 address on the LAN: the one a phone can reach, never loopback. */
    private static String localAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read the local address", e);
        }
        return null;
    }

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = server.accept();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                handle(socket);
            } catch (IOException e) {
                if (running) Log.i(TAG, "accept: " + e.getMessage());
            } catch (RuntimeException e) {
                Log.w(TAG, "request failed", e);
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream rawIn = new BufferedInputStream(socket.getInputStream());
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String requestLine = readLine(rawIn);
        if (requestLine == null || requestLine.isEmpty()) return;
        // Header block: drained so the body (if any) starts at the right place.
        int contentLength = 0;
        String header;
        while ((header = readLine(rawIn)) != null && !header.isEmpty()) {
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(header.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String target = parts.length > 1 ? parts[1] : "";
        String path = target;
        if (method.equals("GET")) {
            main.post(() -> listener.onStatus("waiting"));
            send(out, 200, "text/html; charset=utf-8", page());
            return;
        }
        if (method.equals("POST") && path.startsWith("/upload")) {
            receiveApk(rawIn, out, contentLength, queryName(path));
            return;
        }
        send(out, 405, "text/plain; charset=utf-8", "method not allowed");
    }

    private void receiveApk(InputStream in, OutputStream out, int length, String name) throws IOException {
        if (length <= 0 || length > MAX_BYTES) {
            send(out, 413, "text/plain; charset=utf-8", "too large");
            main.post(() -> listener.onFailed("too-large"));
            return;
        }
        File target = new File(inbox, safeName(name));
        byte[] head = new byte[4];
        int read = readFully(in, head, 4);
        boolean apk = read == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        if (!apk) {
            send(out, 400, "text/plain; charset=utf-8", "not an apk");
            main.post(() -> listener.onFailed("not-apk"));
            return;
        }
        long copied = 0;
        try (FileOutputStream file = new FileOutputStream(target)) {
            file.write(head, 0, read);
            copied = read;
            byte[] buffer = new byte[64 * 1024];
            while (copied < length) {
                int want = (int) Math.min(buffer.length, length - copied);
                int got = in.read(buffer, 0, want);
                if (got < 0) break;
                file.write(buffer, 0, got);
                copied += got;
            }
        }
        if (copied < length) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            send(out, 400, "text/plain; charset=utf-8", "incomplete");
            main.post(() -> listener.onFailed("incomplete"));
            return;
        }
        send(out, 200, "text/html; charset=utf-8", donePage(safeName(name)));
        final File landed = target;
        final String shown = safeName(name);
        main.post(() -> listener.onReceived(landed, shown));
    }

    private static String queryName(String path) {
        int i = path.indexOf("name=");
        if (i < 0) return "upload.apk";
        String raw = path.substring(i + 5);
        try {
            return java.net.URLDecoder.decode(raw, "UTF-8");
        } catch (Exception e) {
            return "upload.apk";
        }
    }

    /** Keeps the name readable but harmless: no path parts, always an .apk. */
    static String safeName(String name) {
        if (name == null || name.isEmpty()) return "upload.apk";
        String base = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (!base.toLowerCase(Locale.US).endsWith(".apk")) base = base + ".apk";
        return base.length() > 96 ? base.substring(base.length() - 96) : base;
    }

    private static int readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int got = in.read(buffer, read, length - read);
            if (got < 0) break;
            read += got;
        }
        return read;
    }

    /** Reads one CRLF line; the socket has a timeout, so a stuck client cannot hold the thread. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.append((char) c);
            if (line.length() > 8192) break;
        }
        return c < 0 && line.length() == 0 ? null : line.toString();
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    // ---- the two pages ---------------------------------------------------------------------------

    private String page() {
        return "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>电视快传</title><style>"
                + "body{margin:0;padding:24px;font:16px/1.6 system-ui,-apple-system,'PingFang SC',sans-serif;"
                + "background:#0f1013;color:#f2f2f2}"
                + "h1{font-size:20px;font-weight:600;margin:0 0 6px}"
                + "p{color:#9aa0a6;margin:0 0 18px}"
                + "label{display:block;padding:28px 18px;border:1px dashed #3a3f46;border-radius:14px;text-align:center;"
                + "color:#cfd3d8}"
                + "input{display:none}"
                + "button{margin-top:16px;width:100%;padding:14px;border:0;border-radius:12px;font-size:17px;"
                + "background:#3d7bfd;color:#fff}"
                + "button:disabled{background:#2a2f36;color:#7b8188}"
                + "#out{margin-top:14px;color:#8ab4f8;white-space:pre-wrap}"
                + "</style></head><body>"
                + "<h1>电视快传</h1><p>选择手机里的 APK,上传完成后电视会自动弹出安装。</p>"
                + "<label for=\"f\">点击选择 APK 文件<input id=\"f\" type=\"file\" accept=\".apk,application/vnd.android.package-archive\"></label>"
                + "<button id=\"b\" disabled>开始上传</button><div id=\"out\"></div>"
                + "<script>var f=document.getElementById('f'),b=document.getElementById('b'),o=document.getElementById('out');"
                + "f.onchange=function(){b.disabled=!f.files.length;o.textContent=f.files[0]?f.files[0].name+' · '+"
                + "(f.files[0].size/1048576).toFixed(1)+' MB':''};"
                + "b.onclick=function(){if(!f.files.length)return;var file=f.files[0];b.disabled=true;o.textContent='上传中…';"
                + "var x=new XMLHttpRequest();x.open('POST','upload?name='+encodeURIComponent(file.name));"
                + "x.upload.onprogress=function(e){if(e.lengthComputable)o.textContent='上传中 '+"
                + "Math.round(e.loaded*100/e.total)+'%'};"
                + "x.onload=function(){o.textContent=x.status==200?'已发送,请在电视上确认安装。':'发送失败 ('+x.status+')';"
                + "b.disabled=false};x.onerror=function(){o.textContent='网络错误,请确认手机与电视在同一 Wi-Fi';b.disabled=false};"
                + "x.send(file)};</script></body></html>";
    }

    private static String donePage(String name) {
        return "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>已发送</title>"
                + "<style>body{margin:0;padding:32px;font:17px/1.6 system-ui,sans-serif;background:#0f1013;color:#f2f2f2}"
                + "b{color:#8ab4f8}</style></head><body><p>已收到 <b>" + name + "</b></p>"
                + "<p>电视上应该已经弹出安装确认,请用遥控器选择「安装」。</p></body></html>";
    }
}
