package com.demo.controlcenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class MainActivity extends Activity {

    public static Context appContext;

    private float density;

    private static final String COLOR_BG     = "#F2F2F7";
    private static final String COLOR_CARD   = "#FFFFFF";
    private static final String COLOR_TEXT   = "#1C1C1E";
    private static final String COLOR_SUB    = "#8E8E93";
    private static final String COLOR_BLUE   = "#007AFF";
    private static final String COLOR_ORANGE = "#FF9500";
    private static final String COLOR_GREEN  = "#34C759";
    private static final String COLOR_RED    = "#FF3B30";
    private static final String COLOR_PURPLE = "#AF52DE";

    private static final int CARD_STROKE_COLOR = 0x0F000000;
    private static final int DIVIDER_COLOR     = 0xFFE5E5EA;

    private static final String SP_APP         = "app_config";
    private static final String K_FIRST_LAUNCH = "first_launch";

    private static final int REQ_STORAGE      = 1002;
    private static final int REQ_NOTIFICATION = 1001;

    private static final int BL_LOCKED   =  1;
    private static final int BL_UNLOCKED =  0;
    private static final int BL_UNKNOWN  = -1;

    private boolean rendered = false;
    private LinearLayout moduleListContainer = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        appContext = getApplicationContext();
        density = getResources().getDisplayMetrics().density;

        if (!hasAllFilesAccess()) {
            requestAllFilesAccess();
            return;
        }
        proceedAfterPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!hasAllFilesAccess()) {
            showPermissionDialog();
            return;
        }

        if (rendered) {
            new Thread(() -> {
                RootManager.get().refresh();
                runOnUiThread(() -> {
                    if (rendered) renderMainPage();
                });
            }).start();
        }
    }

    private boolean canTouchUi() {
        return !isFinishing() && !isDestroyed();
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    Toast.makeText(this, "无法打开权限设置页", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"
            }, REQ_STORAGE);
        }
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("本应用需要「所有文件访问权限」才能管理驱动文件。\n\n"
                        + "请点击「去授权」，在系统设置里打开权限。")
                .setCancelable(false)
                .setPositiveButton("去授权", (d, w) -> requestAllFilesAccess())
                .setNegativeButton("退出", (d, w) -> finishAffinity())
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0
                    && grantResults[0]
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                proceedAfterPermission();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("权限被拒绝")
                        .setMessage("没有存储权限，App 无法工作。")
                        .setCancelable(false)
                        .setPositiveButton("退出", (d, w) -> finishAffinity())
                        .show();
            }
        }
    }

    private void proceedAfterPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        REQ_NOTIFICATION);
            }
        }

        SharedPreferences sp = getSharedPreferences(SP_APP, MODE_PRIVATE);

        if (sp.getBoolean(K_FIRST_LAUNCH, true)) {
            try {
                Class<?> clazz = Class.forName(
                        "com.demo.controlcenter.OnboardingActivity");
                startActivity(new Intent(MainActivity.this, clazz));
            } catch (Exception e) {
                renderMainPage();
            }
            finish();
            return;
        }

        renderMainPage();

        new Thread(() -> {
            RootManager.get().refresh();
            runOnUiThread(() -> {
                if (rendered) renderMainPage();
            });
        }).start();
    }

    private void renderMainPage() {
        if (!canTouchUi()) return;
        rendered = true;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor(COLOR_BG));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(buildTopBar());
        root.addView(buildDeviceEnvCard());

        final String[][] coreOps = {
                {"刷入驱动", "选择驱动包，一键刷入内核", COLOR_BLUE,   "upload"},
                {"驱动管理", "查看已刷入驱动，启用/删除", COLOR_ORANGE, "manage"},
                {"内核配置", "调整内核参数与启动模式",   COLOR_PURPLE, "config"},
                {"模块开关", "启用/禁用内核模块",         COLOR_GREEN,  "toggle"}
        };

        for (int i = 0; i < coreOps.length; i++) {
            final int index = i;
            LinearLayout row = buildFunctionRow(
                    coreOps[i][0], coreOps[i][1], coreOps[i][2], coreOps[i][3]);
            row.setOnClickListener(v -> onCoreOpClick(index));
            root.addView(row);
        }

        moduleListContainer = new LinearLayout(this);
        moduleListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        moduleListContainer.setLayoutParams(lp);
        root.addView(moduleListContainer);

        refreshModules(moduleListContainer);

        TextView btnRefresh = buildBottomButton("刷新检测");
        btnRefresh.setOnClickListener(v -> {
            vibrate();
            new Thread(() -> {
                RootManager.get().refresh();
                runOnUiThread(() -> {
                    if (rendered) renderMainPage();
                });
            }).start();
        });
        root.addView(btnRefresh);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    private void onCoreOpClick(int index) {
        vibrate();

        switch (index) {
            case 0:
                openActivity("com.demo.controlcenter.FlashActivity",
                        "打开刷入页面失败");
                break;
            case 1:
                openActivity("com.demo.controlcenter.ManageActivity",
                        "打开管理页面失败");
                break;
            case 2:
                openActivity("com.demo.controlcenter.KernelConfigActivity",
                        "打开内核配置失败");
                break;
            case 3:
                Toast.makeText(this, "模块开关开发中", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private void openActivity(String className, String errMsg) {
        try {
            Class<?> clazz = Class.forName(className);
            startActivity(new Intent(MainActivity.this, clazz));
        } catch (Exception e) {
            Toast.makeText(this, errMsg, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshModules(LinearLayout container) {
        if (container == null) return;
        CoreManager.send(this, CoreManager.CMD_SCAN, null, new CoreManager.Receiver() {
            @Override
            public void onReceive(CoreManager.Packet packet) {
                if (packet.cmd != CoreManager.CMD_SCAN) return;
                container.removeAllViews();

                if (packet.modules.isEmpty()) {
                    container.addView(buildEmptyState());
                } else {
                    for (int i = 0; i < packet.modules.size(); i++) {
                        CoreManager.ModuleInfo m = packet.modules.get(i);
                        LinearLayout card = buildFunctionRow(
                                m.name, "已刷入 | 点击删除", COLOR_BLUE, "manage");
                        final String path = m.path;
                        final String name = m.name;
                        card.setOnClickListener(v -> onModuleClick(path, name));
                        animateIn(card, 100 + i * 100);
                        container.addView(card);
                    }
                }
            }
        });
    }

    private void onModuleClick(String path, String name) {
        new AlertDialog.Builder(this)
                .setTitle("删除驱动")
                .setMessage("确定删除 [" + name + "] 吗？")
                .setPositiveButton("删除", (d, w) ->
                        CoreManager.send(this, CoreManager.CMD_DELETE,
                                new Object[]{path}, new CoreManager.Receiver() {
                                    @Override
                                    public void onReceive(CoreManager.Packet packet) {
                                        Toast.makeText(MainActivity.this,
                                                packet.message,
                                                Toast.LENGTH_SHORT).show();
                                        if (rendered) renderMainPage();
                                    }
                                }))
                .setNegativeButton("取消", null)
                .show();
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        icon.setBackground(createRoundRect("#E3F0FF", dp(12)));
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setImageResource(android.R.drawable.ic_menu_manage);
        bar.addView(icon);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(12));
        title.setLayoutParams(tp);
        title.setText("驱动管理");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor(COLOR_TEXT));
        title.setTypeface(null, Typeface.BOLD);
        bar.addView(title);

        TextView status = new TextView(this);
        status.setTextSize(12);

        RootEnv.Info info = RootManager.get().getInfo();
        if (info.mode == RootEnv.MODE_GRANTED) {
            status.setText(info.typeName + " 已授权");
            status.setTextColor(Color.parseColor(COLOR_GREEN));
        } else if (info.mode == RootEnv.MODE_DENIED) {
            status.setText("待授权");
            status.setTextColor(Color.parseColor(COLOR_ORANGE));
        } else if (info.mode == RootEnv.MODE_NO_ROOT) {
            status.setText("未 Root");
            status.setTextColor(Color.parseColor(COLOR_ORANGE));
        } else {
            status.setText("检测中...");
            status.setTextColor(Color.parseColor(COLOR_SUB));
        }
        bar.addView(status);
        return bar;
    }

    private LinearLayout buildDeviceEnvCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(16);
        card.setLayoutParams(cp);
        applyCardStyle(card);

        RootEnv.Info info = RootManager.get().getInfo();

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout modeLayout = new LinearLayout(this);
        modeLayout.setOrientation(LinearLayout.VERTICAL);
        TextView modeLabel = new TextView(this);
        modeLabel.setText("工作模式");
        modeLabel.setTextSize(12);
        modeLabel.setTextColor(Color.parseColor(COLOR_SUB));
        modeLayout.addView(modeLabel);

        TextView modeValue = new TextView(this);
        modeValue.setTextSize(20);
        modeValue.setTypeface(null, Typeface.BOLD);
        if (info.mode == RootEnv.MODE_GRANTED) {
            modeValue.setText("已授权");
            modeValue.setTextColor(Color.parseColor(COLOR_GREEN));
        } else if (info.mode == RootEnv.MODE_DENIED) {
            modeValue.setText("待授权");
            modeValue.setTextColor(Color.parseColor(COLOR_ORANGE));
        } else if (info.mode == RootEnv.MODE_NO_ROOT) {
            modeValue.setText("只读模式");
            modeValue.setTextColor(Color.parseColor(COLOR_ORANGE));
        } else {
            modeValue.setText("检测中");
            modeValue.setTextColor(Color.parseColor(COLOR_SUB));
        }
        modeLayout.addView(modeValue);
        row1.addView(modeLayout);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row1.addView(spacer);

        if (info.mode == RootEnv.MODE_GRANTED) {
            row1.addView(makeBadge("● " + info.typeName, COLOR_GREEN, "#E8F8EC"));
        } else if (info.mode == RootEnv.MODE_DENIED) {
            row1.addView(makeBadge("● 未授权", COLOR_ORANGE, "#FFF3E0"));
        }
        card.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp2.topMargin = dp(20);
        row2.setLayoutParams(rp2);

        row2.addView(buildInfoColumn("内核", KernelDetector.getKernelVersion()));
        row2.addView(buildInfoColumn("品牌", Build.BRAND));
        row2.addView(buildInfoColumn("名称", Build.MODEL));
        row2.addView(buildInfoColumn("架构", Build.SUPPORTED_ABIS[0]));
        card.addView(row2);

        View divider = new View(this);
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dp1.topMargin = dp(20);
        divider.setLayoutParams(dp1);
        divider.setBackgroundColor(DIVIDER_COLOR);
        card.addView(divider);

        TextView envTitle = new TextView(this);
        envTitle.setText("环境检测");
        envTitle.setTextSize(15);
        envTitle.setTextColor(Color.parseColor(COLOR_TEXT));
        envTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams etp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        etp.topMargin = dp(16);
        envTitle.setLayoutParams(etp);
        card.addView(envTitle);

        final boolean isRoot = (info.mode == RootEnv.MODE_GRANTED);

        String se = getSelinuxStatus();
        boolean seOk = "强制".equals(se);
        card.addView(buildStatusItem("SELinux", se,
                seOk ? COLOR_GREEN : COLOR_RED));

        boolean rw = isSystemRW();
        card.addView(buildStatusItem("System 分区",
                rw ? "可写" : "只读",
                rw ? COLOR_RED : COLOR_GREEN));

        int blState = getBootloaderState();
        String blText;
        String blColor;
        if (blState == BL_UNKNOWN) {
            blText  = "未知";
            blColor = COLOR_SUB;
        } else {
            boolean locked = (blState == BL_LOCKED);
            blText  = locked ? "已锁" : "已解锁";
            blColor = locked ? COLOR_GREEN : COLOR_RED;
        }
        card.addView(buildStatusItem("Bootloader", blText, blColor));

        boolean zygisk = isZygiskPresent();
        String zygText  = zygisk ? "检测到" : "未检测到";
        String zygColor;
        if (!isRoot) {
            zygColor = COLOR_SUB;
        } else {
            zygColor = zygisk ? COLOR_ORANGE : COLOR_GREEN;
        }
        card.addView(buildStatusItem("Zygisk", zygText, zygColor));

        return card;
    }

    private TextView makeBadge(String text, String colorHex, String bgHex) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTextSize(12);
        badge.setTextColor(Color.parseColor(colorHex));
        badge.setPadding(dp(12), dp(6), dp(12), dp(6));
        badge.setBackground(createRoundRect(bgHex, dp(12)));
        return badge;
    }

    private static String getProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = c.getMethod("get", String.class);
            Object r = m.invoke(null, key);
            return r == null ? null : r.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private String getSelinuxStatus() {
        String prop = getProp("ro.boot.selinux");
        if (prop != null) {
            prop = prop.trim().toLowerCase();
            if (prop.contains("enforcing")) return "强制";
            if (prop.contains("permissive")) return "宽容";
        }
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/sys/fs/selinux/enforce"))) {
            String line = reader.readLine();
            if ("1".equals(line)) return "强制";
            if ("0".equals(line)) return "宽容";
        } catch (Exception ignored) {}
        return "未知";
    }

    private int getBootloaderState() {
        String s = getProp("ro.boot.flash.locked");
        if (s != null) {
            s = s.trim();
            if ("1".equals(s)) return BL_LOCKED;
            if ("0".equals(s)) return BL_UNLOCKED;
        }

        String secure = getProp("ro.secure");
        String debug  = getProp("ro.debuggable");
        if (secure != null && debug != null) {
            if ("1".equals(secure.trim()) && "0".equals(debug.trim()))
                return BL_LOCKED;
        }

        try (BufferedReader r = new BufferedReader(
                new FileReader("/proc/cmdline"))) {
            String line = r.readLine();
            if (line != null) {
                if (line.contains("androidboot.flash.locked=1")) return BL_LOCKED;
                if (line.contains("androidboot.flash.locked=0")) return BL_UNLOCKED;
            }
        } catch (Throwable ignored) {}

        return BL_UNKNOWN;
    }

    private boolean isSystemRW() {
        return isMountRW("/system")
                || isMountRW("/")
                || isMountRW("/vendor");
    }

    private boolean isMountRW(String mountPoint) {
        if (readMountsRW(mountPoint, "/proc/mounts")) return true;
        return isMountRWFromMountInfo(mountPoint);
    }

    private boolean readMountsRW(String mountPoint, String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue;
                if (!mountPoint.equals(parts[1])) continue;

                String opts = parts[3];
                for (String o : opts.split(",")) {
                    if ("ro".equals(o)) return false;
                    if ("rw".equals(o)) return true;
                }
                return opts.contains("rw");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isMountRWFromMountInfo(String mountPoint) {
        try (BufferedReader r = new BufferedReader(
                new FileReader("/proc/self/mountinfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 6) continue;
                if (!mountPoint.equals(parts[4])) continue;
                String opts = parts[5];
                for (String o : opts.split(",")) {
                    if ("ro".equals(o)) return false;
                    if ("rw".equals(o)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isZygiskPresent() {
        if (new File("/data/adb/zygisk").exists()) return true;

        File[] mods = new File("/data/adb/modules").listFiles();
        if (mods != null) {
            for (File m : mods) {
                if (m == null) continue;
                if (m.getName().toLowerCase().contains("zygisk")) return true;
            }
        }

        if (new File("/data/adb/ksu").exists()) {
            File[] ksuMods = new File("/data/adb/ksu/modules").listFiles();
            if (ksuMods != null) {
                for (File m : ksuMods) {
                    if (m == null) continue;
                    if (m.getName().toLowerCase().contains("zygisk"))
                        return true;
                }
            }
        }

        if (new File("/data/adb/magisk/magiskd.socket").exists()) {
            return true;
        }
        if (new File("/sbin/.magisk/magiskd.socket").exists()) {
            return true;
        }

        return false;
    }

    private LinearLayout buildStatusItem(String label, String value, String colorHex) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        row.setLayoutParams(rp);

        View dot = new View(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
        dot.setBackground(createRoundRect(colorHex, dp(4)));
        row.addView(dot);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(14);
        tvLabel.setTextColor(Color.parseColor(COLOR_TEXT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(12));
        tvLabel.setLayoutParams(lp);
        row.addView(tvLabel);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextSize(13);
        tvValue.setTextColor(Color.parseColor(COLOR_SUB));
        row.addView(tvValue);
        return row;
    }

    private LinearLayout buildFunctionRow(String title, String desc,
                                          String colorHex, String type) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        row.setLayoutParams(rp);

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        icon.setBackground(createRoundRect(colorHex, dp(14)));
        icon.setPadding(dp(14), dp(14), dp(14), dp(14));
        icon.setImageResource(getIconByType(type));
        row.addView(icon);

        LinearLayout textLayout = new LinearLayout(this);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(16));
        textLayout.setLayoutParams(tp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(17);
        tvTitle.setTextColor(Color.parseColor(COLOR_TEXT));
        tvTitle.setTypeface(null, Typeface.BOLD);
        textLayout.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextSize(13);
        tvDesc.setTextColor(Color.parseColor(COLOR_SUB));
        textLayout.addView(tvDesc);
        row.addView(textLayout);

        ImageView arrow = new ImageView(this);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        arrow.setImageResource(android.R.drawable.ic_media_play);
        arrow.setAlpha(0.3f);
        row.addView(arrow);

        applyCardStyle(row);
        setPressFeedback(row);
        return row;
    }

    private int getIconByType(String type) {
        switch (type) {
            case "upload": return android.R.drawable.ic_menu_upload;
            case "manage": return android.R.drawable.ic_menu_manage;
            case "config": return android.R.drawable.ic_menu_preferences;
            case "toggle": return android.R.drawable.checkbox_on_background;
            default:       return android.R.drawable.ic_menu_agenda;
        }
    }

    private LinearLayout buildEmptyState() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(60), 0, dp(60));

        TextView tv = new TextView(this);
        tv.setText("暂未检测到已刷入的驱动");
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor(COLOR_SUB));
        tv.setGravity(Gravity.CENTER);
        empty.addView(tv);
        return empty;
    }

    private LinearLayout buildInfoColumn(String label, String value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(10);
        tvLabel.setTextColor(Color.parseColor(COLOR_SUB));
        tvLabel.setGravity(Gravity.CENTER);
        col.addView(tvLabel);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextSize(12);
        tvValue.setTextColor(Color.parseColor(COLOR_TEXT));
        tvValue.setGravity(Gravity.CENTER);
        tvValue.setMaxLines(1);
        tvValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tvValue);
        return col;
    }

    private TextView buildBottomButton(String text) {
        TextView btn = new TextView(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        p.topMargin = dp(24);
        p.bottomMargin = dp(8);
        btn.setLayoutParams(p);
        btn.setText(text);
        btn.setTextSize(16);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(createRoundRect(COLOR_BLUE, dp(28)));
        setPressFeedback(btn);
        return btn;
    }

    private void applyCardStyle(View view) {
        GradientDrawable bg = createRoundRect(COLOR_CARD, dp(20));
        bg.setStroke(dp(1), CARD_STROKE_COLOR);
        view.setBackground(bg);
        view.setElevation(dp(2));
    }

    private void setPressFeedback(final View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private float downX = 0;
            private float downY = 0;
            private boolean moved = false;
            private final float THRESHOLD = 24f;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        moved = false;
                        v.animate().scaleX(0.98f).scaleY(0.98f).alpha(0.9f)
                                .setDuration(80).start();
                        vibrate();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getX() - downX) > THRESHOLD
                                || Math.abs(event.getY() - downY) > THRESHOLD) {
                            if (!moved) {
                                moved = true;
                                v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                        .setDuration(100).start();
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(200).start();
                        if (!moved) v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(200).start();
                        return true;
                }
                return false;
            }
        });
    }

    private void vibrate() {
        try {
            View root = findViewById(android.R.id.content);
            if (root != null) {
                root.performHapticFeedback(
                        HapticFeedbackConstants.VIRTUAL_KEY,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        } catch (Exception ignored) {}
    }

    private void animateIn(View view, long delay) {
        view.setTranslationY(dp(60));
        view.setAlpha(0f);
        view.animate().translationY(0f).alpha(1f)
                .setStartDelay(delay)
                .setDuration(700)
                .setInterpolator(new OvershootInterpolator(1.5f))
                .start();
    }

    private GradientDrawable createRoundRect(String colorHex, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Color.parseColor(colorHex));
        d.setCornerRadius(radiusPx);
        return d;
    }

    private int dp(float dpValue) {
        return (int) (dpValue * density + 0.5f);
    }
}