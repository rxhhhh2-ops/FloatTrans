package com.sonetto.floattrans;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无障碍服务：监听「长按选中文字」，去抖后触发翻译并弹出悬浮卡片。
 * 另提供截屏识字（API30+ 无障碍截图）与相册识字入口。
 */
public class TranslateService extends AccessibilityService {

    private static final String TAG = "FloatTrans";
    private static TranslateService instance;

    public static TranslateService getInstance() {
        return instance;
    }

    private FloatOverlay overlay;
    private Translator translator;
    private ScreenTranslationOverlay screenOverlay;
    private TextRecognizer ocr;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String pendingText = null;
    private String lastTranslated = null;
    private final Map<String, Boolean> gameCache = new HashMap<String, Boolean>();
    private boolean inGame = false;
    private AccessibilityServiceInfo baseInfo;

    private final Runnable translateTask = () -> {
        String text = pendingText;
        if (text == null || text.isEmpty()) return;
        if (text.equals(lastTranslated)) return;
        doTranslate(text);
    };

    private void doTranslate(String text) {
        lastTranslated = text;
        Log.d(TAG, "translate start: [" + clip(text) + "]");
        overlay.showLoading(text);
        translator.translate(text, new Translator.Callback() {
            @Override
            public void onOk(String t, String engine) {
                Log.d(TAG, "translate ok (" + engine + "): [" + clip(t) + "]");
                overlay.showTranslation(text, t, engine);
            }

            @Override
            public void onErr(String e) {
                Log.d(TAG, "translate err: " + e);
                overlay.showError(text, e);
            }
        });
    }

