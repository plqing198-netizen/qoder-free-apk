package com.qoder.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** 模型目录（key -> display_name 等元数据）。 */
public final class ModelCatalog {
    private ModelCatalog() {}

    public static class Model {
        public String key;
        public String displayName;
        public boolean isVl;
        public boolean isReasoning;
        public int maxInputTokens;
    }

    /** 解析 /algo/api/v2/model/list 响应（已 qoder_decode 的 JSON）。 */
    public static List<Model> parse(String json) {
        List<Model> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = null;
            if (root.has("chat")) arr = root.optJSONArray("chat");
            else if (root.has("data")) arr = root.optJSONArray("data");
            else if (root.has("models")) arr = root.optJSONArray("models");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String key = m.optString("key", "");
                if (key.isEmpty()) key = m.optString("id", m.optString("model", ""));
                if (key.isEmpty()) continue;
                if (!m.optBoolean("enable", true)) continue;
                Model mm = new Model();
                mm.key = key;
                mm.displayName = m.optString("display_name", m.optString("name", key));
                mm.isVl = m.optBoolean("is_vl", false);
                mm.isReasoning = m.optBoolean("is_reasoning", false);
                mm.maxInputTokens = m.optInt("max_input_tokens", 180000);
                out.add(mm);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
