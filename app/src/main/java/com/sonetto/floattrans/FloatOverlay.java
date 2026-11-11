package com.sonetto.floattrans;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 悬浮球 + 悬浮翻译卡片。 */
public final class FloatOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private final Prefs prefs;
    private final int screenWidth;
    private final int screenHeight;
    private final int statusBarHeight;

    private FrameLayout ball;
    private LinearLayout card;
    private TextView tvTag;
    private TextView tvOriginal;
    private TextView tvResult;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams cardParams;

    private boolean ballShown = false;
    private boolean cardShown = false;
    private boolean destroyed = false;
    /** 用户是否希望显示悬浮球（隐藏/游戏模式/截图期间为 false）。 */
    private boolean ballWanted;
    /** 因为弹出卡片而临时收起的球，收起卡片后要还原。 */
    private boolean ballSuspended = false;
    private String lastOriginal = "";
    private String lastResult = "";

    public interface ClipListener {
        void onText(String text);
    }

    private ClipListener clipListener;

    public void setClipListener(ClipListener l) {
        this.clipListener = l;
    }

    public interface TranslateListener {
        void onTranslate(String text);
    }

    private TranslateListener translateListener;

    public void setTranslateListener(TranslateListener l) {
        this.translateListener = l;
    }

    public interface MenuListener {
        void onScreenTranslate();

        void onScreenOcr();

        void onImageOcr();

        void onHideBall();

        void onStopService();

        void onSettings();
    }

    private MenuListener menuListener;

    public void setMenuListener(MenuListener l) {
        this.menuListener = l;
    }

    private LinearLayout menu;
    private WindowManager.LayoutParams menuParams;
    private boolean menuShown = false;

    public FloatOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = Prefs.get(ctx);
        this.ballWanted = !prefs.ballHidden();
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        this.screenWidth = dm.widthPixels;
        this.screenHeight = dm.heightPixels;
        int res = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        this.statusBarHeight = res > 0 ? ctx.getResources().getDimensionPixelSize(res) : dp(24);
        cardParams = new WindowManager.LayoutParams(
                screenWidth - dp(24),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        cardParams.gravity = Gravity.TOP | Gravity.LEFT;
        cardParams.x = dp(12);
        cardParams.y = statusBarHeight + dp(10);
        buildBall();
        buildCard();
        buildMenu();
    }

    // ---------- 公开接口 ----------

    /** 常驻悬浮球。 */
    public void showBall() {
        if (ballShown || destroyed || cardShown || !ballWanted) return;
        try {
            wm.addView(ball, ballParams);
            ballShown = true;
            Prefs.get(ctx).edit().putBoolean("overlay_ok", true).apply();
        } catch (Exception e) {
            Toast.makeText(ctx, "悬浮窗权限未授予，无法显示悬浮球", Toast.LENGTH_LONG).show();
        }
    }

    /** 临时隐藏/恢复悬浮球（截图前避免把自己截进去）。 */
    public void setBallVisible(boolean visible) {
        ballWanted = visible;
        if (visible) {
            showBall();
        } else if (ballShown) {
            try {
                wm.removeView(ball);
            } catch (Exception ignored) {
            }
            ballShown = false;
        }
    }

    public void showLoading(String src) {
        if (destroyed) return;
        lastOriginal = src;
        lastResult = "";
        tvTag.setText("浮译 · 识别中");
        bindTexts(src, "翻译中…");
        expand();
    }

    public void showTranslation(String src, String dst, String engine) {
        if (destroyed) return;
        lastOriginal = src;
        lastResult = dst;
        tvTag.setText("浮译 · " + engine);
        bindTexts(src, dst);
        expand();
    }

    public void showError(String src, String err) {
        if (destroyed) return;
        lastOriginal = src;
        lastResult = "";
        tvTag.setText("浮译 · 出错");
        bindTexts(src, "翻译失败：" + err);
        expand();
    }

    /** 识字结果卡片。error != null 时显示错误。 */
    public void showOcrResult(String text, String error) {
        if (destroyed) return;
        lastOriginal = "";
        if (error != null) {
            lastResult = "";
            tvTag.setText("浮译 · 出错");
            bindTexts("", error);
        } else {
            lastResult = text;
            tvTag.setText("浮译 · 识字");
            bindTexts("", text);
        }
        expand();
    }

    /** 收起卡片，只留悬浮球。 */
    public void collapse() {
        if (!cardShown) return;
        try {
            wm.removeView(card);
        } catch (Exception ignored) {
        }
        cardShown = false;
        if (ballSuspended) {
            ballSuspended = false;
            if (!destroyed && ballWanted) showBall();
        }
    }

    public void destroy() {
        destroyed = true;
        collapse();
        hideMenu();
        if (ballShown) {
            try {
                wm.removeView(ball);
            } catch (Exception ignored) {
            }
            ballShown = false;
        }
    }

    // ---------- 内部 ----------

    private void bindTexts(String src, String dst) {
        if (prefs.showOriginal() && src != null && !src.isEmpty()) {
            tvOriginal.setVisibility(View.VISIBLE);
            tvOriginal.setText(src);
        } else {
            tvOriginal.setVisibility(View.GONE);
        }
        tvResult.setText(dst);
    }

    private void expand() {
        if (cardShown || destroyed) return;
        // 卡片会盖住悬浮球（只露出一条边），展开时先把球收起来，收起卡片时再自动还原
        if (ballShown) {
            try {
                wm.removeView(ball);
            } catch (Exception ignored) {
            }
            ballShown = false;
            ballSuspended = true;
        }
        try {
            wm.addView(card, cardParams);
            cardShown = true;
        } catch (Exception e) {
            Toast.makeText(ctx, "悬浮窗权限未授予，无法显示翻译卡片", Toast.LENGTH_LONG).show();
        }
    }

    private void buildBall() {
        ball = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#E620222B"));
        ball.setBackground(bg);

        TextView tv = new TextView(ctx);
        tv.setText("译");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setGravity(Gravity.CENTER);
        ball.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ballParams = new WindowManager.LayoutParams(dp(52), dp(52),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.LEFT;
        ballParams.x = screenWidth - dp(62);
        ballParams.y = statusBarHeight + dp(10);

        ball.setOnTouchListener(makeDrag(ball, ballParams, this::toggleMenu, this::translateClipboard));
    }

    private void buildCard() {
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F9FAFB"));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#E5E7EB"));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(10), dp(16), dp(14));

        // 顶栏（可拖动）
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        tvTag = new TextView(ctx);
        tvTag.setText("浮译");
        tvTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvTag.setTextColor(Color.parseColor("#34A78F"));
        header.addView(tvTag, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View spacer = new View(ctx);
        header.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        TextView btnTranslate = smallButton("翻译");
        TextView btnCopy = smallButton("复制");
        TextView btnHide = smallButton("收起");
        TextView btnClose = smallButton("关闭");
        btnTranslate.setOnClickListener(v -> {
            String src = lastOriginal.isEmpty() ? lastResult : lastOriginal;
            if (!src.isEmpty() && translateListener != null) translateListener.onTranslate(src);
        });
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && !lastResult.isEmpty()) {
                cm.setPrimaryClip(ClipData.newPlainText("translation", lastResult));
                Toast.makeText(ctx, "译文已复制", Toast.LENGTH_SHORT).show();
            }
        });
        btnHide.setOnClickListener(v -> collapse());
        btnClose.setOnClickListener(v -> collapse());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(6);
        header.addView(btnTranslate, btnLp);
        header.addView(btnCopy, cloneLp(btnLp));
        header.addView(btnHide, cloneLp(btnLp));
        header.addView(btnClose, cloneLp(btnLp));

        header.setOnTouchListener(makeDrag(card, cardParams, null, null));
        card.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        tvOriginal = new TextView(ctx);
        tvOriginal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvOriginal.setTextColor(Color.parseColor("#6B7280"));
        tvOriginal.setMaxLines(3);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        oLp.topMargin = dp(10);
        card.addView(tvOriginal, oLp);

        tvResult = new TextView(ctx);
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvResult.setTextColor(Color.parseColor("#111827"));
        tvResult.setMaxLines(16);
        tvResult.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(8);
        card.addView(tvResult, rLp);
    }

    private LinearLayout.LayoutParams cloneLp(LinearLayout.LayoutParams src) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(src.width, src.height);
        lp.leftMargin = src.leftMargin;
        return lp;
    }

    private TextView smallButton(String label) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(Color.parseColor("#374151"));
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EDF0F4"));
        bg.setCornerRadius(dp(10));
        tv.setBackground(bg);
        return tv;
    }

    private void translateClipboard() {
        Log.d("FloatTrans", "clipboard translate requested");
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
                showHint("剪贴板是空的\n先复制一段文字，再长按译球");
                return;
            }
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
            String s = t == null ? "" : t.toString().trim();
            if (s.isEmpty()) {
                showHint("剪贴板是空的\n先复制一段文字，再长按译球");
                return;
            }
            Log.d("FloatTrans", "clipboard=" + s.length() + " chars");
            if (clipListener != null) clipListener.onText(s);
        } catch (Throwable e) {
            showHint("读剪贴板失败：" + e.getMessage());
        }
    }

    private void showHint(String text) {
        tvTag.setText("浮译");
        bindTexts("", text);
        expand();
    }

    // ---------- 悬浮球功能菜单 ----------

    private void buildMenu() {
        menu = new LinearLayout(ctx);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F7F8FA"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#E5E7EB"));
        menu.setBackground(bg);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));

        addMenuItem("屏幕翻译", () -> {
            hideMenu();
            if (menuListener != null) menuListener.onScreenTranslate();
        });
        addMenuItem("翻译剪贴板", () -> {
            hideMenu();
            translateClipboard();
        });
        addMenuItem("识别屏幕文字", () -> {
            hideMenu();
            if (menuListener != null) menuListener.onScreenOcr();
        });
        addMenuItem("识别图片文字", () -> {
            hideMenu();
            if (menuListener != null) menuListener.onImageOcr();
        });
        addMenuItem("隐藏悬浮球", () -> {
            hideMenu();
            if (menuListener != null) menuListener.onHideBall();
        });
        addMenuItem("完全停止", () -> {
            hideMenu();
            if (menuListener != null) menuListener.onStopService();
        });
        addMenuItem("设置", () -> {
            hideMenu();
            if (menuListener != null) menuListener.onSettings();
        });

        menuParams = new WindowManager.LayoutParams(
                dp(150), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.LEFT;
    }

    private void addMenuItem(String label, final Runnable action) {
        TextView item = new TextView(ctx);
        item.setText(label);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        item.setTextColor(Color.parseColor("#1F2937"));
        item.setPadding(dp(12), dp(11), dp(12), dp(11));
        item.setOnClickListener(v -> action.run());
        menu.addView(item, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void toggleMenu() {
        if (menuShown) {
            hideMenu();
            return;
        }
        try {
            menuParams.x = clamp(ballParams.x - dp(80), dp(4), Math.max(dp(4), screenWidth - dp(154)));
            menuParams.y = ballParams.y + dp(58);
            wm.addView(menu, menuParams);
            menuShown = true;
        } catch (Exception e) {
            Toast.makeText(ctx, "菜单显示失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void hideMenu() {
        if (!menuShown) return;
        try {
            wm.removeView(menu);
        } catch (Exception ignored) {
        }
        menuShown = false;
    }

    private View.OnTouchListener makeDrag(final View root, final WindowManager.LayoutParams params,
                                          final Runnable onClick, final Runnable onLong) {
        return new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            boolean moved;
            long downAt;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = params.x;
                        startY = params.y;
                        moved = false;
                        downAt = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            int w = root.getWidth() > 0 ? root.getWidth() : dp(52);
                            int h = root.getHeight() > 0 ? root.getHeight() : dp(52);
                            params.x = clamp(startX + dx, 0, screenWidth - w);
                            params.y = clamp(startY + dy, statusBarHeight, screenHeight - h);
                            try {
                                wm.updateViewLayout(root, params);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            long held = System.currentTimeMillis() - downAt;
                            Log.d("FloatTrans", "ball touch up, held=" + held + "ms");
                            if (held >= 550) {
                                if (onLong != null) onLong.run();
                            } else if (onClick != null) {
                                onClick.run();
                            }
                        }
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    private int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, v));
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}
