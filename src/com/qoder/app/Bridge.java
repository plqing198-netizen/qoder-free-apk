package com.qoder.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;

/** JS 桥。chatRaw 为静态方法供网关复用。 */
public class Bridge {
    private final Context ctx;

    public Bridge(Context ctx) {
        this.ctx = ctx;
    }

    // ---- 账号 ----
    @JavascriptInterface
    public String account() {
        String s = Store.accountStr(ctx);
        return s == null ? "{}" : s;
    }

    @JavascriptInterface
    public String startLogin() {
        try {
            String machineId = DeviceFingerprint.deriveId("anon", "machine");
            QoderApi.LoginStart s = QoderApi.startLogin(machineId);
            JSONObject o = new JSONObject();
            o.put("state", s.state);
            o.put("authUrl", s.authUrl);
            o.put("verifier", s.verifier);
            o.put("nonce", s.nonce);
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    @JavascriptInterface
    public String pollLogin(String nonce, String verifier) {
        try {
            java.util.Map<String, Object> r = QoderApi.pollLogin(nonce, verifier);
            if (!"ok".equals(r.get("status"))) {
                return new JSONObject().put("status", r.get("status"))
                        .put("message", String.valueOf(r.getOrDefault("message", ""))).toString();
            }
            String token = (String) r.get("token");
            JSONObject acc = new JSONObject();
            acc.put("access_token", token);
            acc.put("refresh_token", String.valueOf(r.getOrDefault("refresh_token", "")));
            acc.put("uid", String.valueOf(r.getOrDefault("user_id", "")));
            try {
                java.util.Map<String, Object> ui = QoderApi.userinfo(token);
                acc.put("uid", String.valueOf(ui.getOrDefault("id", acc.optString("uid"))));
                acc.put("nickname", String.valueOf(ui.getOrDefault("name", "")));
                acc.put("user_type", String.valueOf(ui.getOrDefault("user_type", "")));
                acc.put("organization_id", String.valueOf(ui.getOrDefault("organization_id", "")));
                acc.put("organization_name", String.valueOf(ui.getOrDefault("organization_name", "")));
            } catch (Exception ignored) {}
            acc.put("realm", "cn");
            acc.put("login_at", System.currentTimeMillis());
            Store.saveAccount(ctx, acc);
            return new JSONObject().put("status", "ok").put("account", acc).toString();
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    @JavascriptInterface
    public String logout() {
        ctx.getSharedPreferences("qoder_prefs", Context.MODE_PRIVATE).edit().remove("account").apply();
        return "{}";
    }

    @JavascriptInterface
    public void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    // ---- 模型 / 聊天 ----
    @JavascriptInterface
    public String fetchModels() {
        try {
            CosySession sess = instSession();
            if (sess == null) return "{\"error\":\"未登录\"}";
            String raw = QoderApi.fetchModels(sess);
            Store.saveModels(ctx, raw);
            return raw;
        } catch (Exception e) {
            return "{\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    @JavascriptInterface
    public String chat(String payloadJson) {
        return chatRaw(ctx, payloadJson);
    }

    /** 静态 chat 逻辑（Bridge 与网关共用）。 */
    public static String chatRaw(Context ctx, String payloadJson) {
        try {
            CosySession sess = session(ctx);
            if (sess == null) return "{\"code\":-1,\"body\":\"未登录\"}";
            JSONObject payload = new JSONObject(payloadJson);
            JSONArray messages = payload.optJSONArray("messages");
            String modelKey = payload.optString("model", "auto");
            String modelDisplay = modelKey;
            boolean isVl = true, isReasoning = true;
            int maxInputTokens = 180000;
            java.util.List<ModelCatalog.Model> cats = ModelCatalog.parse(Store.models(ctx));
            for (ModelCatalog.Model m : cats) {
                if (m.key.equals(modelKey)) {
                    modelDisplay = m.displayName;
                    isVl = m.isVl;
                    isReasoning = m.isReasoning;
                    maxInputTokens = m.maxInputTokens;
                    break;
                }
            }
            int maxTokens = payload.optInt("max_tokens", 32768);
            String systemText = payload.optString("system", "");
            String json = ChatBody.build(messages, modelKey, modelDisplay, isVl,
                    isReasoning, maxInputTokens, sess.userType, maxTokens, systemText);
            String encodedBody = ChatBody.encode(json);

            final StringBuilder content = new StringBuilder();
            final StringBuilder reasoning = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            HttpUtil.Resp r = QoderApi.chatStream(sess, encodedBody, new HttpUtil.SseListener() {
                @Override
                public boolean onData(String data) {
                    SseParser.Chunk c = SseParser.parse(data);
                    if (c == null) return true;
                    if (c.error != null) { err.append(c.error); return false; }
                    if (c.done) return false;
                    if (!c.reasoning.isEmpty()) reasoning.append(c.reasoning);
                    if (!c.content.isEmpty()) content.append(c.content);
                    return true;
                }
            });
            JSONObject out = new JSONObject();
            if (r.code != 200) {
                out.put("code", r.code);
                out.put("body", err.length() > 0 ? err.toString() : r.body);
                return out.toString();
            }
            out.put("code", 200);
            out.put("body", content.toString());
            out.put("reasoning", reasoning.toString());
            return out.toString();
        } catch (Exception e) {
            return "{\"code\":-1,\"body\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    // ---- 养号 ----
    @JavascriptInterface
    public String quota() {
        String t = Store.token(ctx);
        if (t.isEmpty()) return "{\"error\":\"未登录\"}";
        try { return QoderApi.quota(t); }
        catch (Exception e) { return "{\"error\":\"" + esc(String.valueOf(e)) + "\"}"; }
    }

    @JavascriptInterface
    public String checkin() {
        String t = Store.token(ctx);
        if (t.isEmpty()) return "{\"error\":\"未登录\"}";
        try {
            String st = QoderApi.checkinStatus(t);
            String cl = QoderApi.checkinClaim(t);
            JSONObject o = new JSONObject();
            o.put("status", st);
            o.put("claim", cl);
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    @JavascriptInterface
    public String checkinStatus() {
        String t = Store.token(ctx);
        if (t.isEmpty()) return "{\"error\":\"未登录\"}";
        try { return QoderApi.checkinStatus(t); }
        catch (Exception e) { return "{\"error\":\"" + esc(String.valueOf(e)) + "\"}"; }
    }

    // ---- 网关 ----
    @JavascriptInterface
    public String gwStart(String port) {
        try {
            int p = Integer.parseInt(port.trim());
            if (p >= 1024 && p < 65536) Store.setGatewayPort(ctx, p);
        } catch (Exception ignored) {}
        return GatewayService.startGateway();
    }

    @JavascriptInterface
    public String gwStop() {
        return GatewayService.stopGateway();
    }

    @JavascriptInterface
    public String gwState() {
        return GatewayService.stateJson();
    }

    // ---- 静态工具 ----
    static CosySession session() {
        return session(null);
    }

    private CosySession instSession() {
        return session(ctx);
    }

    static CosySession session(Context ctx) {
        JSONObject a = Store.account(ctx);
        if (a == null) return null;
        return new CosySession(
            a.optString("uid", ""),
            a.optString("nickname", ""),
            a.optString("access_token", ""),
            a.optString("refresh_token", ""),
            a.optString("user_type", ""),
            a.optString("organization_id", ""),
            a.optString("organization_name", ""));
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t");
    }
}
