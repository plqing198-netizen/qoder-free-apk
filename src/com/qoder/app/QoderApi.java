package com.qoder.app;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Qoder 国内版私有 API 封装。
 * 端点（逆向自 qoder_accounts.py / qoder_proxy.py）：
 *   登录:   openapi.qoder.com.cn/api/v1/deviceToken/poll (PKCE 设备码)
 *   用户:   /api/v1/userinfo
 *   额度:   /api/v2/quota/usage
 *   签到:   /sash/api/v1/me/daily-check-in/{status,claim}
 *   推理:   gateway.qoder.com.cn/algo/api/v2/service/pro/sse/agent_chat_generation
 *   模型:   /algo/api/v2/model/list?Encode=1
 */
public final class QoderApi {
    // 国内版配置（逆向自 REALM_CONFIGS["cn"]）
    public static final String OPENAPI = "https://openapi.qoder.com.cn";
    public static final String GATEWAY = "https://gateway.qoder.com.cn";
    public static final String WEBSITE = "https://qoder.com.cn";
    public static final String CLIENT_ID = "1c5e33e1-364d-4ce6-b02c-acaa81274a5c";
    public static final String REDIRECT_URI = "qoder-work-cn://";
    public static final String CLIENT_UA = "QoderWork/1.1.64";

    public static final String PATH_DEVICE_POLL = "/api/v1/deviceToken/poll";
    public static final String PATH_USERINFO = "/api/v1/userinfo";
    public static final String PATH_QUOTA = "/api/v2/quota/usage";
    public static final String PATH_CHECKIN_STATUS = "/sash/api/v1/me/daily-check-in/status";
    public static final String PATH_CHECKIN_CLAIM = "/sash/api/v1/me/daily-check-in/claim";

    public static final String CHAT_PATH =
            "/algo/api/v2/service/pro/sse/agent_chat_generation?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";
    public static final String MODELS_PATH = "/algo/api/v2/model/list?Encode=1";

    private QoderApi() {}

    // ---------- PKCE ----------
    public static class LoginStart {
        public String state;
        public String authUrl;
        public String verifier;
        public String nonce;
    }

    public static LoginStart startLogin(String machineId) {
        String verifier = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String challenge = Crypto.base64Url(Crypto.sha256Digest(verifier));
        String nonce = UUID.randomUUID().toString(); // CN nonce 带横线
        LoginStart s = new LoginStart();
        s.verifier = verifier;
        s.nonce = nonce;
        s.state = "qd-" + System.nanoTime();
        StringBuilder q = new StringBuilder();
        q.append("challenge=").append(enc(challenge));
        q.append("&challenge_method=S256");
        q.append("&nonce=").append(enc(nonce));
        q.append("&redirect_uri=").append(enc(REDIRECT_URI));
        q.append("&client_id=").append(enc(CLIENT_ID));
        q.append("&machine_id=").append(enc(machineId));
        s.authUrl = WEBSITE + "/device/selectAccounts?" + q;
        return s;
    }

    /** 轮询设备码授权结果。返回 {status, token?, user_id?, refresh_token?, expires_in?}。 */
    public static Map<String, Object> pollLogin(String nonce, String verifier) throws Exception {
        String url = OPENAPI + PATH_DEVICE_POLL + "?nonce=" + enc(nonce)
                + "&verifier=" + enc(verifier) + "&challenge_method=S256";
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("User-Agent", "QoderWork");
        HttpUtil.Resp r;
        try {
            r = HttpUtil.get(url, h);
        } catch (Exception e) {
            String m = String.valueOf(e);
            if (m.contains("404") || m.contains("202")) return status("pending", "等待授权");
            throw e;
        }
        Map<String, Object> out = new HashMap<>();
        if (r.code == 404 || r.code == 202) {
            out.put("status", "pending");
            return out;
        }
        if (r.code != 200) {
            out.put("status", "error");
            out.put("message", "poll http " + r.code + " " + trunc(r.body));
            return out;
        }
        try {
            org.json.JSONObject j = new org.json.JSONObject(r.body);
            String token = j.optString("token", j.optString("device_token", ""));
            if (token.isEmpty()) {
                out.put("status", "pending");
                return out;
            }
            out.put("status", "ok");
            out.put("token", token);
            out.put("user_id", j.optString("user_id", ""));
            out.put("refresh_token", j.optString("refresh_token", ""));
            out.put("expires_in", j.opt("expires_in"));
            out.put("expires_at", j.opt("expires_at"));
            return out;
        } catch (Exception e) {
            out.put("status", "pending");
            return out;
        }
    }

