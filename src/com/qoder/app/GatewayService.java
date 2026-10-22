package com.qoder.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地 OpenAI 兼容网关（默认 9253 端口），转发到 Qoder 上游。
 * 网关核心是静态的，Service 仅作为保活宿主。
 */
public class GatewayService extends Service {
    public static volatile boolean running = false;
    public static volatile int port = 9253;
    private static ServerSocket server;
    private static ExecutorService workers;
    private static android.content.Context appContext;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        appContext = getApplicationContext();
        startGateway();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopGateway();
        super.onDestroy();
    }

    public static synchronized String startGateway() {
        port = Store.gatewayPort(appContext);
        if (running) return stateJson();
        if (workers == null || workers.isShutdown()) workers = Executors.newCachedThreadPool();
        workers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    server = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
                    running = true;
                    while (!server.isClosed()) {
                        Socket s = server.accept();
                        workers.execute(new ClientHandler(s, appContext));
                    }
                } catch (Exception e) {
                    running = false;
                }
            }
        });
        return stateJson();
    }

    public static synchronized String stopGateway() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        running = false;
        server = null;
        return stateJson();
    }

    public static String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("port", port);
            o.put("baseUrl", "http://127.0.0.1:" + port + "/v1");
            return o.toString();
        } catch (Exception e) { return "{\"running\":false}"; }
    }

    static class ClientHandler implements Runnable {
        final Socket sock;
        final android.content.Context ctx;
        ClientHandler(Socket s, android.content.Context c) { sock = s; ctx = c; }

        @Override
        public void run() {
            try {
                sock.setSoTimeout(300000);
                BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
                String line = br.readLine();
                if (line == null) { sock.close(); return; }
                String[] parts = line.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                String path = parts.length > 1 ? parts[1] : "/";
                int contentLength = 0;
                while ((line = br.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                    }
                }
                String body = "";
                if (contentLength > 0) {
                    char[] buf = new char[contentLength];
                    int off = 0;
                    while (off < contentLength) {
                        int n = br.read(buf, off, contentLength - off);
                        if (n < 0) break;
                        off += n;
                    }
                    body = new String(buf, 0, off);
                }
                handle(method, path, body);
            } catch (Exception e) {
                try { writeError(500, "{\"error\":{\"message\":\"" + esc(String.valueOf(e)) + "\"}}"); } catch (Exception ignored) {}
            }
        }

        void handle(String method, String path, String body) throws Exception {
            if ("GET".equals(method) && path.startsWith("/health")) {
                writeOk("application/json", "{\"status\":\"ok\",\"port\":" + port + "}");
                return;
            }
            if ("GET".equals(method) && (path.equals("/v1/models") || path.equals("/models"))) {
                writeOk("application/json", modelsJson());
                return;
            }
            if ("POST".equals(method) && path.startsWith("/v1/chat/completions")) {
                handleChat(body);
                return;
            }
            writeError(404, "{\"error\":{\"message\":\"not found\"}}");
        }

        void handleChat(String body) throws Exception {
            JSONObject payload = new JSONObject(body);
            boolean stream = payload.optBoolean("stream", false);
            String result = Bridge.chatRaw(ctx, payload.toString());
            JSONObject r = new JSONObject(result);
            if (stream) {
                OutputStream os = sock.getOutputStream();
                String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
                os.write(head.getBytes(StandardCharsets.UTF_8));
                String content = r.optString("body", "");
                String reasoning = r.optString("reasoning", "");
                String id = "chatcmpl-" + java.util.UUID.randomUUID().toString().replace("-", "");
                long created = System.currentTimeMillis() / 1000;
                String model = payload.optString("model", "auto");
                if (!reasoning.isEmpty()) {
                    JSONObject delta = new JSONObject().put("reasoning_content", reasoning);
                    JSONObject ch = new JSONObject().put("index", 0).put("delta", delta);
                    JSONObject c0 = new JSONObject();
                    c0.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model);
                    c0.put("choices", new JSONArray().put(ch));
                    os.write(("data: " + c0 + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                JSONObject delta2 = new JSONObject().put("content", content);
                JSONObject ch2 = new JSONObject().put("index", 0).put("delta", delta2);
                JSONObject c1 = new JSONObject();
                c1.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model);
                c1.put("choices", new JSONArray().put(ch2));
                os.write(("data: " + c1 + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                sock.close();
            } else {
                JSONObject out = new JSONObject();
                out.put("id", "chatcmpl-" + java.util.UUID.randomUUID().toString().replace("-", ""));
                out.put("object", "chat.completion");
                out.put("created", System.currentTimeMillis() / 1000);
                out.put("model", payload.optString("model", "auto"));
                JSONObject msg = new JSONObject().put("role", "assistant").put("content", r.optString("body", ""));
                JSONObject ch = new JSONObject().put("index", 0).put("message", msg).put("finish_reason", "stop");
                out.put("choices", new JSONArray().put(ch));
                writeOk("application/json", out.toString());
            }
        }

        String modelsJson() {
            try {
                JSONObject root = new JSONObject(Store.models(ctx));
                JSONArray arr = root.optJSONArray("chat");
                if (arr == null) arr = new JSONArray();
                JSONArray data = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    JSONObject entry = new JSONObject();
                    entry.put("id", m.optString("key", m.optString("id", "")));
                    entry.put("object", "model");
                    entry.put("owned_by", "qoder");
                    data.put(entry);
                }
                JSONObject list = new JSONObject();
                list.put("object", "list");
                list.put("data", data);
                return list.toString();
            } catch (Exception e) {
                return "{\"object\":\"list\",\"data\":[]}";
            }
        }

        void writeOk(String ct, String json) throws Exception {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            OutputStream os = sock.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: " + ct + "\r\nContent-Length: " + b.length
                    + "\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(b);
            os.flush();
            sock.close();
        }

        void writeError(int code, String json) throws Exception {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            OutputStream os = sock.getOutputStream();
            os.write(("HTTP/1.1 " + code + " Error\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                    + "\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(b);
            os.flush();
            sock.close();
        }

        String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
