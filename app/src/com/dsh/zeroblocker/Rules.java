package com.dsh.zeroblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 拦截规则引擎（来电筛查服务与 App 内测试共用）。
 *
 * 判定顺序：
 *   1. 无号码 / 总开关关闭      -> 放行
 *   2. 白名单                  -> 放行
 *   3. 通讯录联系人            -> 放行（可关）
 *   4. 最近 30 天拨出过的号码   -> 放行（可关，防止对方回拨被拦）
 *   5. 黑名单                  -> 拦截
 *   6. 开头号码规则            -> 拦截（如 0、95、400、170）
 *   7. 归属地拦截              -> 拦截（用户自己选的省份，如福建）
 *   8. 三大运营商营销/客服号    -> 拦截（可关）
 *   8. 其它                    -> 放行
 */
public final class Rules {

    /** 三大运营商营销/客服号码。 */
    public static final String[] CARRIER_NUMBERS = {
            "10016", "10085", "10086", "10010", "10000", "10001", "10003", "10015"
    };
    /** 1010 开头为运营商/企业外呼营销号段。 */
    public static final String[] CARRIER_PREFIXES = {"1010"};

    public static final class Result {
        public final boolean block;
        public final String reason;

        public Result(boolean block, String reason) {
            this.block = block;
            this.reason = reason;
        }
    }

    private Rules() {
    }

    public static Result evaluate(Context ctx, String raw) {
        SharedPreferences sp = RuleStore.sp(ctx);
        String d = digits(raw);
        if (d.isEmpty()) {
            return new Result(false, "无号码，放行");
        }
        if (!sp.getBoolean(RuleStore.K_MASTER, true)) {
            return new Result(false, "总开关已关闭，放行");
        }
        for (String w : RuleStore.list(ctx, RuleStore.K_WHITE)) {
            if (sameNumber(w, d)) {
                return new Result(false, "命中白名单，放行");
            }
        }
        if (sp.getBoolean(RuleStore.K_CONTACTS, true) && isContact(ctx, d)) {
            return new Result(false, "通讯录联系人，放行");
        }
        if (sp.getBoolean(RuleStore.K_DIALED, true) && isRecentlyDialed(ctx, d)) {
            return new Result(false, "最近拨出过的号码回拨，放行");
        }
        for (String b : RuleStore.list(ctx, RuleStore.K_BLACK)) {
            if (sameNumber(b, d)) {
                return new Result(true, "命中黑名单");
            }
        }
        for (RuleStore.Prefix p : RuleStore.prefixes(ctx)) {
            if (p.on && d.startsWith(p.p)) {
                return new Result(true, "以 " + p.p + " 开头");
            }
        }
        int cityId = RegionDB.get(ctx).cityOfNumber(d);
        if (cityId >= 0) {
            int provinceId = RegionData.CITY_PROVINCE[cityId];
            String provinceName = RegionData.PROVINCES[provinceId];
            String cityName = RegionData.CITIES[cityId];
            List<String> blockedRegions = RuleStore.list(ctx, RuleStore.K_REGIONS);
            if (blockedRegions.contains("p:" + provinceName) || blockedRegions.contains(provinceName)) {
                return new Result(true, "归属地拦截：" + provinceName);
            }
            if (blockedRegions.contains("c:" + provinceName + "/" + cityName)) {
                return new Result(true, "归属地拦截：" + provinceName + "-" + cityName);
            }
        }
        if (sp.getBoolean(RuleStore.K_CARRIER, true) && isCarrier(d)) {
            return new Result(true, "运营商营销/客服号码");
        }
        return new Result(false, "未命中拦截规则，放行");
    }

    /** 只保留数字。 */
    public static String digits(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stripCountry(String d) {
        if (d == null) {
            return "";
        }
        if (d.startsWith("0086")) {
            return d.substring(4);
        }
        if (d.startsWith("86") && d.length() >= 13) {
            return d.substring(2);
        }
        return d;
    }

    private static String tail11(String d) {
        if (d == null) {
            return "";
        }
        return d.length() > 11 ? d.substring(d.length() - 11) : d;
    }

    private static boolean isCarrier(String d) {
        for (String n : CARRIER_NUMBERS) {
            if (n.equals(d)) {
                return true;
            }
        }
        for (String p : CARRIER_PREFIXES) {
            if (d.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContact(Context ctx, String d) {
        String[] candidates = new String[]{d, stripCountry(d), tail11(stripCountry(d))};
        for (String c : candidates) {
            if (c == null || c.isEmpty()) {
                continue;
            }
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(c));
            Cursor cur = null;
            try {
                cur = ctx.getContentResolver().query(uri,
                        new String[]{ContactsContract.PhoneLookup._ID}, null, null, null);
                if (cur != null && cur.moveToFirst()) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 没授通讯录权限等情况，视为非联系人
            } finally {
                if (cur != null) {
                    try {
                        cur.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    private static boolean isRecentlyDialed(Context ctx, String d) {
        long since = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L;
        Cursor cur = null;
        try {
            cur = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER},
                    CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.DATE + ">?",
                    new String[]{String.valueOf(CallLog.Calls.OUTGOING_TYPE), String.valueOf(since)},
                    CallLog.Calls.DATE + " DESC");
            while (cur != null && cur.moveToNext()) {
                if (sameNumber(digits(cur.getString(0)), d)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 没授通话记录权限等情况
        } finally {
            if (cur != null) {
                try {
                    cur.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    public static boolean sameNumber(String a, String b) {
        a = stripCountry(digits(a));
        b = stripCountry(digits(b));
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return a.length() >= 11 && b.length() >= 11 && tail11(a).equals(tail11(b));
    }

    // ---------------- 拦截记录 ----------------

    public static void appendLog(Context ctx, String number, String reason, boolean blocked) {
        try {
            SharedPreferences sp = RuleStore.sp(ctx);
            JSONArray old;
            try {
                old = new JSONArray(sp.getString(RuleStore.K_LOG, "[]"));
            } catch (Throwable t) {
                old = new JSONArray();
            }
            JSONObject o = new JSONObject();
            o.put("n", number == null ? "" : number);
            o.put("r", reason == null ? "" : reason);
            o.put("b", blocked);
            o.put("t", System.currentTimeMillis());
            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < old.length() && i < 99; i++) {
                out.put(old.get(i));
            }
            sp.edit().putString(RuleStore.K_LOG, out.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static JSONArray readLog(Context ctx) {
        try {
            return new JSONArray(RuleStore.sp(ctx).getString(RuleStore.K_LOG, "[]"));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    public static void clearLog(Context ctx) {
        RuleStore.sp(ctx).edit().remove(RuleStore.K_LOG).apply();
    }
}

