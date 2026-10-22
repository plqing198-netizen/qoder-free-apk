package com.qoder.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 构造 agent_chat_generation 上游请求体（逆向自 build_qoder_body）。
 * 以官方 baseprompt.json 为骨架，动态覆写 id/时间戳/模型配置/会话。
 */
public final class ChatBody {
    private ChatBody() {}

    public static String build(JSONArray messages, String modelKey, String modelDisplay,
                               boolean isVl, boolean isReasoning, int maxInputTokens,
                               String userType, int maxTokens, String systemText) throws Exception {
        JSONObject body = new JSONObject();

        String nid = UUID.randomUUID().toString();
        body.put("request_id", nid);
        body.put("request_set_id", UUID.randomUUID().toString());
        body.put("chat_record_id", nid);
        body.put("stream", true);
        body.put("chat_task", "FREE_INPUT");
        body.put("session_id", UUID.randomUUID().toString());
        body.put("code_language", "");
        body.put("source", 1);
        body.put("version", "3");
        body.put("chat_prompt", "");
        body.put("session_type", "qodercli");
        body.put("agent_id", "agent_common");
        body.put("task_id", "common");
        body.put("aliyun_user_type", userType == null || userType.isEmpty()
                ? "personal_professional_trial" : userType);

        // messages：system + 真实会话
        JSONArray msgs = new JSONArray();
        if (systemText != null && !systemText.isEmpty()) {
            JSONObject s = new JSONObject();
            s.put("role", "system");
            s.put("content", systemText);
            msgs.put(s);
        }
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                msgs.put(messages.optJSONObject(i));
            }
        }
        body.put("messages", msgs);

        // 最新用户提示词
        String prompt = "";
        for (int i = msgs.length() - 1; i >= 0; i--) {
            JSONObject m = msgs.optJSONObject(i);
            if (m != null && "user".equals(m.optString("role"))) {
                Object c = m.opt("content");
                if (c instanceof String) prompt = (String) c;
                else if (c instanceof JSONArray) {
                    JSONArray arr = (JSONArray) c;
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject p = arr.optJSONObject(j);
                        if (p != null && "text".equals(p.optString("type"))) {
                            sb.append(p.optString("text"));
                        }
                    }
                    prompt = sb.toString();
                }
                break;
            }
        }

        // model_config
        JSONObject mc = new JSONObject();
        mc.put("key", modelKey == null ? "auto" : modelKey);
        mc.put("display_name", modelDisplay == null ? modelKey : modelDisplay);
        mc.put("model", "");
        mc.put("format", "openai");
        mc.put("is_vl", isVl);
        mc.put("is_reasoning", isReasoning);
        mc.put("api_key", "");
        mc.put("url", "");
        mc.put("source", "system");
        mc.put("max_input_tokens", maxInputTokens > 0 ? maxInputTokens : 180000);
        body.put("model_config", mc);

        // chat_context
        JSONObject cc = new JSONObject();
        cc.put("chatPrompt", "");
        JSONObject extra = new JSONObject();
        extra.put("context", new JSONArray());
        extra.put("modelConfig", new JSONObject().put("is_reasoning", isReasoning)
                .put("key", modelKey));
        extra.put("originalContent", new JSONObject().put("type", "text").put("text", prompt));
        cc.put("extra", extra);
        cc.put("features", new JSONArray());
        cc.put("imageUrls", JSONObject.NULL);
        cc.put("text", new JSONObject().put("type", "text").put("text", prompt));
        body.put("chat_context", cc);
        body.put("image_urls", JSONObject.NULL);

        // parameters
        JSONObject params = new JSONObject();
        params.put("max_tokens", maxTokens > 0 ? maxTokens : 32768);
        body.put("parameters", params);

        // tools 置空（不泄漏桌面端 agent 工具）
        body.put("tools", new JSONArray());

        // business
        JSONObject biz = new JSONObject();
        biz.put("product", "cli");
        biz.put("version", "0.1.43");
        biz.put("type", "agent");
        biz.put("id", UUID.randomUUID().toString());
        biz.put("name", prompt.length() > 30 ? prompt.substring(0, 30) : (prompt.isEmpty() ? "chat" : prompt));
        biz.put("begin_at", System.currentTimeMillis());
        biz.put("stage", "start");
        body.put("business", biz);

        // is_reply / is_retry
        body.put("is_reply", true);
        body.put("is_retry", false);

        return body.toString();
    }

    /** 最终出站 body：json -> qoder_encode。 */
    public static String encode(String json) {
        return Crypto.qoderEncode(json.getBytes(StandardCharsets.UTF_8));
    }
}
