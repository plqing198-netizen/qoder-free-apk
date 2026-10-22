package com.qoder.app;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class Crypto {
    private static final String QODER_CUSTOM_ALPHABET =
            "_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!";
    private static final String QODER_STD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static final String SERVER_PUB_PEM =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDA8iMH5c02LilrsERw9t6Pv5Nc" +
            "4k6Pz1EaDicBMpdpxKduSZu5OANqUq8er4GM95omAGIOPOh+Nx0spthYA2BqGz+l" +
            "6HRkPJ7S236FZz73In/KVuLnwI8JJ2CbuJap8kvheCCZpmAWpb/cPx/3Vr/J6I17" +
            "XcW+ML9FoCI6AOvOzwIDAQAB";

    private static PublicKey cachedPublicKey;

    private Crypto() {}

    public static String qoderEncode(byte[] plain) {
        String std = Base64.getEncoder().encodeToString(plain);
        int n = std.length();
        int a = n / 3;
        String rearranged = std.substring(n - a) + std.substring(a, n - a) + std.substring(0, a);
        StringBuilder sb = new StringBuilder(rearranged.length());
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c == '=') { sb.append('$'); continue; }
            int idx = QODER_STD_ALPHABET.indexOf(c);
            sb.append(idx >= 0 ? QODER_CUSTOM_ALPHABET.charAt(idx) : c);
        }
        return sb.toString();
    }

    public static byte[] qoderDecode(String encoded) {
        StringBuilder std = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '$') { std.append('='); continue; }
            int idx = QODER_CUSTOM_ALPHABET.indexOf(c);
            std.append(idx >= 0 ? QODER_STD_ALPHABET.charAt(idx) : c);
        }
        String s = std.toString();
        int n = s.length();
        int a = n / 3;
        String r1 = s.substring(0, a);
        String r2 = s.substring(a, n - a);
        String r3 = s.substring(n - a);
        return Base64.getDecoder().decode(r3 + r2 + r1);
    }

    public static byte[] aesCbcEncrypt(byte[] plain, byte[] key) throws Exception {
        if (key.length != 16) throw new IllegalArgumentException("aes key must be 16 bytes");
        SecretKeySpec spec = new SecretKeySpec(key, "AES");
        IvParameterSpec iv = new IvParameterSpec(key);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, spec, iv);
        return cipher.doFinal(plain);
    }

    public static byte[] rsaEncrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, getServerPublicKey());
        return cipher.doFinal(plain);
    }

    private static synchronized PublicKey getServerPublicKey() throws Exception {
        if (cachedPublicKey != null) return cachedPublicKey;
        byte[] der = Base64.getDecoder().decode(SERVER_PUB_PEM);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        cachedPublicKey = KeyFactory.getInstance("RSA").generatePublic(spec);
        return cachedPublicKey;
    }

    public static String md5(String s) { return md5Hex(s.getBytes(StandardCharsets.UTF_8)); }

    public static String md5Hex(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static byte[] sha256Digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String sha512Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-512").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    public static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String jsonSortedCompact(Map<String, Object> mapping) {
        List<String> keys = new ArrayList<>(mapping.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : keys) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonString(k)).append(':').append(jsonValue(mapping.get(k)));
        }
        sb.append('}');
        return sb.toString();
    }

    public static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String jsonValue(Object v) {
        if (v == null) return "\"\"";
        if (v instanceof String) return jsonString((String) v);
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Number) return v.toString();
        return jsonString(String.valueOf(v));
    }
}
