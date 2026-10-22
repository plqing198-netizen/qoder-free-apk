package com.qoder.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class HttpUtil {
    public static final int TIMEOUT_CONNECT = 20000;
    public static final int TIMEOUT_READ = 300000;

    private HttpUtil() {}

    public static class Resp {
        public int code;
        public String body;
        public Map<String, List<String>> headers;
        Resp(int c, String b, Map<String, List<String>> h) { code = c; body = b; headers = h; }
    }

    public static Resp request(String method, String url, byte[] data,
                               Map<String, String> headers) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_CONNECT);
            conn.setReadTimeout(TIMEOUT_READ);
            conn.setDoInput(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (data != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(is);
            return new Resp(code, body, conn.getHeaderFields());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static Resp get(String url, Map<String, String> headers) throws Exception {
        return request("GET", url, null, headers);
    }

    public static Resp post(String url, byte[] data, Map<String, String> headers) throws Exception {
        return request("POST", url, data, headers);
    }

    public static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 读取 SSE 流，逐个 data: 帧回调，返回是否正常结束。 */
    public interface SseListener {
        boolean onData(String line) throws Exception;
    }

    public static Resp requestStream(String method, String url, byte[] data,
                                     Map<String, String> headers, SseListener listener)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_CONNECT);
            conn.setReadTimeout(TIMEOUT_READ);
            conn.setDoInput(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (data != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                String err = readAll(conn.getErrorStream());
                return new Resp(code, err, conn.getHeaderFields());
            }
            InputStream is = conn.getInputStream();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            boolean keep = true;
            while ((line = br.readLine()) != null && keep) {
                if (line.startsWith("data:")) {
                    String d = line.substring(5).trim();
                    if (d.isEmpty() || d.equals("[DONE]")) continue;
                    if (listener != null) keep = listener.onData(d);
                }
            }
            br.close();
            return new Resp(code, "", conn.getHeaderFields());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
