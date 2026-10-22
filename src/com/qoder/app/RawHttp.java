package com.qoder.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 原始 HTTPS 客户端，支持 GET 带 body + chunked 响应解码。
 * Qoder 模型列表接口是 GET 带 body 且响应 Transfer-Encoding: chunked，
 * HttpURLConnection 对 GET+body 会抛异常，必须走这里。
 */
public final class RawHttp {
    private RawHttp() {}

    public static class Resp {
        public int code;
        public String body;
    }

    public static Resp request(String method, String url, byte[] data,
                               Map<String, String> headers, int readTimeoutMs) throws Exception {
        java.net.URL u = new java.net.URL(url);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        boolean https = "https".equals(u.getProtocol());
        String path = u.getPath() + (u.getQuery() != null ? "?" + u.getQuery() : "");

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 20000);
        raw.setSoTimeout(readTimeoutMs);
        Socket sock = raw;
        if (https) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket s = (SSLSocket) f.createSocket(raw, host, port, true);
            s.startHandshake();
            sock = s;
        }

        OutputStream os = sock.getOutputStream();
        StringBuilder hb = new StringBuilder();
        hb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        hb.append("Host: ").append(host).append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                hb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        if (data != null) {
            hb.append("Content-Length: ").append(data.length).append("\r\n");
        }
        hb.append("Connection: close\r\n\r\n");
        os.write(hb.toString().getBytes(StandardCharsets.UTF_8));
        if (data != null) os.write(data);
        os.flush();

        InputStream is = sock.getInputStream();
        // 读响应头
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int prev = -1, prev2 = -1, prev3 = -1;
        int b;
        while ((b = is.read()) != -1) {
            headerBuf.write(b);
            if (prev3 == '\r' && prev2 == '\n' && prev == '\r' && b == '\n') break;
            prev3 = prev2; prev2 = prev; prev = b;
        }
        String headerText = new String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1);
        String firstLine = headerText.split("\r\n")[0];
        int code = 0;
        try {
            code = Integer.parseInt(firstLine.split(" ")[1]);
        } catch (Exception ignored) {}

        int contentLength = -1;
        boolean chunked = false;
        for (String line : headerText.split("\r\n")) {
            String low = line.toLowerCase();
            if (low.startsWith("content-length:")) {
                try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
            }
            if (low.startsWith("transfer-encoding:") && low.contains("chunked")) {
                chunked = true;
            }
        }

        byte[] bodyBytes;
        if (chunked) {
            bodyBytes = readChunked(is);
        } else if (contentLength >= 0) {
            bodyBytes = readFixed(is, contentLength);
        } else {
            bodyBytes = readToEof(is);
        }
        sock.close();

        Resp r = new Resp();
        r.code = code;
        r.body = new String(bodyBytes, StandardCharsets.UTF_8);
        return r;
    }

    private static byte[] readFixed(InputStream is, int len) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int remain = len;
        while (remain > 0) {
            int n = is.read(buf, 0, Math.min(8192, remain));
            if (n < 0) break;
            bos.write(buf, 0, n);
            remain -= n;
        }
        return bos.toByteArray();
    }

    private static byte[] readToEof(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 解码 HTTP chunked 编码。 */
    private static byte[] readChunked(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            StringBuilder sizeLine = new StringBuilder();
            int b;
            while ((b = is.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') sizeLine.append((char) b);
            }
            String sizeStr = sizeLine.toString().trim();
            int semi = sizeStr.indexOf(';');
            if (semi >= 0) sizeStr = sizeStr.substring(0, semi);
            int size = Integer.parseInt(sizeStr.trim(), 16);
            if (size == 0) {
                while (true) {
                    b = is.read();
                    if (b == -1) break;
                    if (b == '\n') break;
                }
                break;
            }
            byte[] chunk = new byte[size];
            int off = 0;
            while (off < size) {
                int n = is.read(chunk, off, size - off);
                if (n < 0) break;
                off += n;
            }
            out.write(chunk, 0, off);
            int c1 = is.read();
            int c2 = is.read();
        }
        return out.toByteArray();
    }
}
