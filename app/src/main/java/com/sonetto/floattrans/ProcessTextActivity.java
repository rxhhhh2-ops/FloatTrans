package com.sonetto.floattrans;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 系统选中菜单里的「浮译」按钮：长按选中文字 → 点「浮译」→ 翻译。
 * 若无障碍服务在跑，直接交给顶部悬浮卡；否则本页弹出结果卡。
 */
public class ProcessTextActivity extends Activity {

    private TextView tvResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(true);

        CharSequence cs = null;
        Intent intent = getIntent();
        if (intent != null) {
            cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        }
        final String src = cs == null ? "" : cs.toString().trim();
        if (src.isEmpty()) {
            finish();
            return;
        }

        // 优先交给悬浮卡
        TranslateService svc = TranslateService.getInstance();
        if (svc != null) {
            svc.translateNow(src);
            finish();
            return;
        }

        buildUi(src);

        Translator.get(this).translate(src, new Translator.Callback() {
            @Override
            public void onOk(String translated, String engine) {
                if (tvResult != null) tvResult.setText(translated);
            }

            @Override
            public void onErr(String message) {
                if (tvResult != null) tvResult.setText("翻译失败：" + message);
            }
        });
    }

    private void buildUi(String src) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#66000000"));
        root.setGravity(Gravity.CENTER);
        root.setOnClickListener(v -> finish());
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F9FAFB"));
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(14), dp(18), dp(16));
        card.setOnClickListener(v -> {
        });

        TextView tag = new TextView(this);
        tag.setText("浮译");
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tag.setTextColor(Color.parseColor("#34A78F"));
        card.addView(tag);

        TextView tvOriginal = new TextView(this);
        tvOriginal.setText(src);
        tvOriginal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvOriginal.setTextColor(Color.parseColor("#6B7280"));
        tvOriginal.setMaxLines(4);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        oLp.topMargin = dp(8);
        card.addView(tvOriginal, oLp);

        tvResult = new TextView(this);
        tvResult.setText("翻译中…");
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvResult.setTextColor(Color.parseColor("#111827"));
        tvResult.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(10);
        card.addView(tvResult, rLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        TextView btnCopy = smallButton("复制译文");
        btnCopy.setOnClickListener(v -> {
            if (tvResult != null) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("translation", tvResult.getText()));
                    Toast.makeText(this, "译文已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });
        TextView btnClose = smallButton("关闭");
        btnClose.setOnClickListener(v -> finish());
        row.addView(btnCopy);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.leftMargin = dp(8);
        row.addView(btnClose, cLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);
        card.addView(row, rowLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(card, cardLp);
        setContentView(root);
    }

    private TextView smallButton(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.parseColor("#374151"));
        tv.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EDF0F4"));
        bg.setCornerRadius(dp(12));
        tv.setBackground(bg);
        return tv;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
