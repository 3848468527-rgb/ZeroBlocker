package com.dsh.zeroblocker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 所有规则的存取（SharedPreferences 本地存储，不联网）。
 * 规则类型：开头号码、精确黑名单、白名单 + 几个开关。
 */
public final class RuleStore {

    private static final String SP_NAME = "zero_blocker";

    public static final String K_MASTER = "master";
    public static final String K_CONTACTS = "allow_contacts";
    public static final String K_DIALED = "allow_dialed";
    public static final String K_CARRIER = "block_carrier";
    public static final String K_PREFIX = "prefix_rules";
    public static final String K_BLACK = "black_list";
    public static final String K_WHITE = "white_list";
    public static final String K_REGIONS = "region_rules";
    public static final String K_SMS = "sms_filter";
    public static final String K_LOG = "block_log";
    public static final String K_INITED = "inited_v2";

    public static final class Prefix {
        public final String p;
        public final boolean on;

        public Prefix(String p, boolean on) {
            this.p = p;
            this.on = on;
        }
    }

    private RuleStore() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /** 首次运行时写入默认规则：拦截 0 开头。 */
    public static void ensureDefaults(Context c) {
        SharedPreferences p = sp(c);
        if (p.getBoolean(K_INITED, false)) {
            return;
        }
        List<Prefix> list = new ArrayList<>();
        list.add(new Prefix("0", true));
        p.edit()
                .putBoolean(K_INITED, true)
                .putBoolean(K_MASTER, true)
                .putBoolean(K_CONTACTS, true)
                .putBoolean(K_DIALED, true)
                .putBoolean(K_CARRIER, true)
                .putString(K_PREFIX, prefixesToJson(list))
                .putString(K_BLACK, "[]")
                .putString(K_WHITE, "[]")
                .putString(K_REGIONS, "[]")
                .putBoolean(K_SMS, true)
                .apply();
    }

    /** 老版本升级时补齐新增的默认项。 */
    public static void migrate(Context c) {
        SharedPreferences p = sp(c);
        boolean changed = false;
        if (!p.contains(K_REGIONS)) { p.edit().putString(K_REGIONS, "[]").apply(); changed = true; }
        if (!p.contains(K_SMS)) { p.edit().putBoolean(K_SMS, true).apply(); changed = true; }
    }

    private static String prefixesToJson(List<Prefix> list) {
        JSONArray a = new JSONArray();
        try {
            for (Prefix x : list) {
                JSONObject o = new JSONObject();
                o.put("p", x.p);
                o.put("on", x.on);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    public static List<Prefix> prefixes(Context c) {
        List<Prefix> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(K_PREFIX, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String p = o.optString("p", "");
                if (!p.isEmpty()) {
                    out.add(new Prefix(p, o.optBoolean("on", true)));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void addPrefix(Context c, String p) {
        List<Prefix> list = prefixes(c);
        for (Prefix x : list) {
            if (x.p.equals(p)) {
                return;
            }
        }
        list.add(new Prefix(p, true));
        sp(c).edit().putString(K_PREFIX, prefixesToJson(list)).apply();
    }

    public static void removePrefix(Context c, String p) {
        List<Prefix> list = new ArrayList<>();
        for (Prefix x : prefixes(c)) {
            if (!x.p.equals(p)) {
                list.add(x);
            }
        }
        sp(c).edit().putString(K_PREFIX, prefixesToJson(list)).apply();
    }

    public static void setPrefixOn(Context c, String p, boolean on) {
        List<Prefix> list = new ArrayList<>();
        for (Prefix x : prefixes(c)) {
            list.add(x.p.equals(p) ? new Prefix(x.p, on) : x);
        }
        sp(c).edit().putString(K_PREFIX, prefixesToJson(list)).apply();
    }

    public static List<String> list(Context c, String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void addItem(Context c, String key, String v) {
        List<String> l = list(c, key);
        if (!l.contains(v)) {
            l.add(v);
        }
        saveList(c, key, l);
    }

    public static void removeItem(Context c, String key, String v) {
        List<String> l = list(c, key);
        l.remove(v);
        saveList(c, key, l);
    }

    private static void saveList(Context c, String key, List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) {
            a.put(s);
        }
        sp(c).edit().putString(key, a.toString()).apply();
    }
}