    /** 供 ProcessTextActivity / 悬浮卡按钮直接触发翻译（悬浮卡展示）。 */
    public void translateNow(String text) {
        if (text == null || text.trim().isEmpty()) return;
        handler.removeCallbacks(translateTask);
        handler.post(() -> doTranslate(text.trim()));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        overlay = new FloatOverlay(this);
        translator = Translator.get(this);
        screenOverlay = new ScreenTranslationOverlay(this);
        try {
            ocr = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        } catch (Throwable t) {
            Log.d(TAG, "ocr init failed: " + t);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // 先留一份完整配置：处于「完全停止」时也会走到这里，之后恢复悬浮球要靠它把事件类型改回来
        baseInfo = getServiceInfo();
        if (Prefs.get(this).stopped()) {
            // 用户已「完全停止」：即使被系统/ROM 重新绑定，也保持静默（零事件、不显示球）
            try {
                AccessibilityServiceInfo info = getServiceInfo();
                if (info != null) {
                    info.eventTypes = 0;
                    setServiceInfo(info);
                }
            } catch (Throwable ignored) {
            }
            Log.d(TAG, "stopped state, stay silent");
            return;
        }
        if (!Prefs.get(this).ballHidden()) overlay.showBall();
        overlay.setClipListener(text -> translateNow(text));
        overlay.setTranslateListener(text -> translateNow(text));
        overlay.setMenuListener(new FloatOverlay.MenuListener() {
            @Override
            public void onScreenTranslate() {
                screenTranslate();
            }

            @Override
            public void onScreenOcr() {
                ocrScreen();
            }

            @Override
            public void onImageOcr() {
                openImagePicker();
            }

            @Override
            public void onHideBall() {
                setBallEnabled(false);
            }

            @Override
            public void onStopService() {
                Prefs.get(TranslateService.this).edit()
                        .putBoolean("stopped", true)
                        .putBoolean("ball_hidden", false)
                        .apply();
                try {
                    new java.io.File(getFilesDir(), "disabled").createNewFile();
                } catch (Throwable ignored) {
                }
                try {
                    AccessibilityServiceInfo info = getServiceInfo();
                    if (info != null) {
                        info.eventTypes = 0;
                        setServiceInfo(info);
                    }
                } catch (Throwable ignored) {
                }
                removeSelfFromAccessibilitySetting();
                disableSelf();
            }

            @Override
            public void onSettings() {
                Intent i = new Intent(TranslateService.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        });
        Log.d(TAG, "service connected");
    }

    // ---------- 截屏识字 ----------

    private interface ScreenshotHandler {
        void onBitmap(Bitmap bmp);

        void onFail(String message);
    }

    /**
     * 先收起卡片、隐藏悬浮球，等一小段时间再截屏。
     * 无障碍截图会把系统悬浮窗一起拍进去，不隐藏的话 OCR 结果里会混进「浮译」自己的菜单与卡片文字。
     */
    private void captureScreen(final ScreenshotHandler cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.onFail("系统版本过低，不支持截屏（需 Android 11+）");
            return;
        }
        overlay.collapse();
        overlay.setBallVisible(false);
        handler.postDelayed(() -> {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        Bitmap bmp = hardwareToBitmap(result);
                        overlay.setBallVisible(true);
                        if (bmp == null) cb.onFail("截图失败");
                        else cb.onBitmap(bmp);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.d(TAG, "screenshot fail code=" + errorCode);
                        overlay.setBallVisible(true);
                        cb.onFail("截屏失败（code=" + errorCode + "）");
                    }
                });
            } catch (Throwable t) {
                Log.d(TAG, "screenshot err: " + t);
                overlay.setBallVisible(true);
                cb.onFail("截屏异常：" + t);
            }
        }, 260);
    }

    private Bitmap hardwareToBitmap(ScreenshotResult result) {
        Bitmap bmp = null;
        try {
            Bitmap hw = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
            if (hw != null) bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            Log.d(TAG, "screenshot copy err: " + t);
        } finally {
            try {
                result.getHardwareBuffer().close();
            } catch (Throwable ignored) {
            }
        }
        return bmp;
    }

    private void ocrScreen() {
        if (Build.VERSION.SDK_INT < 30) {
            overlay.showOcrResult("", "系统版本过低，不支持截屏识字（需 Android 11+）");
            return;
        }
        captureScreen(new ScreenshotHandler() {
            @Override
            public void onBitmap(Bitmap bmp) {
                overlay.showLoading("识别中…");
                processOcr(bmp);
            }

            @Override
            public void onFail(String message) {
                overlay.showOcrResult("", message);
            }
        });
    }

    private void processOcr(Bitmap bmp) {
        if (ocr == null) {
            overlay.showOcrResult("", "OCR 引擎未初始化");
            return;
        }
        ocr.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(result -> {
                    String s = result.getText() == null ? "" : result.getText().trim();
                    Log.d(TAG, "ocr ok: " + clip(s));
                    if (s.isEmpty()) overlay.showOcrResult("", "没有识别到文字");
                    else overlay.showOcrResult(s, null);
                })
                .addOnFailureListener(e -> {
                    Log.d(TAG, "ocr err: " + e);
                    overlay.showOcrResult("", "识别失败：" + e.getMessage());
                });
    }

    private void openImagePicker() {
        try {
            Intent i = new Intent(this, OcrPickActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.d(TAG, "pick err: " + t);
        }
    }

    // ---------- 整屏翻译 ----------

    /** 截屏 → OCR → 逐行批量翻译 → 译文块按原位置覆盖屏幕。 */
    private void screenTranslate() {
        if (Build.VERSION.SDK_INT < 30) {
            overlay.showOcrResult("", "系统版本过低，不支持整屏翻译（需 Android 11+）");
            return;
        }
        if (screenOverlay.isShowing()) {
            screenOverlay.dismiss();
            overlay.setBallVisible(true);
            return;
        }
        captureScreen(new ScreenshotHandler() {
            @Override
            public void onBitmap(Bitmap bmp) {
                overlay.showLoading("整屏识别中…");
                ocrForScreenTranslate(bmp);
            }

            @Override
            public void onFail(String message) {
                overlay.showOcrResult("", message);
            }
        });
    }

    private void ocrForScreenTranslate(Bitmap bmp) {
        if (ocr == null) {
            overlay.showOcrResult("", "OCR 引擎未初始化");
            return;
        }
        ocr.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(result -> {
                    final List<Rect> boxes = new ArrayList<>();
                    final List<String> lines = new ArrayList<>();
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String t = line.getText() == null ? "" : line.getText().trim();
                            Rect r = line.getBoundingBox();
                            if (t.length() < 2 || r == null || r.width() < 12 || r.height() < 12) continue;
                            if (t.replaceAll("[^\\p{L}\\p{N}]", "").length() < 2) continue;
                            boxes.add(r);
                            lines.add(t);
                            if (boxes.size() >= 40) break;
                        }
                        if (boxes.size() >= 40) break;
                    }
                    Log.d(TAG, "screen translate lines=" + lines.size());
                    if (lines.isEmpty()) {
                        overlay.showOcrResult("", "没有识别到可翻译的文字");
                        return;
                    }
                    String langName = targetLangName();
                    StringBuilder sb = new StringBuilder();
                    sb.append("请逐行把下面每行「编号|原文」翻译成").append(langName)
                            .append("，自动判断源语言。输出格式必须是「编号|译文」，行数与编号一一对应，")
                            .append("不要输出任何解释或其他内容：\n");
                    for (int i = 0; i < lines.size(); i++) {
                        sb.append(i + 1).append('|').append(lines.get(i)).append('\n');
                    }
                    overlay.showLoading("整屏翻译中…");
                    translator.translateRaw(sb.toString(), new Translator.Callback() {
                        @Override
                        public void onOk(String t, String engine) {
                            Map<Integer, String> map = parseNumbered(t, lines.size());
                            Log.d(TAG, "numbered parsed=" + map.size() + "/" + lines.size());
                            List<String> out = new ArrayList<>();
                            for (int i = 0; i < lines.size(); i++) {
                                String tr = map.get(i + 1);
                                out.add(tr == null ? "" : tr);
                            }
                            if (map.size() < Math.max(1, lines.size() / 3)) {
                                // 编号对齐失败，退化为整段译文卡片
                                overlay.showOcrResult(t, null);
                                return;
                            }
                            overlay.collapse();
                            // 覆盖层显示期间先把悬浮球收起来，避免它挡住译文和关闭按钮
                            overlay.setBallVisible(false);
                            screenOverlay.show(boxes, out, () -> overlay.setBallVisible(true));
                            if (!screenOverlay.isShowing()) overlay.setBallVisible(true);
                        }

                        @Override
                        public void onErr(String e) {
                            overlay.showError("整屏翻译", e);
                        }
                    });
                })
                .addOnFailureListener(e -> overlay.showOcrResult("", "识别失败：" + e.getMessage()));
    }

    private String targetLangName() {
        String code = Prefs.get(this).targetLang();
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

    private Map<Integer, String> parseNumbered(String out, int count) {
        Map<Integer, String> map = new LinkedHashMap<>();
        if (out == null) return map;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            Matcher m = Pattern.compile("^(\\d+)\\s*[|｜:：.、]\\s*(.+)$").matcher(s);
            if (m.find()) {
                try {
                    int idx = Integer.parseInt(m.group(1));
                    String v = m.group(2).trim();
                    if (idx >= 1 && idx <= count && !v.isEmpty()) map.put(idx, v);
                } catch (Exception ignored) {
                }
            }
        }
        return map;
    }

    // ---------- 悬浮球显隐 / 游戏自动隐藏 ----------

    /** 供设置页调用：显示/隐藏悬浮球（记住选择）。 */
    public void setBallEnabled(boolean enabled) {
        if (enabled) {
            Prefs.get(this).edit().putBoolean("ball_hidden", false).putBoolean("stopped", false).apply();
            // 用户主动恢复：清掉「完全停止」标记，把无障碍条目加回系统并恢复监听
            try {
                new java.io.File(getFilesDir(), "disabled").delete();
            } catch (Throwable ignored) {
            }
            addSelfToAccessibilitySetting();
            // 处于「完全停止」时 onServiceConnected 提前返回过，baseInfo 可能没取到，这里兜底取一次
            AccessibilityServiceInfo info = baseInfo != null ? baseInfo : getServiceInfo();
            if (info != null) {
                try {
                    setServiceInfo(info);
                } catch (Throwable ignored) {
                }
            }
            baseInfo = info;
        } else {
            Prefs.get(this).edit().putBoolean("ball_hidden", true).apply();
        }
        overlay.setBallVisible(enabled && !inGame);
    }

    /** 需要 WRITE_SECURE_SETTINGS（安装时由 Shizuku 授予）。 */
    private void removeSelfFromAccessibilitySetting() {
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            String cur = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || cur.isEmpty()) return;
            String me = getPackageName() + "/" + TranslateService.class.getName();
            StringBuilder sb = new StringBuilder();
            for (String s : cur.split(":")) {
                if (s.equalsIgnoreCase(me)) continue;
                if (sb.length() > 0) sb.append(':');
                sb.append(s);
            }
            android.provider.Settings.Secure.putString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 需要 WRITE_SECURE_SETTINGS（安装时由 Shizuku 授予）。 */
    private void addSelfToAccessibilitySetting() {
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            String cur = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String me = getPackageName() + "/" + TranslateService.class.getName();
            if (cur != null && cur.contains(me)) return;
            String next = (cur == null || cur.isEmpty() || "null".equals(cur)) ? me : cur + ":" + me;
            android.provider.Settings.Secure.putString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            android.provider.Settings.Secure.putInt(getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Throwable ignored) {
        }
    }

    /** 前台切到游戏时自动隐藏球，离开游戏自动恢复（手动隐藏的不会恢复）。 */
    private void updateGameState(String pkg) {
        if (pkg.isEmpty() || pkg.equals(getPackageName())) return;
        boolean game = isGamePackage(pkg);
        if (game && !inGame) {
            inGame = true;
            overlay.collapse();
            overlay.setBallVisible(false);
            Log.d(TAG, "game detected, hide ball: " + pkg);
        } else if (!game && inGame) {
            inGame = false;
            if (!Prefs.get(this).ballHidden()) overlay.setBallVisible(true);
            Log.d(TAG, "leave game, restore ball: " + pkg);
        }
    }

    private boolean isGamePackage(String pkg) {
        Boolean c = gameCache.get(pkg);
        if (c != null) return c;
        boolean g = false;
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            g = ai.category == ApplicationInfo.CATEGORY_GAME;
        } catch (Throwable ignored) {
        }
        gameCache.put(pkg, g);
        return g;
    }

    // ---------- 选区监听 ----------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 保持卡片常驻：键盘弹出、选词菜单都会触发窗口切换，不能拿来当收起信号
            updateGameState(pkg);
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            String sel = extractSelection(event);
            if (sel != null && !sel.trim().isEmpty()) {
                schedule(sel.trim());
            } else if (sel != null && sel.isEmpty()) {
                handler.removeCallbacks(translateTask);
                pendingText = null;
                // 选区清空后允许再次翻译同一段文字（否则重复选中会被去重逻辑挡掉）
                lastTranslated = null;
            }
        }
    }

    private void schedule(String text) {
        if (!Prefs.get(this).autoPopup()) return;
        pendingText = text;
        handler.removeCallbacks(translateTask);
        handler.postDelayed(translateTask, 380);
    }

    /** 尽力从事件里抠出选中的文字；返回 null 表示拿不到（不动作），返回 "" 表示选区被清空。 */
    private String extractSelection(AccessibilityEvent event) {
        try {
            int from = event.getFromIndex();
            int to = event.getToIndex();
            CharSequence t = null;
            if (event.getText() != null && !event.getText().isEmpty()) t = event.getText().get(0);
            if (t == null) t = event.getBeforeText();
            if (t == null) {
                AccessibilityNodeInfo src = event.getSource();
                if (src != null) {
                    t = src.getText();
                    if (t == null) t = src.getContentDescription();
                }
            }
            if (t != null && from >= 0 && to >= 0) {
                if (to > from && to <= t.length()) return t.subSequence(from, to).toString();
                return "";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String clip(String s) {
        if (s == null) return "null";
        String r = s.replace('\n', ' ');
        return r.length() <= 60 ? r : r.substring(0, 60) + "…";
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (overlay != null) overlay.destroy();
        if (screenOverlay != null) screenOverlay.dismiss();
        if (ocr != null) {
            try {
                ocr.close();
            } catch (Throwable ignored) {
            }
            ocr = null;
        }
        instance = null;
        super.onDestroy();
    }
}
