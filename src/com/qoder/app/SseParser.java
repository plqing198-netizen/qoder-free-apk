package com.qoder.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 上游 SSE 信封解包（逆向自 iter_inner_sse）。
 * 上游帧：data:{"headers":...,"body":"<内层OpenAI chunk JSON字符串>","statusCodeValue":200}
 * 规则：statusCodeValue != 200 -> 错误；body=="[DONE]" -> 结束；内层才是标准 chunk。
 */
public final class SseParser {
    private SseParser() {}

    public static class Chunk {
        public String content = "";
        public String reasoning = "";
        public String model = "";
        public String id = "";
        public String finishReason = null;
        public JSONObject usage = null;
        public boolean done = false;
        public String error = null;
    }

    /** 解析单条 data 帧（已去掉 "data:" 前缀）。返回 null 表示忽略。 */
    public static Chunk parse(String data) {
        try {
            JSONObject outer = new JSONObject(data);
            Object status = outer.opt("statusCodeValue");
            if (status != null && !status.equals(200) && !status.equals("200")) {
                Chunk c = new Chunk();
                Object b = outer.opt("body");
                c.error = b instanceof String ? (String) b : outer.toString();
                return c;
            }
            String body = outer.optString("body", "");
            if (body.isEmpty()) return null;
            if (body.equals("[DONE]")) {
                Chunk c = new Chunk();
                c.done = true;
                return c;
            }
            JSONObject inner = new JSONObject(body);
            Chunk c = new Chunk();
            c.model = inner.optString("model", "");
            c.id = inner.optString("id", "");
            if (!inner.isNull("usage")) c.usage = inner.optJSONObject("usage");
            JSONArray choices = inner.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject ch0 = choices.optJSONObject(0);
                if (ch0 != null) {
                    String fr = ch0.optString("finish_reason", "");
                    if (!fr.isEmpty()) c.finishReason = fr;
                    JSONObject delta = ch0.optJSONObject("delta");
                    if (delta != null) {
                        if (!delta.isNull("content")) {
                            Object content = delta.opt("content");
                            if (content instanceof String) c.content = (String) content;
                            else if (content instanceof JSONArray) {
                                StringBuilder sb = new StringBuilder();
                                JSONArray arr = (JSONArray) content;
                                for (int i = 0; i < arr.length(); i++) {
                                    Object p = arr.opt(i);
                                    if (p instanceof String) sb.append((String) p);
                                    else if (p instanceof JSONObject) sb.append(((JSONObject) p).optString("text", ""));
                                }
                                c.content = sb.toString();
                            }
                        }
                        if (!delta.isNull("reasoning_content")) {
                            c.reasoning = delta.optString("reasoning_content", "");
                        }
                    }
                }
            }
            return c;
        } catch (Exception e) {
            return null;
        }
    }
}