    /** 用 token 拉 userinfo，补齐 uid/昵称/用户类型/组织。 */
    public static Map<String, Object> userinfo(String token) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("User-Agent", CLIENT_UA);
        h.put("Authorization", "Bearer " + token);
        HttpUtil.Resp r = HttpUtil.get(OPENAPI + PATH_USERINFO, h);
        org.json.JSONObject j = new org.json.JSONObject(r.body);
        Map<String, Object> out = new HashMap<>();
        out.put("id", j.optString("id"));
        out.put("name", j.optString("name", ""));
        out.put("user_type", j.optString("user_type", ""));
        out.put("organization_id", j.optString("organization_id", ""));
        out.put("organization_name", j.optString("organization_name", ""));
        return out;
    }

    /** 额度查询。 */
    public static String quota(String token) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("User-Agent", CLIENT_UA);
        h.put("Authorization", "Bearer " + token);
        HttpUtil.Resp r = HttpUtil.get(OPENAPI + PATH_QUOTA, h);
        return r.body;
    }

    /** 签到状态。 */
    public static String checkinStatus(String token) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("User-Agent", CLIENT_UA);
        h.put("Authorization", "Bearer " + token);
        HttpUtil.Resp r = HttpUtil.get(OPENAPI + PATH_CHECKIN_STATUS, h);
        return r.body;
    }

    /** 签到领取。 */
    public static String checkinClaim(String token) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("User-Agent", CLIENT_UA);
        h.put("Authorization", "Bearer " + token);
        h.put("Content-Type", "application/json");
        HttpUtil.Resp r = HttpUtil.post(OPENAPI + PATH_CHECKIN_CLAIM, "{}".getBytes(StandardCharsets.UTF_8), h);
        return r.body;
    }

    // ---------- 推理 ----------
    /** 拉取模型清单（COSY 签名）。 */
    public static String fetchModels(CosySession sess) throws Exception {
        String signBody = Crypto.qoderEncode("{}".getBytes(StandardCharsets.UTF_8));
        String url = GATEWAY + MODELS_PATH;
        Map<String, String> h = sess.headers(signBody, url, "", false);
        // 模型列表接口只支持 GET 带 body，走 RawHttp
        RawHttp.Resp r = RawHttp.request("GET", url, signBody.getBytes(StandardCharsets.UTF_8), h, 30000);
        if (r.code != 200) return "{\"error\":\"http " + r.code + " body=" + r.body + "\"}";
        // 响应是明文 UTF-8 JSON，直接返回（不要再 qoderDecode）
        return r.body;
    }

    /** 发送 chat（流式）。body 已经是 qoder_encode 后的字符串。 */
    public static HttpUtil.Resp chatStream(CosySession sess, String encodedBody,
                                          HttpUtil.SseListener listener) throws Exception {
        String url = GATEWAY + CHAT_PATH;
        Map<String, String> h = sess.headers(encodedBody, url, "", true);
        return HttpUtil.requestStream("POST", url,
                encodedBody.getBytes(StandardCharsets.UTF_8), h, listener);
    }

    // ---------- 工具 ----------
    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    static String trunc(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    static Map<String, Object> status(String s, String m) {
        Map<String, Object> o = new HashMap<>();
        o.put("status", s);
        o.put("message", m);
        return o;
    }
}
