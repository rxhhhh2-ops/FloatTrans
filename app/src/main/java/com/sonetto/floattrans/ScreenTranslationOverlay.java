package com.sonetto.floattrans;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/** 整屏翻译覆盖层：把译文块按原位置盖在原文上。点任意空白或「×」关闭。 */
public final class ScreenTranslationOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final int screenW;
    private final int screenH;

    private FrameLayout root;
    private boolean showing = false;
    private Runnable onClose;

    public ScreenTranslationOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        params = new WindowManager.LayoutParams(
                screenW, screenH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = 0;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // 截图坐标从屏幕左上角算起（含状态栏），窗口也必须从 0 开始布局，否则译文会整体下移一个状态栏
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
    }

    public boolean isShowing() {
        return showing;
    }

    /** boxes 为截图坐标系（像素），texts 与之一一对应；空字符串表示该块不显示。 */
    public void show(List<Rect> boxes, List<String> texts, Runnable onCloseCb) {
        dismiss();
        this.onClose = onCloseCb;
        root = new FrameLayout(ctx);
        root.setOnClickListener(v -> doClose());

        int shown = 0;
        for (int i = 0; i < boxes.size() && i < texts.size(); i++) {
            Rect b = boxes.get(i);
            String t = texts.get(i);
            if (b == null || t == null || t.trim().isEmpty()) continue;
            if (b.width() < 8 || b.height() < 8) continue;
            TextView tv = new TextView(ctx);
            tv.setText(t);
            int fs = Math.max((int) (b.height() * 0.60f), 24);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.min(fs, 46));
            tv.setTextColor(Color.parseColor("#111827"));
            GradientDrawable bg = new GradientDrawable();
            // 不透明底色：完全盖住原文那一行，避免原文从半透明块里透出来
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(6));
            tv.setBackground(bg);
            tv.setPadding(6, 2, 6, 2);
            tv.setMaxLines(3);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setMinHeight(b.height() + dp(2));
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int left = Math.max(0, Math.min(b.left - dp(2), screenW - 16));
            int top = Math.max(0, Math.min(b.top - dp(1), screenH - 16));
            int w = Math.max(32, Math.min(b.width() + dp(4), screenW - left));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    w, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = left;
            lp.topMargin = top;
            root.addView(tv, lp);
            shown++;
        }

        if (shown == 0) {
            TextView none = new TextView(ctx);
            none.setText("整屏翻译：没有可显示的译文块\n点屏幕关闭");
            none.setTextColor(Color.WHITE);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#E620222B"));
            bg.setCornerRadius(40f);
            none.setBackground(bg);
            none.setPadding(40, 20, 40, 20);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            root.addView(none, lp);
        }

        TextView close = new TextView(ctx);
        close.setText("×");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setShape(GradientDrawable.OVAL);
        cbg.setColor(Color.parseColor("#E620222B"));
        close.setBackground(cbg);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> doClose());
        int csize = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 40, ctx.getResources().getDisplayMetrics());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(csize, csize);
        clp.gravity = Gravity.TOP | Gravity.RIGHT;
        clp.topMargin = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 90, ctx.getResources().getDisplayMetrics());
        clp.rightMargin = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 14, ctx.getResources().getDisplayMetrics());
        root.addView(close, clp);

        try {
            wm.addView(root, params);
            showing = true;
        } catch (Exception e) {
            root = null;
        }
    }

    private void doClose() {
        dismiss();
        if (onClose != null) {
            Runnable cb = onClose;
            onClose = null;
            cb.run();
        }
    }

    public void dismiss() {
        if (!showing) return;
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
        showing = false;
        root = null;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}
