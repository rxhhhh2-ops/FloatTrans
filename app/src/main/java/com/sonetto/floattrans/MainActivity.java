package com.sonetto.floattrans;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：服务开关引导 + LLM 引擎配置 + 行为设置。 */
public class MainActivity extends Activity {

    private TextView tvAccStatus;
    private TextView tvOverlayStatus;
    private EditText etBase;
    private EditText etKey;
    private EditText etModel;
    private CheckBox cbFallback;
    private CheckBox cbAutoPopup;
    private CheckBox cbShowOriginal;
    private CheckBox cbBall;
    private Spinner spLang;

    private Translator translator;

    private static final String[] LANG_LABELS = {
            "简体中文", "繁體中文", "英语", "日语", "韩语", "法语", "德语", "西班牙语", "俄语"
    };
    private static final String[] LANG_CODES = {
            "zh-CN", "zh-TW", "en", "ja", "ko", "fr", "de", "es", "ru"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        translator = Translator.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));
        root.setPadding(dp(20), dp(28), dp(20), dp(28));

        TextView title = new TextView(this);
        title.setText("浮译 FloatTrans");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#111827"));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("长按选中文字 → 屏幕上方自动翻译\n悬浮球常驻，可拖动；点一下收起/展开卡片");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(Color.parseColor("#6B7280"));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(6);
        root.addView(subtitle, sLp);

        // ① 服务状态
        root.addView(sectionTitle("① 服务状态"));
        tvAccStatus = statusRow(root, "无障碍服务", "去开启", v -> {
                try {
                    new java.io.File(getFilesDir(), "disabled").delete();
                    Prefs.get(this).edit().putBoolean("stopped", false).apply();
                } catch (Throwable ignored) {
                }
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "请在列表中开启「浮译划词翻译」", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "无法打开设置：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        tvOverlayStatus = statusRow(root, "悬浮窗权限", "去授权", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开设置：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // ② 引擎
        root.addView(sectionTitle("② 翻译引擎 · 自配 LLM API"));
        TextView hint = new TextView(this);
        hint.setText("支持任意 OpenAI 兼容接口（DeepSeek / 智谱 / Kimi / 中转站等）。\n地址结尾带 /v1 或 /chat/completions 都可以，Key 可留空。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(Color.parseColor("#6B7280"));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(8);
        root.addView(hint, hLp);

        etBase = field("API 地址，例如 https://api.deepseek.com/v1", InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(etBase);
        etKey = field("API Key（可留空）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(etKey);
        etModel = field("模型名，例如 deepseek-chat / gpt-4o-mini", InputType.TYPE_CLASS_TEXT);
        root.addView(etModel);

        cbFallback = check("LLM 失败时用免费接口兜底（MyMemory/Google 自动切换）", true);
        root.addView(cbFallback);

        Button btnTest = button("测试翻译（Hello world → 中文）");
        btnTest.setOnClickListener(v -> doTest());
        root.addView(btnTest);

        // ③ 行为
        root.addView(sectionTitle("③ 行为"));
        cbAutoPopup = check("长按选中后自动弹出翻译", true);
        root.addView(cbAutoPopup);
        cbShowOriginal = check("卡片里显示原文", true);
        root.addView(cbShowOriginal);

        cbBall = check("在屏幕上显示悬浮球（关掉后游戏/视频里不挡视线）", true);
        cbBall.setOnCheckedChangeListener((b, checked) -> {
            Prefs.get(this).edit().putBoolean("ball_hidden", !checked).apply();
            TranslateService svc = TranslateService.getInstance();
            if (svc != null) {
                svc.setBallEnabled(checked);
            } else if (checked) {
                // 服务没在跑（例如刚点过「完全停止」）：只改这个开关不会出现悬浮球
                try {
                    new java.io.File(getFilesDir(), "disabled").delete();
                } catch (Throwable ignored) {
                }
                Prefs.get(this).edit().putBoolean("stopped", false).apply();
                Toast.makeText(this, "悬浮球需要无障碍服务：请点上方「去开启」", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(cbBall);

        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView langLabel = new TextView(this);
        langLabel.setText("目标语言");
        langLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        langLabel.setTextColor(Color.parseColor("#111827"));
        langRow.addView(langLabel);
        spLang = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, LANG_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLang.setAdapter(adapter);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spLp.leftMargin = dp(16);
        langRow.addView(spLang, spLp);
        LinearLayout.LayoutParams lrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrLp.topMargin = dp(10);
        root.addView(langRow, lrLp);

        // 保存
        Button btnSave = button("保存设置");
        btnSave.setOnClickListener(v -> save());
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(18);
        root.addView(btnSave, bLp);

        // ④ 关于
        root.addView(sectionTitle("④ 关于"));
        TextView about = new TextView(this);
        about.setText("浮译 v" + versionName() + "\n· 通过无障碍服务读取长按选中的文字，仅此文本会被发送到你配置的翻译接口。\n"
                + "· 不收集任何数据，不需要账号。\n"
                + "· 网页等不产生选区事件的应用，请「复制 → 长按悬浮球」，或用系统选中菜单里的「浮译」。");
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        about.setTextColor(Color.parseColor("#6B7280"));
        root.addView(about);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        loadPrefs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 从悬浮球菜单「设置」再次进入时，处理中状态可能已变化
        refreshStatus();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- UI helpers ----------

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#111827"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView statusRow(LinearLayout parent, String label, String action, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(Color.parseColor("#374151"));
        row.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btn = new Button(this);
        btn.setText(action);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setAllCaps(false);
        btn.setOnClickListener(click);
        row.addView(btn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(row, lp);
        status.setText(label + "：检测中…");
        return status;
    }

    private EditText field(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setBackgroundColor(Color.WHITE);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        et.setLayoutParams(lp);
        return et;
    }

    private CheckBox check(String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cb.setTextColor(Color.parseColor("#374151"));
        cb.setChecked(checked);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        cb.setLayoutParams(lp);
        return cb;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------- 状态 / 读写 ----------

    private void refreshStatus() {
        boolean acc = isAccessibilityEnabled() || TranslateService.getInstance() != null;
        boolean overlay = Settings.canDrawOverlays(this) || Prefs.get(this).overlayOk();
        tvAccStatus.setText("无障碍服务：" + (acc ? "已开启 ✓" : "未开启"));
        tvAccStatus.setTextColor(Color.parseColor(acc ? "#1E8E5A" : "#B45309"));
        tvOverlayStatus.setText("悬浮窗权限：" + (overlay ? "已授予 ✓" : "未授予"));
        tvOverlayStatus.setTextColor(Color.parseColor(overlay ? "#1E8E5A" : "#B45309"));
    }

    private boolean isAccessibilityEnabled() {
        ComponentName cn = new ComponentName(this, TranslateService.class);
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.toLowerCase().contains(cn.flattenToString().toLowerCase());
    }

    private void loadPrefs() {
        Prefs p = Prefs.get(this);
        etBase.setText(p.llmBase());
        etKey.setText(p.llmKey());
        etModel.setText(p.llmModel());
        cbFallback.setChecked(p.fallbackGoogle());
        cbAutoPopup.setChecked(p.autoPopup());
        cbShowOriginal.setChecked(p.showOriginal());
        cbBall.setChecked(!p.ballHidden());
        String lang = p.targetLang();
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equals(lang)) spLang.setSelection(i);
        }
    }

    private void save() {
        int idx = spLang.getSelectedItemPosition();
        String lang = LANG_CODES[Math.max(0, Math.min(idx, LANG_CODES.length - 1))];
        Prefs.get(this).edit()
                .putString("llm_base", etBase.getText().toString().trim())
                .putString("llm_key", etKey.getText().toString().trim())
                .putString("llm_model", etModel.getText().toString().trim())
                .putString("target_lang", lang)
                .putBoolean("fallback_google", cbFallback.isChecked())
                .putBoolean("auto_popup", cbAutoPopup.isChecked())
                .putBoolean("show_original", cbShowOriginal.isChecked())
                .putBoolean("ball_hidden", !cbBall.isChecked())
                .apply();
        translator.clearCache();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private void doTest() {
        save();
        Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show();
        translator.translate("The quick brown fox jumps over the lazy dog.", new Translator.Callback() {
            @Override
            public void onOk(String translated, String engine) {
                Toast.makeText(MainActivity.this,
                        "[" + engine + "] " + translated, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onErr(String message) {
                Toast.makeText(MainActivity.this, "测试失败：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
