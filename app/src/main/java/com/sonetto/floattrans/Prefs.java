package com.sonetto.floattrans;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量偏好封装。 */
public final class Prefs {
    private static final String NAME = "floattrans";
    private static Prefs instance;
    private final SharedPreferences sp;

    private Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context c) {
        if (instance == null) instance = new Prefs(c);
        return instance;
    }

    public SharedPreferences.Editor edit() {
        return sp.edit();
    }

    public String llmBase() {
        return sp.getString("llm_base", "");
    }

    public String llmKey() {
        return sp.getString("llm_key", "");
    }

    public String llmModel() {
        return sp.getString("llm_model", "");
    }

    public String targetLang() {
        return sp.getString("target_lang", "zh-CN");
    }

    public boolean fallbackGoogle() {
        return sp.getBoolean("fallback_google", true);
    }

    public boolean autoPopup() {
        return sp.getBoolean("auto_popup", true);
    }

    public boolean showOriginal() {
        return sp.getBoolean("show_original", true);
    }

    /** 用户手动隐藏了悬浮球。 */
    public boolean ballHidden() {
        return sp.getBoolean("ball_hidden", false);
    }

    /** 用户点过「完全停止」。 */
    public boolean stopped() {
        return sp.getBoolean("stopped", false);
    }

    /** 悬浮球实际添加成功过（部分 ROM 读不到系统设置，用事实说话）。 */
    public boolean overlayOk() {
        return sp.getBoolean("overlay_ok", false);
    }
}
