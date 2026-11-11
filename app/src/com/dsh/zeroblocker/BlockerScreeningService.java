package com.dsh.zeroblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.util.Log;

/**
 * 系统来电筛查服务：来电响铃前回调，命中规则直接拒接。
 * 需要被授予 android.app.role.CALL_SCREENING。
 */
public class BlockerScreeningService extends CallScreeningService {

    private static final String TAG = "ZeroBlocker";
    private static final String CHANNEL_ID = "blocked_calls";

    @Override
    public void onScreenCall(Call.Details callDetails) {
        String number = "";
        try {
            if (callDetails == null || callDetails.getCallDirection() != Call.Details.DIRECTION_INCOMING) {
                allow(callDetails);
                return;
            }

            Uri handle = callDetails.getHandle();
            number = handle == null ? "" : handle.getSchemeSpecificPart();
            Rules.Result r = Rules.evaluate(this, number);
            if (r.block) {
                block(callDetails);
                Rules.appendLog(this, number, r.reason, true);
                notifyBlocked(number, r.reason);
                Log.i(TAG, "BLOCK " + number + " -> " + r.reason);
            } else {
                allow(callDetails);
                Rules.appendLog(this, number, r.reason, false);
                Log.i(TAG, "ALLOW " + number + " -> " + r.reason);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onScreenCall error", t);
            try {
                allow(callDetails);
            } catch (Throwable ignored) {
            }
        }
    }

    private void allow(Call.Details d) {
        if (d == null) {
            return;
        }
        respondToCall(d, new CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build());
    }

    private void block(Call.Details d) {
        respondToCall(d, new CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSilenceCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(true)
                .build());
    }

    private void notifyBlocked(String number, String reason) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL_ID, "已拦截来电", NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_missed_call)
                    .setContentTitle("已拦截来电")
                    .setContentText((number == null || number.isEmpty() ? "隐藏号码" : number) + " · " + reason)
                    .setAutoCancel(true)
                    .build();
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
        } catch (Throwable ignored) {
        }
    }
}

