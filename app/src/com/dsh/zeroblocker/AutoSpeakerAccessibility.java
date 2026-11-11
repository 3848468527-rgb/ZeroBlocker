package com.dsh.zeroblocker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 自动免提（纯无障碍实现，不需要任何电话权限）。
 *
 * 逻辑：
 *  1. 检测到通话界面出现，开始 25 秒的"免提保持窗口"；
 *  2. 窗口内每 0.8 秒检查一次真实音频路由：
 *     - 如果还在听筒，就用无障碍点一下「免提」按钮；
 *     - 已经是扬声器就什么都不做（不会把免提点掉）；
 *  3. 这样无论系统是"接通瞬间重置回听筒"还是"延迟接通"，最终都能保持免提；
 *  4. 来电（响铃界面出现"来电/接听"）不处理，只处理自己拨出去的电话。
 */
public class AutoSpeakerAccessibility extends AccessibilityService {

    private static final String TAG = "AutoSpeaker";
    private static final String INCALL_PKG = "com.android.incallui";
    private static final long WINDOW_MS = 25000;
    private static final long SESSION_GAP_MS = 20000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;

    private boolean enforcing = false;
    private boolean incoming = false;
    private long windowStartAt = 0;
    private long lastIncallEventAt = 0;
    private long lastShadeAt = 0;
    private int shadeAttempts = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        audio = getSystemService(AudioManager.class);
        Log.i(TAG, "accessibility connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !INCALL_PKG.contentEquals(pkg)) {
            return;
        }

        long now = System.currentTimeMillis();
        lastIncallEventAt = now;

