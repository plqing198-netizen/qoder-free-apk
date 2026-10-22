package com.qoder.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** 账号凭据与配置持久化（SharedPreferences）。 */
public final class Store {
    private static final String PREF = "qoder_prefs";
    private static final String KEY_ACCOUNT = "account";
    private static final String KEY_MODELS = "models_cache";
    private static final String KEY_GATEWAY_PORT = "gateway_port";

    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void saveAccount(Context c, JSONObject account) {
        prefs(c).edit().putString(KEY_ACCOUNT, account.toString()).apply();
    }

    public static JSONObject account(Context c) {
        String s = prefs(c).getString(KEY_ACCOUNT, null);
        if (s == null) return null;
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    public static String accountStr(Context c) {
        return prefs(c).getString(KEY_ACCOUNT, null);
    }

    public static String token(Context c) {
        JSONObject a = account(c);
        return a == null ? "" : a.optString("access_token", "");
    }

    public static String uid(Context c) {
        JSONObject a = account(c);
        return a == null ? "" : a.optString("uid", "");
    }

    public static void saveModels(Context c, String json) {
        prefs(c).edit().putString(KEY_MODELS, json).apply();
    }

    public static String models(Context c) {
        return prefs(c).getString(KEY_MODELS, "[]");
    }

    public static int gatewayPort(Context c) {
        return prefs(c).getInt(KEY_GATEWAY_PORT, 9253);
    }

    public static void setGatewayPort(Context c, int port) {
        prefs(c).edit().putInt(KEY_GATEWAY_PORT, port).apply();
    }
}
