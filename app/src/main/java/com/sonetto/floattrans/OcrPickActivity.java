package com.sonetto.floattrans;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/** 相册选图识字：选一张图 → 本地 OCR → 可一键翻译。 */
public class OcrPickActivity extends Activity {

    private TextView tvTag;
    private TextView tvOriginal;
    private TextView tvResult;
    private String recognized = "";
    private TextRecognizer ocr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ocr = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        buildUi();
        pickImage();
    }

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选择要识别的图片"), 1);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开相册：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        Uri uri = data.getData();
        try {
            Bitmap bmp = BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
            if (bmp == null) {
                tvResult.setText("图片读取失败");
                return;
            }
            tvTag.setText("浮译 · 识别中");
            tvResult.setText("正在识别图片文字…");
            ocr.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(result -> {
                        recognized = result.getText() == null ? "" : result.getText().trim();
                        tvOriginal.setText(recognized);
                        if (recognized.isEmpty()) {
                            tvTag.setText("浮译 · 识字");
                            tvResult.setText("没有识别到文字");
                        } else {
                            tvTag.setText("浮译 · 识字");
                            tvResult.setText("识别完成，点「翻译」出译文");
                        }
                    })
                    .addOnFailureListener(e -> {
                        tvTag.setText("浮译 · 出错");
                        tvResult.setText("识别失败：" + e.getMessage());
                    });
        } catch (Exception e) {
            tvResult.setText("图片读取失败：" + e.getMessage());
        }
    }

    private void doTranslate() {
        if (recognized.isEmpty()) return;
        tvTag.setText("浮译 · 翻译中");
        tvResult.setText("翻译中…");
        Translator.get(this).translate(recognized, new Translator.Callback() {
            @Override
            public void onOk(String translated, String engine) {
                tvTag.setText("浮译 · " + engine);
                tvResult.setText(translated);
            }

            @Override
            public void onErr(String message) {
                tvTag.setText("浮译 · 出错");
                tvResult.setText("翻译失败：" + message);
            }
        });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#66000000"));
        root.setGravity(Gravity.CENTER);
        root.setOnClickListener(v -> finish());
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F9FAFB"));
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(14), dp(18), dp(16));
        card.setOnClickListener(v -> {
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        tvTag = new TextView(this);
        tvTag.setText("浮译 · 相册识字");
        tvTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvTag.setTextColor(Color.parseColor("#34A78F"));
        head.addView(tvTag);

        TextView btnTranslate = smallButton("翻译");
        btnTranslate.setOnClickListener(v -> doTranslate());
        TextView btnCopy = smallButton("复制");
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("text", recognized));
                Toast.makeText(this, "已复制识别文字", Toast.LENGTH_SHORT).show();
            }
        });
        TextView btnClose = smallButton("关闭");
        btnClose.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(6);
        head.addView(btnTranslate, blp);
        head.addView(btnCopy, blp);
        head.addView(btnClose, blp);
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvOriginal = new TextView(this);
        tvOriginal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvOriginal.setTextColor(Color.parseColor("#6B7280"));
        tvOriginal.setMaxLines(6);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(10);
        card.addView(tvOriginal, olp);

        tvResult = new TextView(this);
        tvResult.setText("正在打开相册…");
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvResult.setTextColor(Color.parseColor("#111827"));
        tvResult.setTypeface(Typeface.DEFAULT_BOLD);
        tvResult.setMaxLines(14);
        tvResult.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        card.addView(tvResult, rlp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(card, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(0);
        root.addView(scroll, slp);
        setContentView(root);
    }

    private TextView smallButton(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.parseColor("#374151"));
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
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
