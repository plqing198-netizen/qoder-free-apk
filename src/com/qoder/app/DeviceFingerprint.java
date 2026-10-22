package com.qoder.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 设备指纹稳定派生（逆向自 qoder_fingerprint.py）。
 * machineId 由 uid 稳定派生（带连字符 UUIDv4 形状）。
 */
public final class DeviceFingerprint {
    private DeviceFingerprint() {}

    public static String deriveId(String uid, String salt) {
        String seed = salt + ":" + (uid == null || uid.isEmpty() ? "anonymous" : uid);
        String h = Crypto.md5(seed);
        String hh = h.substring(0, 12) + "4" + h.substring(13, 16) + "a" + h.substring(17);
        return hh.substring(0, 8) + "-" + hh.substring(8, 12) + "-" + hh.substring(12, 16)
                + "-" + hh.substring(16, 20) + "-" + hh.substring(20, 32);
    }

    public static String deriveMachineType(String uid) {
        return deriveId(uid, "machinetype").replace("-", "").substring(0, 18);
    }

    public static String deriveMachineToken(String uid) {
        // base64url(SHA512("machinetoken:"+uid).digest()).rstrip("=")[:43]
        String seed = "machinetoken:" + (uid == null || uid.isEmpty() ? "anonymous" : uid);
        try {
            byte[] d = MessageDigest.getInstance("SHA-512").digest(seed.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d).substring(0, 43);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String deriveHostname(String uid) {
        String h = Crypto.md5("hostname:" + (uid == null || uid.isEmpty() ? "anonymous" : uid));
        return "DESKTOP-" + h.substring(0, 7).toUpperCase();
    }
}