        if (!getSharedPreferences("autospeaker", MODE_PRIVATE).getBoolean("enabled", true)) {
            return;
        }
        if (!enforcing) {
            startWindow(now);
        }
        // 窗口内：用事件文本更新来电/呼出判断
        if (now - windowStartAt < WINDOW_MS) {
            updateDirection();
        }
    }

    private void startWindow(long now) {
        enforcing = true;
        incoming = false;
        windowStartAt = now;
        shadeAttempts = 0;
        lastShadeAt = 0;
        Log.i(TAG, "start enforce window");
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::tick, 80);
    }

    private void updateDirection() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        if (root == null) {
            return;
        }
        String texts = collectTexts(root, new StringBuilder(), 0).toString();
        if (texts.contains("来电") || texts.contains("响铃") || texts.contains("接听")) {
            incoming = true;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long elapsed = now - windowStartAt;
        if (!enforcing || elapsed > WINDOW_MS) {
            enforcing = false;
            Log.i(TAG, "enforce window over");
            return;
        }

        boolean callActive = isCallActive();
        boolean uiPresent = inCallWindowPresent();
        boolean recentEvents = (now - lastIncallEventAt) < 4000;
        if (!callActive && !recentEvents) {
            enforcing = false;
            Log.i(TAG, "call ended, stop");
            return;
        }
        boolean speakerOn = isSpeakerOn();
        Log.i(TAG, "tick t=" + elapsed + "ms speaker=" + speakerOn
                + " incoming=" + incoming + " ui=" + uiPresent + " callMode=" + callActive);
        if (!incoming && !speakerOn) {
            if (uiPresent) {
                boolean clicked = false;
                AccessibilityNodeInfo root = null;
                try {
                    root = getRootInActiveWindow();
                } catch (Throwable ignored) {
                }
                if (root != null && INCALL_PKG.contentEquals(root.getPackageName())) {
                    clicked = Boolean.TRUE.equals(findAndClick(root, 0));
                }
                if (!clicked) {
                    tapByPosition();
                }
            } else if (callActive && shadeAttempts < 3 && now - lastShadeAt > 2500) {
                // 用户把通话界面最小化了：从通知栏里的通话通知点「免提」
                shadeAttempts++;
                lastShadeAt = now;
                Log.i(TAG, "expand notification shade to toggle speaker");
                try {
                    performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                } catch (Throwable t) {
                    Log.e(TAG, "expand shade failed", t);
                }
                handler.postDelayed(this::clickShadeSpeaker, 500);
            }
        }
        long next = elapsed < 3000 ? 200 : 600;
        handler.postDelayed(this::tick, next);
    }

    private boolean isCallActive() {
        try {
            if (audio == null) {
                return false;
            }
            int mode = audio.getMode();
            return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
        } catch (Throwable t) {
            return false;
        }
    }

    private void clickShadeSpeaker() {
        boolean clicked = false;
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w == null) {
                    continue;
                }
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) {
                    continue;
                }
                if (clickByDesc(root, 0)) {
                    clicked = true;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "clickShadeSpeaker failed", t);
        }
        Log.i(TAG, "shade speaker clicked=" + clicked);
        handler.postDelayed(() -> {
            try {
                performGlobalAction(GLOBAL_ACTION_BACK);
            } catch (Throwable ignored) {
            }
        }, 600);
    }

    private boolean clickByDesc(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 30) {
            return false;
        }
        try {
            CharSequence desc = node.getContentDescription();
            CharSequence text = node.getText();
            String all = ((desc == null ? "" : desc.toString()) + " "
                    + (text == null ? "" : text.toString())).trim();
            if (all.equals("免提") || all.contains("免提")) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                Log.i(TAG, "shade candidate: desc=" + desc + " text=" + text
                        + " clickable=" + node.isClickable() + " bounds=" + b);
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                AccessibilityNodeInfo p = node;
                for (int i = 0; i < 3 && p != null; i++) {
                    p = p.getParent();
                    if (p != null && p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                if (clickByDesc(node.getChild(i), depth + 1)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
    private boolean inCallWindowPresent() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w == null || w.getRoot() == null) {
                    continue;
                }
                CharSequence p = w.getRoot().getPackageName();
                if (p != null && INCALL_PKG.contentEquals(p)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 兜底：无障碍树还没准备好时，直接按 MIUI 通话界面的免提按钮位置点一下。 */
    private void tapByPosition() {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float x = dm.widthPixels * 0.2047f;
            float y = dm.heightPixels * 0.8660f;
            Log.i(TAG, "coordinate tap " + x + "," + y + " screen=" + dm.widthPixels + "x" + dm.heightPixels);
            gestureTap(x, y);
        } catch (Throwable t) {
            Log.e(TAG, "tapByPosition failed", t);
        }
    }

    private boolean isSpeakerOn() {
        try {
            if (audio == null) {
                return false;
            }
            if (audio.isSpeakerphoneOn()) {
                return true;
            }
            AudioDeviceInfo dev = audio.getCommunicationDevice();
            return dev != null && dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean clickSpeaker() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        if (root == null) {
            Log.i(TAG, "root null");
            return false;
        }
        Boolean r = findAndClick(root, 0);
        if (r == null) {
            Log.i(TAG, "speaker node not found");
        }
        return r != null && r;
    }

    private Boolean findAndClick(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) {
            return null;
        }
        try {
            String text = node.getText() == null ? "" : node.getText().toString();
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
            String all = (text + " " + desc).toLowerCase();
            if (all.contains("免提") || all.contains("扬声器") || all.contains("speaker")) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                Log.i(TAG, "candidate desc=" + desc + " bounds=" + bounds);
                AccessibilityNodeInfo target = node;
                for (int i = 0; i < 4 && target != null && !target.isClickable(); i++) {
                    target = target.getParent();
                }
                if (target != null && target.isClickable()
                        && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "clicked by node action");
                    return true;
                }
                if (bounds.width() > 0 && bounds.height() > 0) {
                    gestureTap(bounds.centerX(), bounds.centerY());
                    return true;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                Boolean r = findAndClick(node.getChild(i), depth + 1);
                if (r != null && r) {
                    return r;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void gestureTap(float x, float y) {
        try {
            Log.i(TAG, "gesture tap " + x + "," + y);
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
                    .build();
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.i(TAG, "gesture completed");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.i(TAG, "gesture cancelled");
                }
            }, null);
        } catch (Throwable t) {
            Log.e(TAG, "gesture failed", t);
        }
    }

    private StringBuilder collectTexts(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 25 || sb.length() > 1500) {
            return sb;
        }
        try {
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) {
                sb.append(t).append(' ');
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collectTexts(node.getChild(i), sb, depth + 1);
            }
        } catch (Throwable ignored) {
        }
        return sb;
    }

    @Override
    public void onInterrupt() {
    }
}
