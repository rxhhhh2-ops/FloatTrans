package com.sonetto.floattrans;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 翻译引擎：优先走用户自配的 LLM（OpenAI 兼容 /chat/completions），
 * 可选 Google 免 key 接口兜底。全部在后台线程执行。
 */
public final class Translator {

    public interface Callback {
        void onOk(String translated, String engine);

        void onErr(String message);
    }

    private final Prefs prefs;
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> cache = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 240;
        }
    };

    private static Translator instance;

    private Translator(Context c) {
        this.prefs = Prefs.get(c);
    }

    public static synchronized Translator get(Context c) {
        if (instance == null) instance = new Translator(c);
        return instance;
    }

    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    public void translate(final String text, final Callback cb) {
        final String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            main.post(() -> cb.onErr("空文本"));
            return;
        }
        final String key = prefs.targetLang() + "\u0000" + t;
        synchronized (cache) {
            String hit = cache.get(key);
            if (hit != null) {
                final String h = hit;
                main.post(() -> cb.onOk(h, "缓存"));
                return;
            }
        }
        exec.execute(() -> {
            boolean llmReady = !prefs.llmBase().trim().isEmpty() && !prefs.llmModel().trim().isEmpty();
            String result = null;
            String engine = "";
            String err = null;

            if (llmReady) {
                try {
                    result = translateLlm(t);
                    engine = "LLM";
                } catch (Exception e) {
                    err = "LLM失败: " + errText(e);
                    if (!prefs.fallbackGoogle()) {
                        final String f = err;
                        main.post(() -> cb.onErr(f));
                        return;
                    }
                }
            }
            if (result == null && prefs.fallbackGoogle()) {
                try {
                    result = translateMyMemory(t);
                    engine = "MyMemory";
                } catch (Exception e) {
                    String msg = "MyMemory失败: " + errText(e);
                    err = err == null ? msg : err + " | " + msg;
                    try {
                        result = translateGoogle(t);
                        engine = "Google";
                    } catch (Exception e2) {
                        String msg2 = "Google失败: " + errText(e2);
                        err = err + " | " + msg2;
                    }
                }
            }
            if (result == null) {
                final String f = err == null ? "未配置翻译引擎：请填 API 地址与模型，或打开 Google 兜底" : err;
                main.post(() -> cb.onErr(f));
                return;
            }
            final String r = result;
            final String en = engine;
            synchronized (cache) {
                cache.put(key, r);
            }
            main.post(() -> cb.onOk(r, en));
        });
    }

    /** 自配 LLM（OpenAI 兼容接口）。 */
    private String translateLlm(String text) throws Exception {
        String langName = langName(prefs.targetLang());
        String userContent = "请把下面这段文本翻译成" + langName
                + "，自动判断源语言。只输出译文本身，不要任何解释、引号或注释；"
                + "如果原文已经是" + langName + "就原样返回。\n\n" + text;
        return llmCall(userContent);
    }

    /** 直接按给定用户消息调用 LLM（自定义任务，如整屏逐行翻译）。 */
    private String llmCall(String userContent) throws Exception {
        String base = prefs.llmBase().trim();
        String url;
        if (base.endsWith("/chat/completions")) {
            url = base;
        } else if (base.endsWith("/v1")) {
            url = base + "/chat/completions";
        } else {
            url = base + "/v1/chat/completions";
        }
        JSONObject body = new JSONObject();
        body.put("model", prefs.llmModel().trim());
        body.put("temperature", 0.2);
        body.put("max_tokens", 8000);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", "你是一个文本处理助手，严格按用户要求的输出格式执行，不要额外解释。"));
        messages.put(new JSONObject().put("role", "user").put("content", userContent));
        body.put("messages", messages);

        HttpURLConnection conn = open(url, "POST", 120000);
        String key = prefs.llmKey().trim();
        if (!key.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("Content-Type", "application/json");
        writeBody(conn, body.toString());
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code >= 400) throw new IOException("HTTP " + code + " " + clip(resp, 160));

        JSONObject root = new JSONObject(resp);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("响应缺少 choices " + clip(resp, 160));
        }
        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
        String cleaned = clean(content);
        if (cleaned.isEmpty()) throw new IOException("空译文");
        return cleaned;
    }

    /** 只走 LLM 的自定义请求（整屏翻译用，无兜底引擎）。 */
    public void translateRaw(final String userContent, final Callback cb) {
        if (prefs.llmBase().trim().isEmpty() || prefs.llmModel().trim().isEmpty()) {
            main.post(() -> cb.onErr("整屏翻译需要先在设置里配置 LLM"));
            return;
        }
        exec.execute(() -> {
            try {
                String r = llmCall(userContent);
                main.post(() -> cb.onOk(r, "LLM"));
            } catch (Exception e) {
                final String msg = errText(e);
                main.post(() -> cb.onErr(msg));
            }
        });
    }

    /** Google 免 key 接口。 */
    private String translateGoogle(String text) throws Exception {
        String tl = prefs.targetLang();
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                + URLEncoder.encode(tl, "UTF-8") + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8");
        HttpURLConnection conn = open(url, "GET");
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code >= 400) throw new IOException("HTTP " + code + " " + clip(resp, 120));
        JSONArray root = new JSONArray(resp);
        JSONArray parts = root.getJSONArray(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONArray seg = parts.optJSONArray(i);
            if (seg != null && seg.length() > 0 && !seg.isNull(0)) sb.append(seg.getString(0));
        }
        String cleaned = clean(sb.toString());
        if (cleaned.isEmpty()) throw new IOException("空译文");
        return cleaned;
    }

    /** MyMemory 免 key 接口（国内直连可达）。 */
    private String translateMyMemory(String text) throws Exception {
        String tl = prefs.targetLang();
        String url = "https://api.mymemory.translated.net/get?langpair="
                + URLEncoder.encode("Autodetect|" + tl, "UTF-8")
                + "&q=" + URLEncoder.encode(text, "UTF-8");
        HttpURLConnection conn = open(url, "GET");
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code >= 400) throw new IOException("HTTP " + code + " " + clip(resp, 120));
        JSONObject root = new JSONObject(resp);
        JSONObject data = root.optJSONObject("responseData");
        if (data == null) throw new IOException("响应缺 responseData " + clip(resp, 120));
        String out = clean(data.optString("translatedText", ""));
        if (out.isEmpty()) throw new IOException("空译文");
        return out;
    }

    // ---------- helpers ----------

    private static String errText(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.toString() : m;
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        return open(url, method, 30000);
    }

    private HttpURLConnection open(String url, String method, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("User-Agent", "FloatTrans/1.0");
        return conn;
    }

    private void writeBody(HttpURLConnection conn, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(bytes.length);
        OutputStream os = conn.getOutputStream();
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
    }

    private String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        // 去掉整段包裹的引号
        if (t.length() >= 2) {
            char a = t.charAt(0), b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')
                    || (a == '\u201C' && b == '\u201D') || (a == '\u300C' && b == '\u300D')
                    || (a == '\u300E' && b == '\u300F')) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private String langName(String code) {
        switch (code) {
            case "zh-CN":
                return "简体中文";
            case "zh-TW":
                return "繁體中文";
            case "en":
                return "英语";
            case "ja":
                return "日语";
            case "ko":
                return "韩语";
            case "fr":
                return "法语";
            case "de":
                return "德语";
            case "es":
                return "西班牙语";
            case "ru":
                return "俄语";
            default:
                return code;
        }
    }
}
