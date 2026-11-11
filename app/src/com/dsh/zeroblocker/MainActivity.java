package com.dsh.zeroblocker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class MainActivity extends Activity {

    private static final int REQ_ROLE = 1001;
    private static final int REQ_PERMS = 1002;

    private LinearLayout root;
    private TextView statusView;
    private TextView logView;
    private TextView smsStatusView;
    private TextView autoSpeakerStatusView;
    private LinearLayout prefixBox;
    private LinearLayout blackBox;
    private LinearLayout whiteBox;
    private LinearLayout regionBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RuleStore.ensureDefaults(this);
        RuleStore.migrate(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        final int p = dp(16);
        page.setPadding(p, p, p, p);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
        // Android 15+ 默认强制 edge-to-edge：给滚动区域留出系统栏空间，滚动时内容不会压到状态栏下面
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        root = page;

        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) {
            refreshStatus();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 主界面 ----------------

    private void buildUi() {
        TextView title = new TextView(this);
        title.setText(Config.APP_TITLE);
        title.setTextSize(26);
        title.setTextColor(Config.ACCENT_COLOR);
        title.setPadding(0, 0, 0, dp(2));
        root.addView(title);

        addText(Config.APP_SUBTITLE, 14, false);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(10), 0, dp(6));
        root.addView(statusView);

        addButton("① 授予「来电筛查」权限（必做）", this::requestRole);
        addButton("② 授予通讯录 / 通话记录权限", this::requestPerms);


        section("基础开关");
        addSwitch(RuleStore.K_MASTER, "总开关（启用拦截）", true);
        addSwitch(RuleStore.K_CONTACTS, "通讯录里的联系人来电放行", true);
        addSwitch(RuleStore.K_DIALED, "我最近 30 天拨出过的号码，对方回拨放行", true);
        addSwitch(RuleStore.K_CARRIER, "拦截运营商营销/客服号（10086/10010/10000/10016/10085…）", true);

        section("开头号码规则");
        addText("号码以这些开头就自动拦截，可自己加（常见：0、95、400、170、1010）。", 13, true);
        prefixBox = new LinearLayout(this);
        prefixBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(prefixBox);
        addButton("＋ 添加开头号码规则", () -> promptInput("添加开头号码", "例如 0、95、400、170", v -> {
            RuleStore.addPrefix(this, v);
            refreshRules();
            toast("已添加");
        }));

        section("地区拦截（按号码归属地）");
        addText("可选择「整个省」或「具体城市」。例如只拦「海南-海口」：海口号码拦，三亚号码正常放行。", 13, true);
        regionBox = new LinearLayout(this);
        regionBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(regionBox);
        addButton("＋ 添加地区拦截", this::showRegionDialog);

        section("黑名单（精确号码）");
        blackBox = new LinearLayout(this);
        blackBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(blackBox);
        addButton("＋ 添加黑名单号码", () -> promptInput("添加黑名单号码", "完整号码 / 后 11 位", v -> {
            RuleStore.addItem(this, RuleStore.K_BLACK, v);
            refreshRules();
            toast("已添加");
        }));

        section("白名单（永远放行）");
        whiteBox = new LinearLayout(this);
        whiteBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(whiteBox);
        addButton("＋ 添加白名单号码", () -> promptInput("添加白名单号码", "这个号码永远放行", v -> {
            RuleStore.addItem(this, RuleStore.K_WHITE, v);
            refreshRules();
            toast("已添加");
        }));

        section("垃圾短信过滤");
        addSwitch(RuleStore.K_SMS, "拦截垃圾短信通知（需开启通知使用权）", true);
        addButton("开启通知使用权", this::openNotificationListenerSettings);
        smsStatusView = new TextView(this);
        smsStatusView.setTextSize(13);
        smsStatusView.setPadding(0, dp(4), 0, dp(4));
        root.addView(smsStatusView);

        section("自动免提（通话接通自动开扬声器）");
        addText("打开后：打电话接通自动开免提；即使你把通话界面最小化，也会自动从通知栏点「免提」再收起通知栏。", 13, true);
        final CheckBox autoSpeaker = new CheckBox(this);
        autoSpeaker.setText("启用自动免提");
        autoSpeaker.setTextSize(14);
        autoSpeaker.setChecked(getSharedPreferences("autospeaker", MODE_PRIVATE).getBoolean("enabled", true));
        autoSpeaker.setOnCheckedChangeListener((b, checked) ->
                getSharedPreferences("autospeaker", MODE_PRIVATE).edit().putBoolean("enabled", checked).apply());
        root.addView(autoSpeaker, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addButton("开启「无障碍」权限（自动免提需要）", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("在列表里找到「零号拦截」并打开");
            } catch (Throwable t) {
                toast("请到 设置 → 无障碍 里开启");
            }
        });
        autoSpeakerStatusView = new TextView(this);
        autoSpeakerStatusView.setTextSize(13);
        autoSpeakerStatusView.setPadding(0, dp(4), 0, dp(4));
        root.addView(autoSpeakerStatusView);

        section("规则测试");
        addButton("输入一个号码，测试会不会被拦", this::showTestDialog);

        section("最近记录");
        logView = new TextView(this);
        logView.setTextSize(13);
        root.addView(logView);
        addButton("清空记录", () -> {
            Rules.clearLog(this);
            refreshLog();
        });

        section("说明");
        addText(Config.FOOTER, 13, true);
    }

    // ---------------- 通用小控件 ----------------

    private void section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(19);
        tv.setTextColor(Config.ACCENT_COLOR);
        tv.setPadding(0, dp(18), 0, dp(8));
        root.addView(tv);
    }

    private void addText(String text, int sp, boolean gray) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        if (gray) {
            tv.setTextColor(Color.parseColor("#666666"));
        }
        tv.setPadding(0, dp(4), 0, dp(4));
        root.addView(tv);
    }

    private void addSwitch(String key, String label, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(14);
        cb.setChecked(RuleStore.sp(this).getBoolean(key, def));
        cb.setOnCheckedChangeListener((b, checked) ->
                RuleStore.sp(this).edit().putBoolean(key, checked).apply());
        root.addView(cb, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private void addButton(String label, Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setOnClickListener(v -> onClick.run());
        root.addView(btn);
    }

    private void addHint(LinearLayout box, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#999999"));
        tv.setPadding(0, dp(4), 0, dp(4));
        box.addView(tv);
    }

    private void addRowWithDelete(LinearLayout box, String label, Runnable onDelete) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        Button del = new Button(this);
        del.setText("删除");
        del.setTextSize(12);
        del.setAllCaps(false);
        del.setOnClickListener(v -> onDelete.run());
        row.addView(del);
        box.addView(row);
    }

    // ---------------- 状态刷新 ----------------

    private void refreshStatus() {
        RoleManager rm = getSystemService(RoleManager.class);
        boolean avail = rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING);
        boolean held = rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        boolean contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        boolean callLog = checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;

        String s = "来电筛查权限：" + (held ? "已授权 ✅" : "未授权 ❌")
                + "\n系统支持来电筛查角色：" + (avail ? "是" : "否")
                + "\n通讯录权限：" + (contacts ? "已授权 ✅" : "未授权 ❌")
                + "　通话记录权限：" + (callLog ? "已授权 ✅" : "未授权 ❌");
        statusView.setText(s);

        if (smsStatusView != null) {
            smsStatusView.setText("通知使用权：" + (isNotificationListenerEnabled() ? "已开启 ✅" : "未开启 ❌（短信过滤需要它）"));
        }
        if (autoSpeakerStatusView != null) {
            boolean as = isAutoSpeakerAccessibilityEnabled();
            boolean asOn = getSharedPreferences("autospeaker", MODE_PRIVATE).getBoolean("enabled", true);
            autoSpeakerStatusView.setText("自动免提无障碍：" + (as ? "已开启 ✅" : "未开启 ❌（需要开无障碍）")
                    + "　开关：" + (asOn ? "开" : "关"));
        }
        refreshRules();
        refreshLog();
    }

    private void refreshRules() {
        if (prefixBox == null) {
            return;
        }
        prefixBox.removeAllViews();
        List<RuleStore.Prefix> prefixes = RuleStore.prefixes(this);
        if (prefixes.isEmpty()) {
            addHint(prefixBox, "（暂无规则，可添加 0、95、400 等）");
        }
        for (RuleStore.Prefix p : prefixes) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this);
            cb.setText("拦以 \"" + p.p + "\" 开头的号码");
            cb.setTextSize(14);
            cb.setChecked(p.on);
            cb.setOnCheckedChangeListener((b, checked) -> RuleStore.setPrefixOn(this, p.p, checked));
            row.addView(cb, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            Button del = new Button(this);
            del.setText("删除");
            del.setTextSize(12);
            del.setAllCaps(false);
            del.setOnClickListener(v -> {
                RuleStore.removePrefix(this, p.p);
                refreshRules();
            });
            row.addView(del);
            prefixBox.addView(row);
        }

        regionBox.removeAllViews();
        List<String> regions = RuleStore.list(this, RuleStore.K_REGIONS);
        if (regions.isEmpty()) {
            addHint(regionBox, "（暂无地区规则，例如添加「海南-海口」）");
        }
        for (final String raw : regions) {
            String label;
            if (raw.startsWith("p:")) {
                label = "拦截归属地：" + raw.substring(2) + "（全省）";
            } else if (raw.startsWith("c:")) {
                label = "拦截归属地：" + raw.substring(2).replace("/", "-");
            } else {
                label = "拦截归属地：" + raw + "（全省）";
            }
            addRowWithDelete(regionBox, label, () -> {
                RuleStore.removeItem(this, RuleStore.K_REGIONS, raw);
                refreshRules();
            });
        }

        blackBox.removeAllViews();
        List<String> blacks = RuleStore.list(this, RuleStore.K_BLACK);
        if (blacks.isEmpty()) {
            addHint(blackBox, "（暂无黑名单）");
        }
        for (final String n : blacks) {
            addRowWithDelete(blackBox, n, () -> {
                RuleStore.removeItem(this, RuleStore.K_BLACK, n);
                refreshRules();
            });
        }

        whiteBox.removeAllViews();
        List<String> whites = RuleStore.list(this, RuleStore.K_WHITE);
        if (whites.isEmpty()) {
            addHint(whiteBox, "（暂无白名单）");
        }
        for (final String n : whites) {
            addRowWithDelete(whiteBox, n, () -> {
                RuleStore.removeItem(this, RuleStore.K_WHITE, n);
                refreshRules();
            });
        }
    }

    private void refreshLog() {
        if (logView == null) {
            return;
        }
        JSONArray arr = Rules.readLog(this);
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < arr.length() && shown < 30; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            boolean blocked = o.optBoolean("b", false);
            sb.append(f.format(new Date(o.optLong("t", 0)))).append("  ")
                    .append(blocked ? "🚫 拦截  " : "✅ 放行  ")
                    .append(o.optString("n", ""))
                    .append("  (").append(o.optString("r", "")).append(")\n");
            shown++;
        }
        logView.setText(sb.length() == 0 ? "（暂无记录）" : sb.toString());
    }

    // ---------------- 权限与设置 ----------------

    private void requestRole() {
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            toast("系统不支持来电筛查角色");
            return;
        }
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            toast("已授权");
            return;
        }
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE);
    }

    private String[] missingPerms() {
        List<String> list = new ArrayList<>();
        addIfMissing(list, Manifest.permission.READ_CALL_LOG);
        addIfMissing(list, Manifest.permission.READ_CONTACTS);
        addIfMissing(list, Manifest.permission.ANSWER_PHONE_CALLS);
        addIfMissing(list, Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(list, Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[0]);
    }

    private void addIfMissing(List<String> list, String perm) {
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            list.add(perm);
        }
    }

    private void requestPerms() {
        String[] missing = missingPerms();
        if (missing.length == 0) {
            toast("权限已全部授予");
            return;
        }
        requestPermissions(missing, REQ_PERMS);
    }

    private boolean isNotificationListenerEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isAutoSpeakerAccessibilityEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return flat != null && flat.contains(getPackageName() + "/" + AutoSpeakerAccessibility.class.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void openNotificationListenerSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable t) {
            toast("请到 设置 → 通知 → 通知使用权 里开启");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        refreshStatus();
    }

    // ---------------- 弹窗 ----------------

    private void showTestDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint("例如 05911234567 / 01012345678 / 10086 / 13800138000");
        new AlertDialog.Builder(this)
                .setTitle("测试号码")
                .setView(input)
                .setPositiveButton("测试", (d, w) -> {
                    Rules.Result r = Rules.evaluate(this, input.getText().toString());
                    new AlertDialog.Builder(this)
                            .setTitle(r.block ? "会被拦截 🚫" : "会放行 ✅")
                            .setMessage("原因：" + r.reason)
                            .setPositiveButton("知道了", null)
                            .show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRegionDialog() {
        final String[] provinces = RegionData.PROVINCES;
        new AlertDialog.Builder(this)
                .setTitle("第 1 步：选择省份")
                .setItems(provinces, (d, which) -> showCityDialog(which))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCityDialog(final int provinceId) {
        final String provinceName = RegionData.PROVINCES[provinceId];
        final List<String> cities = new ArrayList<>();
        for (int i = 0; i < RegionData.CITY_PROVINCE.length; i++) {
            if (RegionData.CITY_PROVINCE[i] == provinceId) {
                cities.add(RegionData.CITIES[i]);
            }
        }
        List<String> items = new ArrayList<>();
        items.add("整个省（" + provinceName + "）");
        items.addAll(cities);
        new AlertDialog.Builder(this)
                .setTitle("第 2 步：" + provinceName)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        RuleStore.addItem(this, RuleStore.K_REGIONS, "p:" + provinceName);
                        toast("已添加：" + provinceName + "（全省）");
                    } else {
                        String city = cities.get(which - 1);
                        RuleStore.addItem(this, RuleStore.K_REGIONS, "c:" + provinceName + "/" + city);
                        toast("已添加：" + provinceName + "-" + city);
                    }
                    refreshRules();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptInput(String title, String hint, Consumer<String> onOk) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_PHONE);
        et.setHint(hint);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("添加", (d, w) -> {
                    String v = Rules.digits(et.getText().toString());
                    if (v.isEmpty()) {
                        toast("请输入数字");
                        return;
                    }
                    onOk.accept(v);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
