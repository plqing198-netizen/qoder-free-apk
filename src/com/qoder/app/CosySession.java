package com.qoder.app;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单账号 COSY 签名会话。
 * 逆向自 qoder_sign.py：
 *   tempKey(16) 随机会话密钥（uuid4().hex[:16]）
 *   cosyKey = base64(RSA_PKCS1v15(tempKey))
 *   info    = base64(AES-CBC(identity_json_sorted_compact, key=iv=tempKey))
 *   payload = base64(sorted-compact {cosyVersion, ideVersion, info, requestId, version})
 *   sig     = md5(payload + "\n" + cosyKey + "\n" + date + "\n" + body + "\n" + path)
 *   Bearer  = "COSY." + payload + "." + sig
 */
public final class CosySession {
    public static final String COSY_VERSION = "1.1.64";

    public final String uid;
    public final String accessToken;
    public final String refreshToken;
    public final String nickname;
    public final String userType;
    public final String orgId;
    public final String orgName;

    public final String machineId;
    public final String machineToken;
    public final String machineType;
    public final String machineHostname;

    private final String tempKey;
    public final String cosyKey;
    public final String info;

    public CosySession(String uid, String nickname, String accessToken, String refreshToken,
                       String userType, String orgId, String orgName) {
        this.uid = uid == null ? "" : uid;
        this.nickname = nickname == null ? "" : nickname;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.refreshToken = refreshToken == null ? "" : refreshToken;
        this.userType = (userType == null || userType.isEmpty()) ? "personal_professional_trial" : userType;
        this.orgId = orgId == null ? "" : orgId;
        this.orgName = orgName == null ? "" : orgName;

        // 稳定设备指纹（按 uid 派生，同账号永远相同）
        this.machineId = DeviceFingerprint.deriveId(this.uid, "machine");
        this.machineToken = DeviceFingerprint.deriveMachineToken(this.uid);
        this.machineType = DeviceFingerprint.deriveMachineType(this.uid);
        this.machineHostname = DeviceFingerprint.deriveHostname(this.uid);

        try {
            this.tempKey = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            byte[] tk = this.tempKey.getBytes(StandardCharsets.UTF_8);
            this.cosyKey = Crypto.b64(Crypto.rsaEncrypt(tk));

            Map<String, Object> identity = Crypto.orderedMap(
                "name", this.nickname,
                "aid", this.uid,
                "uid", this.uid,
                "yx_uid", "",
                "organization_id", this.orgId,
                "organization_name", this.orgName,
                "user_type", this.userType,
                "security_oauth_token", this.accessToken,
                "refresh_token", this.refreshToken
            );
            byte[] plain = Crypto.jsonSortedCompact(identity).getBytes(StandardCharsets.UTF_8);
            this.info = Crypto.b64(Crypto.aesCbcEncrypt(plain, tk));
        } catch (Exception e) {
            throw new RuntimeException("cosy session init failed", e);
        }
    }

    /** 返回 (payload_b64, date, bearer)。签名覆盖 body 与去 /algo 的 path。 */
    public String[] bearer(String body, String rawUrl) {
        String path = pathOf(rawUrl);
        if (path.startsWith("/algo")) path = path.substring("/algo".length());

        Map<String, Object> payload = Crypto.orderedMap(
            "cosyVersion", COSY_VERSION,
            "ideVersion", "",
            "info", this.info,
            "requestId", UUID.randomUUID().toString(),
            "version", "v1"
        );
        String payloadB64 = Crypto.b64(Crypto.jsonSortedCompact(payload).getBytes(StandardCharsets.UTF_8));
        String date = String.valueOf(System.currentTimeMillis() / 1000L);
        String raw = payloadB64 + "\n" + this.cosyKey + "\n" + date + "\n" + body + "\n" + path;
        String sig = Crypto.md5(raw);
        return new String[]{payloadB64, date, "Bearer COSY." + payloadB64 + "." + sig};
    }

    /** 构造一次推理/模型接口的完整 COSY 签名头。 */
    public Map<String, String> headers(String body, String rawUrl, String modelKey, boolean sse) {
        String[] b = bearer(body, rawUrl);
        String date = b[1];
        String bearer = b[2];
        Map<String, String> h = new HashMap<>();
        h.put("cosy-data-policy", "AGREE");
        h.put("content-type", "application/json");
        h.put("cosy-machinetype", this.machineType);
        h.put("cosy-clienttype", "5");
        h.put("cosy-date", date);
        h.put("cosy-user", this.uid);
        h.put("cosy-key", this.cosyKey);
        h.put("accept", sse ? "text/event-stream" : "application/json");
        h.put("cosy-clientip", "169.254.198.161");
        h.put("authorization", bearer);
        h.put("accept-encoding", "identity");
        h.put("cosy-version", COSY_VERSION);
        h.put("cosy-machineid", this.machineId);
        h.put("cosy-machinetoken", this.machineToken);
        h.put("login-version", "v2");
        h.put("user-agent", "Go-http-client/2.0");
        if (sse) {
            h.put("cache-control", "no-cache");
            h.put("cosy-machineos", "x86_64_linux");
            h.put("cosy-machinehostname", this.machineHostname);
        }
        if (modelKey != null && !modelKey.isEmpty()) {
            h.put("x-model-key", modelKey);
            h.put("x-model-source", "system");
        }
        return h;
    }

    static String pathOf(String rawUrl) {
        String path = rawUrl;
        int p = rawUrl.indexOf("://");
        if (p >= 0) {
            path = rawUrl.substring(p + 3);
            int slash = path.indexOf('/');
            path = slash >= 0 ? path.substring(slash) : "/";
        }
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        return path.isEmpty() ? "/" : path;
    }
}
