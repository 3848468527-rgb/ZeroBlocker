package com.dsh.zeroblocker;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 垃圾短信通知过滤：监听系统短信 App 的通知，命中规则就撤掉通知。
 * 需要用户在系统设置里给本应用开启「通知使用权」。
 * 说明：这是轻量方案，短信本体还在收件箱里，只是不弹通知。
 */
public class SmsNotificationFilter extends NotificationListenerService {

    private static final String TAG = "ZeroBlocker";

    private static final Set<String> SMS_PKGS = new HashSet<>(Arrays.asList(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.messaging",
            "com.oneplus.mms"
    ));

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) {
                return;
            }

            if (!RuleStore.sp(this).getBoolean(RuleStore.K_SMS, true)) {
                return;
            }
            String pkg = sbn.getPackageName();
            if (pkg == null) {
                return;
            }
            String lower = pkg.toLowerCase();
            if (!SMS_PKGS.contains(pkg) && !lower.contains("mms") && !lower.contains("messaging")) {
                return;
            }
            Bundle extras = sbn.getNotification().extras;
            if (extras == null) {
                return;
            }
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            String raw = title == null ? "" : title.toString();
            String num = Rules.digits(raw);
            if (num.length() < 3) {
                return;
            }
            Rules.Result r = Rules.evaluate(this, num);
            if (r.block) {
                cancelNotification(sbn.getKey());
                Rules.appendLog(this, raw + "（短信）", r.reason, true);
                Log.i(TAG, "BLOCK SMS " + raw + " -> " + r.reason);
            }
        } catch (Throwable t) {
            Log.e(TAG, "sms filter error", t);
        }
    }
}

