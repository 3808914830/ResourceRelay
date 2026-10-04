package com.demo.controlcenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ManageActivity extends Activity {

    private float density;
    private final Handler MAIN = new Handler(Looper.getMainLooper());
    private final ExecutorService IO_POOL = Executors.newSingleThreadExecutor();
    private LinearLayout listContainer;

    private static final String COLOR_BG     = "#F2F2F7";
    private static final String COLOR_CARD   = "#FFFFFF";
    private static final String COLOR_TEXT   = "#1C1C1E";
    private static final String COLOR_SUB    = "#8E8E93";
    private static final String COLOR_BLUE   = "#007AFF";
    private static final String COLOR_GREEN  = "#34C759";
    private static final String COLOR_RED    = "#FF3B30";
    private static final String COLOR_ORANGE = "#FF9500";
    private static final String COLOR_PURPLE = "#AF52DE";

    private static final String BG_BLUE_LIGHT   = "#E3F0FF";
    private static final String BG_ORANGE_LIGHT = "#FFF3E0";
    private static final String BG_PURPLE_LIGHT = "#F3E8FF";
    private static final String BG_GRAY_LIGHT   = "#EEEEEE";

    private static final int CARD_STROKE_COLOR = 0x0F000000;

    private static final String[] USER_SCAN_PATHS = {
            "/data/adb/modules/",
            "/data/adb/ksu/modules/",
            "/data/adb/ap/modules/"
    };

    private static final String[] SYSTEM_SCAN_PATHS = {
            "/vendor/lib/modules/",
            "/system/lib/modules/",
            "/lib/modules/"
    };

    private static final String[] CUSTOM_SCAN_PATHS = {
            "/sdcard/DriverModules/"
    };

    private static final String BACKUP_DIR          = "/sdcard/DriverBackup";
    private static final String CONFIRM_WORD        = "DELETE";
    private static final long   MAX_DIR_SIZE_BYTES  = 500L * 1024 * 1024;
    private static final int    MAX_SCAN_DEPTH      = 6;
    private static final int    MAX_SCAN_NODES      = 2000;
    private static final int    MAX_PATH_LENGTH     = 4096;
    private static final long   PLACEHOLDER_KO_SIZE = 1024L;

    private static final int PLACEHOLDER_UNKNOWN = -1;
    private static final int PLACEHOLDER_NO      =  0;
    private static final int PLACEHOLDER_YES     =  1;

    private final AtomicBoolean scanning      = new AtomicBoolean(false);
    private volatile boolean    destroyed     = false;
    private volatile boolean    firstScanDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        buildUI();
        scanModules();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (destroyed) return;
        if (!firstScanDone && !scanning.get()) {
            scanModules();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        MAIN.removeCallbacksAndMessages(null);
        scanning.set(false);
        try { IO_POOL.shutdownNow(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private boolean canTouchUi() {
        return !destroyed && !isFinishing() && !isDestroyed();
    }

    private void safeShow(AlertDialog d) {
        if (d == null) return;
        if (!canTouchUi()) { try { d.dismiss(); } catch (Throwable ignored) {} return; }
        try { d.show(); } catch (Throwable ignored) {}
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(COLOR_BG));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(buildTopBar());
        root.addView(buildTitle());

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.addView(listContainer);
        root.addView(scroll);

        setContentView(root);
    }

    private LinearLayout buildTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView btnBack = new TextView(this);
        btnBack.setText("‹ 返回");
        btnBack.setTextSize(16);
        btnBack.setTextColor(Color.parseColor(COLOR_BLUE));
        btnBack.setContentDescription("返回");
        btnBack.setOnClickListener(v -> { vibrate(); finish(); });
        topBar.addView(btnBack);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        topBar.addView(spacer);

        TextView btnRefresh = new TextView(this);
        btnRefresh.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        btnRefresh.setText("↻");
        btnRefresh.setTextSize(18);
        btnRefresh.setTextColor(Color.parseColor(COLOR_BLUE));
        btnRefresh.setGravity(Gravity.CENTER);
        btnRefresh.setBackground(createRoundRect(BG_BLUE_LIGHT, dp(10)));
        btnRefresh.setContentDescription("刷新驱动列表");
        btnRefresh.setOnClickListener(v -> { vibrate(); scanModules(); });
        setPressFeedback(btnRefresh);
        topBar.addView(btnRefresh);

        return topBar;
    }

    private TextView buildTitle() {
        TextView title = new TextView(this);
        title.setText("驱动管理");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor(COLOR_TEXT));
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(8);
        tp.bottomMargin = dp(16);
        title.setLayoutParams(tp);
        return title;
    }

    private void scanModules() {
        if (destroyed) return;
        if (!scanning.compareAndSet(false, true)) return;

        if (listContainer == null) { scanning.set(false); return; }

        listContainer.removeAllViews();
        addHint("正在扫描驱动...");

        IO_POOL.execute(() -> {
            List<ModuleEntry> userList   = new ArrayList<>();
            List<ModuleEntry> systemList = new ArrayList<>();
            List<ModuleEntry> customList = new ArrayList<>();

            try {
                Set<String> seenCanonical = new HashSet<>();
                scanPaths(USER_SCAN_PATHS,   userList,   seenCanonical);
                scanPaths(SYSTEM_SCAN_PATHS, systemList, seenCanonical);
                scanPaths(CUSTOM_SCAN_PATHS, customList, seenCanonical);

                sortByName(userList);
                sortByName(systemList);
                sortByName(customList);
            } catch (Throwable t) {
                scanning.set(false);
                firstScanDone = true;
                MAIN.post(() -> {
                    if (!canTouchUi() || listContainer == null) return;
                    listContainer.removeAllViews();
                    addHint("扫描出错: " + safeMsg(t));
                });
                return;
            }

            final List<ModuleEntry> fu = userList;
            final List<ModuleEntry> fs = systemList;
            final List<ModuleEntry> fc = customList;

            MAIN.post(() -> {
                scanning.set(false);
                firstScanDone = true;

                if (!canTouchUi() || listContainer == null) return;

                if (fu.isEmpty() && fs.isEmpty() && fc.isEmpty()) {
                    listContainer.removeAllViews();
                    addHint("未检测到任何驱动");
                    return;
                }
                renderModules(fu, fs, fc);
            });
        });
    }

    private String safeMsg(Throwable t) {
        if (t == null) return "未知";
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private void scanPaths(String[] paths, List<ModuleEntry> out,
                           Set<String> seenCanonical) {
        if (destroyed || paths == null) return;

        for (String basePath : paths) {
            if (destroyed) return;
            if (!isSafeBasePath(basePath)) continue;

            File dir = new File(basePath);
            if (!dir.exists() || !dir.isDirectory()) continue;

            File[] files;
            try { files = dir.listFiles(); }
            catch (Throwable t) { continue; }
            if (files == null) continue;

            for (File f : files) {
                if (destroyed) return;
                if (f == null) continue;

                String canonical = canonicalPath(f);
                if (canonical == null) continue;
                if (!seenCanonical.add(canonical)) continue;

                ModuleEntry e = createEntry(f, basePath);
                if (e != null) out.add(e);
            }
        }
    }

    private boolean isSafeBasePath(String p) {
        if (p == null || p.isEmpty()) return false;
        if (p.length() > MAX_PATH_LENGTH) return false;
        if (p.indexOf('\0') >= 0) return false;
        if (p.contains("/../") || p.endsWith("/..")) return false;
        return true;
    }

    private String canonicalPath(File f) {
        try {
            String c = f.getCanonicalPath();
            if (c == null || c.length() > MAX_PATH_LENGTH) return null;
            if (c.indexOf('\0') >= 0) return null;
            return c;
        } catch (Throwable e) {
            return null;
        }
    }

    private void sortByName(List<ModuleEntry> list) {
        if (list == null) return;
        Collections.sort(list, new Comparator<ModuleEntry>() {
            @Override
            public int compare(ModuleEntry a, ModuleEntry b) {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                String an = a.name == null ? "" : a.name;
                String bn = b.name == null ? "" : b.name;
                return an.compareToIgnoreCase(bn);
            }
        });
    }

    private ModuleEntry createEntry(File f, String basePath) {
        if (f == null) return null;

        String name;
        try { name = f.getName(); }
        catch (Throwable t) { return null; }

        if (TextUtils.isEmpty(name)) return null;
        if (name.length() > 255) return null;
        if (name.startsWith(".")) return null;
        if (name.indexOf('\0') >= 0) return null;
        if (name.indexOf('/') >= 0) return null;
        if (name.indexOf('\\') >= 0) return null;
        if ("lost+found".equals(name)) return null;

        ModuleEntry e = new ModuleEntry();
        e.path = f.getAbsolutePath();
        e.installTime = f.lastModified();

        try {
            if (f.isDirectory()) {
                e.name = name;
                e.isDir = true;
                e.type = detectType(basePath, true);
                e.enabled = !new File(f, "disable").exists();
                e.isSystem = isSystemPath(basePath);
                return e;
            }

            if (!f.isFile()) return null;

            if (name.endsWith(".ko") || name.endsWith(".ko.disabled")) {
                boolean disabled = name.endsWith(".ko.disabled");
                e.name = disabled
                        ? name.substring(0, name.length()
                                - ".ko.disabled".length()) + ".ko"
                        : name;
                e.isDir = false;
                e.type = detectType(basePath, false);
                e.enabled = !disabled;
                e.isSystem = isSystemPath(basePath);

                int r = detectPlaceholderKoResult(f);
                e.isPlaceholder        = (r == PLACEHOLDER_YES);
                e.isPlaceholderUnknown = (r == PLACEHOLDER_UNKNOWN);
                return e;
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    private int detectPlaceholderKoResult(File f) {
        if (f == null) return PLACEHOLDER_UNKNOWN;

        try {
            if (!f.canRead()) return PLACEHOLDER_UNKNOWN;
        } catch (Throwable t) {
            return PLACEHOLDER_UNKNOWN;
        }

        long len;
        try { len = f.length(); }
        catch (Throwable t) { return PLACEHOLDER_UNKNOWN; }

        if (len < PLACEHOLDER_KO_SIZE) return PLACEHOLDER_YES;

        byte[] header = new byte[4];
        try (FileInputStream fis = new FileInputStream(f)) {
            if (fis.read(header) != 4) return PLACEHOLDER_UNKNOWN;
        } catch (Throwable t) {
            return PLACEHOLDER_UNKNOWN;
        }

        boolean isElf = header[0] == 0x7F
                && header[1] == 'E'
                && header[2] == 'L'
                && header[3] == 'F';
        return isElf ? PLACEHOLDER_NO : PLACEHOLDER_YES;
    }

    private String detectType(String basePath, boolean isDir) {
        if (basePath == null) return isDir ? "模块" : "内核驱动";
        if (basePath.endsWith("/data/adb/modules/"))    return "Magisk 模块";
        if (basePath.endsWith("/data/adb/ksu/modules/")) return "KernelSU 模块";
        if (basePath.endsWith("/data/adb/ap/modules/"))  return "APatch 模块";
        if (basePath.endsWith("/vendor/lib/modules/"))   return "内核驱动";
        if (basePath.endsWith("/system/lib/modules/"))   return "系统驱动";
        if (basePath.endsWith("/sdcard/DriverModules/")) return "自定义驱动";
        if (basePath.endsWith("/lib/modules/"))          return "系统驱动";
        return isDir ? "模块" : "内核驱动";
    }

    private boolean isSystemPath(String basePath) {
        if (basePath == null) return false;
        return basePath.endsWith("/vendor/lib/modules/")
                || basePath.endsWith("/system/lib/modules/")
                || basePath.endsWith("/lib/modules/");
    }

    private boolean isCustomPath(String path) {
        if (path == null) return false;
        if (path.startsWith("/sdcard/")) return true;
        if (path.startsWith("/storage/emulated/")) return true;
        if (path.startsWith("/mnt/sdcard/")) return true;
        return false;
    }

    private void renderModules(List<ModuleEntry> userList,
                               List<ModuleEntry> systemList,
                               List<ModuleEntry> customList) {
        if (!canTouchUi() || listContainer == null) return;
        listContainer.removeAllViews();

        addSectionHeader("系统驱动",
                "系统内置 · 删除风险极高 · 请谨慎操作", COLOR_ORANGE);
        if (systemList == null || systemList.isEmpty()) {
            addSectionEmpty("未检测到系统驱动");
        } else {
            for (int i = 0; i < systemList.size(); i++) {
                if (!canTouchUi()) return;
                LinearLayout card = buildModuleCard(systemList.get(i));
                if (card != null) {
                    if (i < 12) animateIn(card, 40 + i * 50);
                    listContainer.addView(card);
                }
            }
        }

        addSectionHeader("用户驱动",
                "你刷入的模块 · 可自由管理", COLOR_BLUE);
        if (userList == null || userList.isEmpty()) {
            addSectionEmpty("暂未刷入任何用户驱动\n\n（需要 Root 才会有此区域的内容）");
        } else {
            for (int i = 0; i < userList.size(); i++) {
                if (!canTouchUi()) return;
                LinearLayout card = buildModuleCard(userList.get(i));
                if (card != null) {
                    if (i < 12) animateIn(card, 40 + i * 50);
                    listContainer.addView(card);
                }
            }
        }

        if (customList != null && !customList.isEmpty()) {
            addSectionHeader("自定义驱动",
                    "来自 /sdcard/DriverModules/ · 只读", COLOR_PURPLE);
            for (int i = 0; i < customList.size(); i++) {
                if (!canTouchUi()) return;
                LinearLayout card = buildModuleCard(customList.get(i));
                if (card != null) {
                    if (i < 12) animateIn(card, 40 + i * 50);
                    listContainer.addView(card);
                }
            }
        }
    }

    private void addSectionHeader(String title, String sub, String accentColor) {
        if (!canTouchUi() || listContainer == null) return;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(24);
        hp.bottomMargin = dp(4);
        header.setLayoutParams(hp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(6), dp(6)));
        dot.setBackground(createRoundRect(accentColor, dp(3)));
        row.addView(dot);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTextColor(Color.parseColor(COLOR_TEXT));
        tvTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.setMarginStart(dp(8));
        tvTitle.setLayoutParams(tp);
        row.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(sub);
        tvSub.setTextSize(11);
        tvSub.setTextColor(Color.parseColor(COLOR_SUB));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.setMarginStart(dp(8));
        tvSub.setLayoutParams(sp);
        row.addView(tvSub);

        header.addView(row);
        listContainer.addView(header);
    }

    private void addSectionEmpty(String text) {
        if (!canTouchUi() || listContainer == null) return;

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor(COLOR_SUB));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(24), 0, dp(24));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        tv.setLayoutParams(p);
        listContainer.addView(tv);
    }

    private LinearLayout buildModuleCard(final ModuleEntry e) {
        if (!canTouchUi()) return null;
        if (e == null
                || TextUtils.isEmpty(e.name)
                || TextUtils.isEmpty(e.path)) {
            return null;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        card.setLayoutParams(cp);
        applyCardStyle(card);

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        String bg;
        if (e.isSystem)      bg = BG_ORANGE_LIGHT;
        else if (e.isDir)    bg = BG_BLUE_LIGHT;
        else                 bg = BG_PURPLE_LIGHT;
        icon.setBackground(createRoundRect(bg, dp(12)));
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));
        icon.setImageResource(e.isDir
                ? android.R.drawable.ic_menu_agenda
                : android.R.drawable.ic_menu_compass);
        card.addView(icon);

        LinearLayout textLayout = new LinearLayout(this);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(16));
        textLayout.setLayoutParams(tp);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setText(e.name);
        tvName.setTextSize(16);
        tvName.setTextColor(Color.parseColor(COLOR_TEXT));
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setMaxLines(1);
        tvName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        nameRow.addView(tvName);

        if (e.isSystem) {
            nameRow.addView(makeTag("系统", COLOR_ORANGE, BG_ORANGE_LIGHT));
        }
        if (e.isPlaceholder) {
            nameRow.addView(makeTag("占位", COLOR_SUB, BG_GRAY_LIGHT));
        } else if (e.isPlaceholderUnknown) {
            nameRow.addView(makeTag("未知", COLOR_SUB, BG_GRAY_LIGHT));
        }
        if (isCustomPath(e.path)) {
            nameRow.addView(makeTag("自定义", COLOR_PURPLE, BG_PURPLE_LIGHT));
        }

        textLayout.addView(nameRow);

        String statusText;
        if (e.isPlaceholder) {
            statusText = e.type + " · 占位模块 · 无实际功能";
        } else if (e.isPlaceholderUnknown) {
            statusText = e.type + " · 状态未知 · 无法读取文件头";
        } else if (e.isSystem) {
            statusText = e.type + " · 系统内置 · "
                    + (e.enabled ? "已启用" : "已禁用");
        } else {
            statusText = e.type + " · " + (e.enabled ? "已启用" : "已禁用")
                    + " · " + formatTime(e.installTime);
        }

        TextView tvInfo = new TextView(this);
        tvInfo.setText(statusText);
        tvInfo.setTextSize(12);
        tvInfo.setTextColor(
                e.isPlaceholder || e.isPlaceholderUnknown
                        ? Color.parseColor(COLOR_SUB)
                        : e.enabled ? Color.parseColor(COLOR_GREEN)
                        : Color.parseColor(COLOR_SUB));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(4);
        tvInfo.setLayoutParams(ip);
        textLayout.addView(tvInfo);

        card.addView(textLayout);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(24);
        arrow.setTextColor(Color.parseColor(COLOR_SUB));
        card.addView(arrow);

        card.setContentDescription("驱动: " + e.name);
        card.setOnClickListener(v -> {
            if (!canTouchUi()) return;
            vibrate();
            if (!RootManager.get().canWrite()) {
                showModuleMenuReadOnly(e);
            } else if (isCustomPath(e.path)) {
                showCustomMenuReadOnly(e);
            } else {
                showModuleMenu(e);
            }
        });

        setPressFeedback(card);
        return card;
    }

    private TextView makeTag(String text, String textColor, String bgColor) {
        TextView tag = new TextView(this);
        tag.setText(text);
        tag.setTextSize(10);
        tag.setTextColor(Color.parseColor(textColor));
        tag.setPadding(dp(6), dp(2), dp(6), dp(2));
        tag.setBackground(createRoundRect(bgColor, dp(6)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMarginStart(dp(8));
        tag.setLayoutParams(p);
        return tag;
    }

    private void showModuleMenu(final ModuleEntry e) {
        if (!canTouchUi() || e == null) return;
        String[] actions = {"启用/禁用", "删除", "查看详情"};
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(actions, (dialog, which) -> {
                    if (!canTouchUi()) return;
                    switch (which) {
                        case 0: toggleModule(e);   break;
                        case 1: confirmDelete(e);  break;
                        case 2: showDetail(e);     break;
                    }
                })
                .create();
        safeShow(d);
    }

    private void showModuleMenuReadOnly(final ModuleEntry e) {
        if (!canTouchUi() || e == null) return;
        String[] actions = {"查看详情", "如何获取 Root 权限"};
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(actions, (dialog, which) -> {
                    if (!canTouchUi()) return;
                    if (which == 0) showDetail(e);
                    else showRootHint();
                })
                .create();
        safeShow(d);
    }

    private void showCustomMenuReadOnly(final ModuleEntry e) {
        if (!canTouchUi() || e == null) return;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage("此驱动来自 /sdcard/DriverModules/\n\n"
                        + "该目录为只读来源，仅用于浏览与手动刷入。\n"
                        + "如需删除，请直接在文件管理器中操作。")
                .setPositiveButton("查看详情", (dlg, w) -> showDetail(e))
                .setNegativeButton("知道了", null)
                .create();
        safeShow(d);
    }

    private void showRootHint() {
        if (!canTouchUi()) return;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("获取 Root 权限")
                .setMessage("需要先安装 Magisk / KernelSU / APatch "
                        + "并授予本应用 Root 权限，才能启用、禁用或删除驱动。")
                .setPositiveButton("知道了", null)
                .create();
        safeShow(d);
    }

    private void toggleModule(ModuleEntry e) {
        if (!canTouchUi() || e == null) return;
        if (!RootManager.get().requireRoot(this)) return;

        if (e.isSystem) {
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("⚠️ 系统驱动")
                    .setMessage("这是系统自带驱动。\n\n"
                            + "启用/禁用的实现是「重命名文件」，"
                            + "部分系统会因此在启动时校验失败，导致无法开机。\n\n"
                            + "确定继续吗？")
                    .setPositiveButton("继续", (dlg, w) -> doToggle(e))
                    .setNegativeButton("取消", null)
                    .create();
            safeShow(d);
        } else {
            doToggle(e);
        }
    }

    private void doToggle(final ModuleEntry e) {
        if (e == null) return;

        final String cmd;
        if (e.isDir) {
            String disablePath = e.path + "/disable";
            cmd = e.enabled
                    ? "touch " + shellEscape(disablePath)
                    : "rm -f " + shellEscape(disablePath);
        } else {
            if (e.enabled) {
                cmd = "mv " + shellEscape(e.path)
                        + " " + shellEscape(e.path + ".disabled");
            } else {
                if (e.path.endsWith(".disabled")) {
                    String base = e.path.substring(
                            0, e.path.length() - ".disabled".length());
                    cmd = "mv " + shellEscape(e.path)
                            + " " + shellEscape(base);
                } else {
                    cmd = "true";
                }
            }
        }

        RootHelper.runAsync(cmd, 10, new RootHelper.Callback() {
            @Override
            public void onResult(RootHelper.Result r) {
                MAIN.post(() -> {
                    if (!canTouchUi()) return;
                    Toast.makeText(ManageActivity.this,
                            r.ok() ? (e.enabled
                                    ? "已禁用: " + e.name
                                    : "已启用: " + e.name)
                                    : "操作失败",
                            Toast.LENGTH_SHORT).show();
                    scanModules();
                });
            }
        });
    }

    private void confirmDelete(final ModuleEntry e) {
        if (e == null) return;
        if (e.isSystem) confirmDeleteSystem(e);
        else            confirmDeleteUser(e);
    }

    private void confirmDeleteUser(final ModuleEntry e) {
        if (!canTouchUi()) return;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("删除驱动")
                .setMessage("确定删除 [" + e.name + "] 吗？\n\n此操作不可恢复！")
                .setPositiveButton("删除", (dlg, w) -> doDelete(e))
                .setNegativeButton("取消", null)
                .create();
        safeShow(d);
    }

    private void confirmDeleteSystem(final ModuleEntry e) {
        if (!canTouchUi()) return;

        String msg = "你正在尝试删除系统分区内的驱动：\n\n"
                + "  " + e.name + "\n\n"
                + "⚠️ 现代 Android（动态分区 / dm-verity / AVB）下，\n"
                + "直接 remount rw + 删除系统文件大概率会：\n"
                + "  • 启动校验失败 → 无法开机\n"
                + "  • 触发 Recovery / 变砖\n\n"
                + "正确做法是：\n"
                + "  • Magisk 用户：用「模块 overlayfs」覆盖，而非改原分区\n"
                + "  • 已解锁 BL + 已关 dm-verity 的用户：可尝试直删\n\n"
                + "本工具会先自动备份到：\n"
                + "  " + BACKUP_DIR + "\n\n"
                + "如果你不确定以上含义，请点「取消」。";

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("⚠️ 系统驱动 · 危险操作")
                .setMessage(msg)
                .setPositiveButton("我已了解风险",
                        (dlg, w) -> confirmDeleteSystemPhase2(e))
                .setNegativeButton("取消", null)
                .create();
        safeShow(d);
    }

    private void confirmDeleteSystemPhase2(final ModuleEntry e) {
        if (!canTouchUi() || e == null) return;

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setHint("请输入 " + CONFIRM_WORD + " 以确认");
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("二次确认")
                .setMessage("请输入大写字母 " + CONFIRM_WORD + " 以确认删除：")
                .setView(input)
                .setPositiveButton("确认删除", (dlg, w) -> {
                    String typed = input.getText().toString().trim();
                    if (!CONFIRM_WORD.equals(typed)) {
                        Toast.makeText(this,
                                "输入不匹配，已取消",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    doDeleteSystemWithBackup(e);
                })
                .setNegativeButton("取消", null)
                .create();
        safeShow(d);
    }

    private void doDelete(final ModuleEntry e) {
        if (e == null) return;

        if (e.isSystem) {
            confirmDeleteSystem(e);
            return;
        }

        if (!RootManager.get().requireRoot(this)) return;

        String cmd = "rm -rf " + shellEscape(e.path);

        RootHelper.runAsync(cmd, 15, new RootHelper.Callback() {
            @Override
            public void onResult(RootHelper.Result r) {
                MAIN.post(() -> {
                    if (!canTouchUi()) return;
                    Toast.makeText(ManageActivity.this,
                            r.ok() ? "已删除: " + e.name : "删除失败",
                            Toast.LENGTH_SHORT).show();
                    scanModules();
                });
            }
        });
    }

    private void doDeleteSystemWithBackup(final ModuleEntry e) {
        if (e == null) return;
        if (!RootManager.get().requireRoot(this)) return;

        final String backupName = "backup_" + sha1Short(e.path) + ".bak";
        final String backupPath = BACKUP_DIR + "/" + backupName;

        final String safeSrc   = shellEscape(e.path);
        final String safeBk    = shellEscape(backupPath);
        final String safeBkDir = shellEscape(BACKUP_DIR);

        String script =
                "SRC=" + safeSrc + "\n" +
                "BK=" + safeBk + "\n" +
                "BKDIR=" + safeBkDir + "\n" +
                "mkdir -p \"$BKDIR\" 2>/dev/null\n" +

                "mount -o remount,rw /system 2>/dev/null\n" +
                "mount -o remount,rw /vendor 2>/dev/null\n" +
                "mount -o remount,rw / 2>/dev/null\n" +

                "SRC_DIR=$(dirname \"$SRC\")\n" +
                "TMP_F=\"$SRC_DIR/.rw_test_$$\"\n" +
                "if ! touch \"$TMP_F\" 2>/dev/null; then\n" +
                "  echo 'REMOUNT_FAILED'\n" +
                "  exit 3\n" +
                "fi\n" +
                "rm -f \"$TMP_F\" 2>/dev/null\n" +

                "rm -rf \"$BK\" 2>/dev/null\n" +
                "if [ -d \"$SRC\" ]; then\n" +
                "  mkdir -p \"$BK\"\n" +
                "  cp -a \"$SRC\"/. \"$BK\"/ 2>&1\n" +
                "  SRC_N=$(find \"$SRC\" -type f 2>/dev/null | wc -l)\n" +
                "  DST_N=$(find \"$BK\"  -type f 2>/dev/null | wc -l)\n" +
                "else\n" +
                "  cp -a \"$SRC\" \"$BK\" 2>&1\n" +
                "  SRC_N=1\n" +
                "  if [ -e \"$BK\" ]; then DST_N=1; else DST_N=0; fi\n" +
                "fi\n" +

                "if [ \"$SRC_N\" -ne \"$DST_N\" ]; then\n" +
                "  echo \"BACKUP_COUNT_MISMATCH src=$SRC_N dst=$DST_N\"\n" +
                "  exit 2\n" +
                "fi\n" +

                "echo \"BACKUP_OK count=$DST_N\"\n" +

                "rm -rf \"$SRC\" 2>&1\n" +
                "sync\n" +

                "echo DONE\n";

        RootHelper.runAsync(script, 45, new RootHelper.Callback() {
            @Override
            public void onResult(RootHelper.Result r) {
                MAIN.post(() -> {
                    if (!canTouchUi()) return;

                    String out = r.all() == null ? "" : r.all();
                    boolean remountFailed = out.contains("REMOUNT_FAILED");
                    boolean mismatch      = out.contains("BACKUP_COUNT_MISMATCH");
                    boolean backupOk      = out.contains("BACKUP_OK");
                    boolean done          = out.contains("DONE");

                    if (remountFailed) {
                        Toast.makeText(ManageActivity.this,
                                "分区无法挂载为可写（现代 Android 常见），已中止删除。\n"
                                        + "建议改用 Magisk overlayfs 方案。",
                                Toast.LENGTH_LONG).show();
                    } else if (mismatch) {
                        Toast.makeText(ManageActivity.this,
                                "备份文件数不一致，已中止删除",
                                Toast.LENGTH_LONG).show();
                    } else if (!backupOk) {
                        Toast.makeText(ManageActivity.this,
                                "备份未通过校验，已中止删除",
                                Toast.LENGTH_LONG).show();
                    } else if (r.timeout) {
                        Toast.makeText(ManageActivity.this,
                                "操作超时。备份可能不完整，删除可能未执行。\n"
                                        + "请勿重启，先手动检查 " + backupPath,
                                Toast.LENGTH_LONG).show();
                    } else if (done) {
                        showRestartRequiredDialog(e.name, backupPath);
                    } else {
                        Toast.makeText(ManageActivity.this,
                                "删除过程异常，请检查详情",
                                Toast.LENGTH_LONG).show();
                    }
                    scanModules();
                });
            }
        });
    }

    private void showRestartRequiredDialog(String name, String backupPath) {
        if (!canTouchUi()) return;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("✅ 已删除 · 需要重启")
                .setMessage("已删除系统驱动：\n"
                        + "  " + name + "\n\n"
                        + "备份位置：\n"
                        + "  " + backupPath + "\n\n"
                        + "⚠️ 需要重启设备才能生效。\n\n"
                        + "重启后如果出现以下情况，请进入 Recovery 还原备份：\n"
                        + "  • 开机卡在 logo\n"
                        + "  • 反复重启\n"
                        + "  • 硬件功能异常\n\n"
                        + "是否立即重启？")
                .setPositiveButton("立即重启", (dlg, w) -> rebootDevice())
                .setNegativeButton("稍后手动重启", null)
                .setCancelable(false)
                .create();
        safeShow(d);
    }

    private void rebootDevice() {
        RootHelper.runAsync("sync; reboot", 10, new RootHelper.Callback() {
            @Override
            public void onResult(RootHelper.Result r) {
                MAIN.post(() -> {
                    if (!canTouchUi()) return;
                    if (!r.ok()) {
                        Toast.makeText(ManageActivity.this,
                                "重启失败，请手动重启",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private String sha1Short(String s) {
        if (s == null) return "null";
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            long h = s.hashCode() & 0xFFFFFFFFL;
            return String.format("%08x", h);
        }
    }

    private void showDetail(final ModuleEntry e) {
        if (!canTouchUi() || e == null) return;

        final String  name          = e.name == null ? "" : e.name;
        final String  path          = e.path == null ? "" : e.path;
        final String  type          = e.type == null ? "未知" : e.type;
        final boolean isSystem      = e.isSystem;
        final boolean isCustom      = isCustomPath(path);
        final boolean enabled       = e.enabled;
        final boolean isPlaceholder = e.isPlaceholder;
        final boolean isUnknown     = e.isPlaceholderUnknown;
        final long    installTime   = e.installTime;

        IO_POOL.execute(() -> {
            final long size = computeSizeSafely(new File(path));

            MAIN.post(() -> {
                if (!canTouchUi()) return;

                StringBuilder sb = new StringBuilder();
                sb.append("名称: ").append(name).append("\n");
                sb.append("类型: ").append(type).append("\n");
                sb.append("路径: ").append(path).append("\n");

                if (isSystem) {
                    sb.append("来源: 系统内置\n");
                } else if (isCustom) {
                    sb.append("来源: 自定义目录\n");
                } else {
                    sb.append("来源: 用户刷入\n");
                    sb.append("刷入时间: ").append(formatTime(installTime)).append("\n");
                }

                sb.append("状态: ").append(enabled ? "已启用" : "已禁用").append("\n");

                if (isPlaceholder) {
                    sb.append("提示: 该文件疑似占位模块（非真实内核驱动）\n");
                } else if (isUnknown) {
                    sb.append("提示: 无法读取文件头（可能无权限）\n");
                }

                sb.append("大小: ").append(formatSize(size)).append("\n");

                AlertDialog d = new AlertDialog.Builder(ManageActivity.this)
                        .setTitle("驱动详情")
                        .setMessage(sb.toString())
                        .setPositiveButton("确定", null)
                        .create();
                safeShow(d);
            });
        });
    }

    private String formatTime(long ms) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                    Locale.getDefault()).format(new java.util.Date(ms));
        } catch (Throwable t) {
            return "未知时间";
        }
    }

    private long computeSizeSafely(File dir) {
        try {
            int[] nodes = new int[1];
            return computeSize(dir, 0L, MAX_DIR_SIZE_BYTES, 0, nodes);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private long computeSize(File f, long acc, long limit,
                             int depth, int[] nodes) {
        if (acc >= limit) return acc;
        if (nodes[0] >= MAX_SCAN_NODES) return acc;
        if (depth >= MAX_SCAN_DEPTH) return acc;
        if (f == null || !f.exists()) return acc;

        boolean isFile = false;
        try { isFile = f.isFile(); } catch (Throwable ignored) {}

        if (isFile) {
            nodes[0]++;
            long len;
            try { len = f.length(); }
            catch (Throwable t) { return acc; }
            long next = acc + len;
            return next > limit ? limit : next;
        }

        boolean isDir = false;
        try { isDir = f.isDirectory(); } catch (Throwable ignored) {}
        if (!isDir) return acc;

        nodes[0]++;
        File[] children;
        try { children = f.listFiles(); }
        catch (Throwable t) { return acc; }
        if (children == null) return acc;

        for (File c : children) {
            acc = computeSize(c, acc, limit, depth + 1, nodes);
            if (acc >= limit) return acc;
            if (nodes[0] >= MAX_SCAN_NODES) return acc;
        }
        return acc;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1024 / 1024) + " MB";
        return String.format(Locale.getDefault(),
                "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    private void addHint(String text) {
        if (!canTouchUi() || listContainer == null) return;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor(COLOR_SUB));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(60), 0, dp(60));
        listContainer.addView(tv);
    }

    private void applyCardStyle(View view) {
        if (view == null) return;
        GradientDrawable bg = createRoundRect(COLOR_CARD, dp(20));
        bg.setStroke(dp(1), CARD_STROKE_COLOR);
        view.setBackground(bg);
        view.setElevation(dp(2));
    }

    private void setPressFeedback(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            private float downX = 0;
            private float downY = 0;
            private boolean moved = false;
            private final float THRESHOLD = 24f;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (v == null || event == null) return false;

                ViewGroup parent = (v.getParent() instanceof ViewGroup)
                        ? (ViewGroup) v.getParent() : null;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (parent != null)
                            parent.requestDisallowInterceptTouchEvent(true);
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
                                if (parent != null)
                                    parent.requestDisallowInterceptTouchEvent(false);
                                v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                        .setDuration(100).start();
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (parent != null)
                            parent.requestDisallowInterceptTouchEvent(false);
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(200).start();
                        if (!moved) v.performClick();
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        if (parent != null)
                            parent.requestDisallowInterceptTouchEvent(false);
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(200).start();
                        return true;
                }
                return false;
            }
        });
    }

    private void vibrate() {
        if (!canTouchUi()) return;
        try {
            View root = findViewById(android.R.id.content);
            if (root != null) root.performHapticFeedback(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignored) {}
    }

    private void animateIn(View view, long delay) {
        if (view == null) return;
        view.setTranslationY(dp(40));
        view.setAlpha(0f);
        view.animate().translationY(0f).alpha(1f)
                .setStartDelay(Math.max(0, delay))
                .setDuration(500)
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

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private String shellEscape(String path) {
        if (path == null) return "''";
        return "'" + path.replace("'", "'\\''") + "'";
    }

    static class ModuleEntry {
        String  name;
        String  path;
        String  type;
        boolean isDir;
        boolean enabled;
        boolean isSystem;
        boolean isPlaceholder;
        boolean isPlaceholderUnknown;
        long    installTime;
    }
}