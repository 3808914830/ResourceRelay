package com.demo.controlcenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
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

public class KernelConfigActivity extends Activity {

    private float density;
    private final Handler MAIN = new Handler(Looper.getMainLooper());
    private final ExecutorService IO_POOL = Executors.newSingleThreadExecutor();

    private EditText searchBox;
    private LinearLayout listContainer;
    private TextView countView;

    private final List<String[]> allParams = new ArrayList<>();
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile boolean destroyed = false;

    private List<String[]> currentFiltered = new ArrayList<>();
    private int renderedCount = 0;

    private static final String COLOR_BG     = "#F2F2F7";
    private static final String COLOR_CARD   = "#FFFFFF";
    private static final String COLOR_TEXT   = "#1C1C1E";
    private static final String COLOR_SUB    = "#8E8E93";
    private static final String COLOR_BLUE   = "#007AFF";
    private static final String COLOR_GREEN  = "#34C759";
    private static final String COLOR_ORANGE = "#FF9500";
    private static final String COLOR_RED    = "#FF3B30";

    private static final String BG_BLUE_LIGHT  = "#E3F0FF";
    private static final int    CARD_STROKE_COLOR = 0x0F000000;

    private static final int  MAX_DEPTH          = 4;
    private static final int  MAX_PARAMS         = 3000;
    private static final int  RENDER_LIMIT       = 200;
    private static final int  RENDER_STEP        = 200;
    private static final long SEARCH_DEBOUNCE_MS = 200L;
    private static final int  MAX_PATH_LENGTH    = 4096;
    private static final int  HEX_DUMP_BYTES     = 2048;

    private static final Set<String> EXCLUDE_PATHS = new HashSet<>();
    static {
        EXCLUDE_PATHS.add("/proc/sys/kernel/random/uuid");
        EXCLUDE_PATHS.add("/proc/sys/kernel/random/boot_id");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        buildUI();
        loadAllParams();
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

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(COLOR_BG));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(buildTopBar());
        root.addView(buildTitle());
        root.addView(buildSearchCard());
        root.addView(buildCountView());
        root.addView(buildList());

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
        btnRefresh.setContentDescription("刷新内核参数");
        btnRefresh.setOnClickListener(v -> { vibrate(); loadAllParams(); });
        setPressFeedback(btnRefresh);
        topBar.addView(btnRefresh);

        return topBar;
    }

    private TextView buildTitle() {
        TextView title = new TextView(this);
        title.setText("内核配置");
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

    private LinearLayout buildSearchCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        applyCardStyle(card);

        TextView icon = new TextView(this);
        icon.setText("🔍");
        icon.setTextSize(16);
        card.addView(icon);

        searchBox = new EditText(this);
        searchBox.setHint("搜索内核参数...");
        searchBox.setHintTextColor(Color.parseColor(COLOR_SUB));
        searchBox.setTextSize(15);
        searchBox.setTextColor(Color.parseColor(COLOR_TEXT));
        searchBox.setBackground(null);
        searchBox.setSingleLine(true);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sbp.setMarginStart(dp(12));
        searchBox.setLayoutParams(sbp);
        card.addView(searchBox);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                MAIN.removeCallbacks(searchRunnable);
                MAIN.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });

        return card;
    }

    private final Runnable searchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!canTouchUi()) return;
            String kw = searchBox == null ? "" : searchBox.getText().toString();
            filterParams(kw);
        }
    };

    private TextView buildCountView() {
        countView = new TextView(this);
        countView.setText("加载中...");
        countView.setTextSize(12);
        countView.setTextColor(Color.parseColor(COLOR_SUB));
        LinearLayout.LayoutParams cvp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cvp.topMargin = dp(12);
        cvp.bottomMargin = dp(8);
        countView.setLayoutParams(cvp);
        return countView;
    }

    private ScrollView buildList() {
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.addView(listContainer);
        return scroll;
    }

    private void loadAllParams() {
        if (destroyed) return;
        if (!scanning.compareAndSet(false, true)) return;

        if (listContainer == null) { scanning.set(false); return; }
        listContainer.removeAllViews();
        if (countView != null) countView.setText("加载中...");
        addHint("正在读取内核参数...");

        IO_POOL.execute(() -> {
            final List<String[]> params = new ArrayList<>();
            try {
                readProcSys(new File("/proc/sys"), "", 0, params);
                addReadonlyInfo(params);
                sortByKey(params);
            } catch (Throwable t) {
            } finally {
                scanning.set(false);
            }

            MAIN.post(() -> {
                if (!canTouchUi()) return;
                allParams.clear();
                allParams.addAll(params);
                if (countView != null) {
                    countView.setText("共 " + allParams.size() + " 项"
                            + (allParams.size() >= MAX_PARAMS ? "（已达上限）" : ""));
                }
                currentFiltered = allParams;
                renderedCount = 0;
                renderParams(allParams);
            });
        });
    }

    private void sortByKey(List<String[]> list) {
        if (list == null) return;
        Collections.sort(list, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                if (a == null && b == null) return 0;
                if (a == null || a.length == 0) return 1;
                if (b == null || b.length == 0) return -1;
                String ak = a[0] == null ? "" : a[0];
                String bk = b[0] == null ? "" : b[0];
                return ak.compareToIgnoreCase(bk);
            }
        });
    }

    private void readProcSys(File dir, String prefix, int depth, List<String[]> out) {
        if (dir == null || out == null) return;
        if (depth > MAX_DEPTH) return;
        if (out.size() >= MAX_PARAMS) return;
        if (destroyed) return;

        File[] files;
        try { files = dir.listFiles(); }
        catch (Throwable t) { return; }
        if (files == null) return;

        for (File f : files) {
            if (out.size() >= MAX_PARAMS) return;
            if (destroyed) return;
            if (f == null) continue;

            String name = f.getName();
            if (TextUtils.isEmpty(name)) continue;
            if (name.startsWith(".")) continue;

            String canonical = safeCanonicalPath(f);
            if (canonical == null) continue;

            try {
                if (f.isDirectory()) {
                    readProcSys(f, prefix + name + ".", depth + 1, out);
                } else {
                    if (EXCLUDE_PATHS.contains(canonical)) continue;

                    String value = readFileSafe(canonical);
                    out.add(new String[]{
                            prefix + name,
                            value == null ? "(空)" : value,
                            canonical,
                            "rw"
                    });
                }
            } catch (Throwable t) {
            }
        }
    }

    private void addReadonlyInfo(List<String[]> out) {
        try {
            String kernelVer = KernelDetector.getKernelVersion();
            out.add(new String[]{
                    "@info.kernel.version",
                    kernelVer == null ? "未知" : kernelVer,
                    "/proc/version",
                    "ro"
            });
        } catch (Throwable ignored) {}

        try {
            String ostype = readFileSafe("/proc/sys/kernel/ostype");
            if (ostype != null) {
                out.add(new String[]{
                        "@info.kernel.ostype",
                        ostype,
                        "/proc/sys/kernel/ostype",
                        "ro"
                });
            }
        } catch (Throwable ignored) {}
    }

    private void filterParams(String keyword) {
        if (!canTouchUi()) return;

        if (keyword == null || keyword.trim().isEmpty()) {
            currentFiltered = allParams;
            renderedCount = 0;
            renderParams(allParams);
            return;
        }
        String k = keyword.toLowerCase(Locale.ROOT).trim();
        List<String[]> filtered = new ArrayList<>();
        for (String[] p : allParams) {
            if (p != null && p.length > 0 && p[0] != null
                    && p[0].toLowerCase(Locale.ROOT).contains(k)) {
                filtered.add(p);
            }
        }
        currentFiltered = filtered;
        renderedCount = 0;
        renderParams(filtered);
    }

    private void renderParams(List<String[]> list) {
        if (!canTouchUi() || listContainer == null) return;
        listContainer.removeAllViews();

        if (list == null || list.isEmpty()) {
            addHint("没有匹配的参数");
            return;
        }

        int target;
        if (renderedCount <= 0) {
            target = Math.min(list.size(), RENDER_LIMIT);
        } else {
            target = Math.min(list.size(), renderedCount);
        }

        for (int i = 0; i < target; i++) {
            if (!canTouchUi()) return;
            String[] p = list.get(i);
            if (p == null || p.length < 4) continue;
            addParamCard(p[0], p[1], p[2], p[3]);
        }
        renderedCount = target;

        if (list.size() > target) {
            TextView more = buildLoadMoreView(list, target);
            listContainer.addView(more);
        }
    }

    private TextView buildLoadMoreView(final List<String[]> list, int initialTarget) {
        final TextView more = new TextView(this);
        int remain = list.size() - initialTarget;
        more.setText("加载更多（还有 " + remain + " 项）");
        more.setTextSize(13);
        more.setTextColor(Color.parseColor(COLOR_BLUE));
        more.setTypeface(null, Typeface.BOLD);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp(20), 0, dp(20));
        more.setBackground(createRoundRect(BG_BLUE_LIGHT, dp(10)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(12);
        more.setLayoutParams(mp);
        more.setContentDescription("加载更多内核参数");
        setPressFeedback(more);

        more.setOnClickListener(v -> {
            if (!canTouchUi()) return;
            vibrate();

            int moreIndex = listContainer.indexOfChild(more);
            if (moreIndex < 0) return;

            int nextTarget = Math.min(list.size(), renderedCount + RENDER_STEP);
            if (nextTarget <= renderedCount) {
                listContainer.removeView(more);
                return;
            }

            int insertAt = moreIndex;
            for (int i = renderedCount; i < nextTarget; i++) {
                if (!canTouchUi()) return;
                String[] p = list.get(i);
                if (p == null || p.length < 4) continue;
                View card = buildParamCardView(p[0], p[1], p[2], p[3]);
                if (card != null) {
                    listContainer.addView(card, insertAt++);
                }
            }
            renderedCount = nextTarget;

            if (list.size() > nextTarget) {
                int remain2 = list.size() - nextTarget;
                more.setText("加载更多（还有 " + remain2 + " 项）");
            } else {
                listContainer.removeView(more);
            }
        });

        return more;
    }

    private void addParamCard(final String key, String value,
                              final String path, String perm) {
        View card = buildParamCardView(key, value, path, perm);
        if (card != null && listContainer != null) {
            listContainer.addView(card);
        }
    }

    private View buildParamCardView(final String key, String value,
                                    final String path, String perm) {
        if (!canTouchUi()) return null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(8);
        card.setLayoutParams(cp);
        applyCardStyle(card);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvKey = new TextView(this);
        tvKey.setText(key == null ? "(空名)" : key);
        tvKey.setTextSize(14);
        tvKey.setTextColor(Color.parseColor(COLOR_TEXT));
        tvKey.setTypeface(null, Typeface.BOLD);
        tvKey.setMaxLines(2);
        tvKey.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row1.addView(tvKey);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row1.addView(spacer);

        TextView tvPerm = new TextView(this);
        tvPerm.setText(perm == null ? "?" : perm);
        tvPerm.setTextSize(11);
        tvPerm.setTextColor(Color.parseColor(
                "ro".equals(perm) ? COLOR_SUB : COLOR_GREEN));
        row1.addView(tvPerm);

        card.addView(row1);

        TextView tvValue = new TextView(this);
        tvValue.setText(value == null ? "(空)" : value);
        tvValue.setTextSize(13);
        tvValue.setTextColor(Color.parseColor(COLOR_SUB));
        tvValue.setMaxLines(2);
        tvValue.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = dp(6);
        tvValue.setLayoutParams(vp);
        card.addView(tvValue);

        card.setContentDescription("内核参数: " + key);
        card.setOnClickListener(v -> {
            if (!canTouchUi()) return;
            vibrate();
            if (!RootManager.get().requireRoot(this)) return;
            showActionMenu(key, value, path, perm);
        });
        setPressFeedback(card);

        return card;
    }

    private void showActionMenu(final String key, final String value,
                                final String path, final String perm) {
        if (!canTouchUi()) return;

        String[] actions;
        if ("ro".equals(perm)) {
            actions = new String[]{"查看十六进制", "查看路径"};
        } else {
            actions = new String[]{"修改值", "查看十六进制", "写入十六进制", "查看路径"};
        }

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(key)
                .setItems(actions, (dialog, which) -> {
                    if (!canTouchUi()) return;
                    String action = actions[which];
                    if ("修改值".equals(action)) {
                        showEditDialog(key, value, path);
                    } else if ("查看十六进制".equals(action)) {
                        showHexView(key, path);
                    } else if ("写入十六进制".equals(action)) {
                        showHexEditor(key, path);
                    } else if ("查看路径".equals(action)) {
                        AlertDialog dd = new AlertDialog.Builder(this)
                                .setTitle("参数路径")
                                .setMessage(path)
                                .setPositiveButton("确定", null)
                                .create();
                        safeShow(dd);
                    }
                })
                .create();
        safeShow(d);
    }

    private void safeShow(AlertDialog d) {
        if (d == null) return;
        if (!canTouchUi()) { try { d.dismiss(); } catch (Throwable ignored) {} return; }
        try { d.show(); } catch (Throwable ignored) {}
    }

    private void showEditDialog(final String key, String oldValue, final String path) {
        if (!canTouchUi()) return;

        final EditText input = new EditText(this);
        input.setText(oldValue == null ? "" : oldValue);
        input.setTextSize(15);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("修改 " + key)
                .setView(input)
                .setPositiveButton("写入", (dlg, w) ->
                        writeParam(path, input.getText().toString()))
                .setNegativeButton("取消", null)
                .create();
        safeShow(d);
    }

    private void writeParam(final String path, final String rawValue) {
        if (path == null || path.isEmpty()) return;

        final String value = rawValue == null ? "" : rawValue;
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            Toast.makeText(this, "值不能含换行", Toast.LENGTH_SHORT).show();
            return;
        }
        if (value.indexOf('\0') >= 0) {
            Toast.makeText(this, "值不能含 NUL", Toast.LENGTH_SHORT).show();
            return;
        }

        final String safePath  = shellEscape(path);
        final String safeValue = shellEscape(value);
        final String cmd = "printf '%s' " + safeValue
                + " | tee " + safePath + " >/dev/null";

        RootHelper.runAsync(cmd, 10, r -> MAIN.post(() -> {
            if (!canTouchUi()) return;
            Toast.makeText(this,
                    r.ok() ? "已写入" : "写入失败: " + r.all(),
                    Toast.LENGTH_SHORT).show();
            loadAllParams();
        }));
    }

    private void showHexView(String key, final String path) {
        if (!canTouchUi()) return;

        IO_POOL.execute(() -> {
            final String content = buildHexDump(path);
            MAIN.post(() -> {
                if (!canTouchUi()) return;
                if (content == null) {
                    Toast.makeText(this, "读取失败或文件为空",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                AlertDialog d = new AlertDialog.Builder(this)
                        .setTitle("十六进制 - " + key)
                        .setMessage(content + "\n\n路径: " + path)
                        .setPositiveButton("确定", null)
                        .create();
                safeShow(d);
            });
        });
    }

    private String buildHexDump(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] buf = new byte[HEX_DUMP_BYTES];
            int len = fis.read(buf);
            if (len <= 0) return null;

            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < len; i++) {
                hex.append(String.format(Locale.ROOT, "%02X ", buf[i]));
                char c = (char) (buf[i] & 0xFF);
                ascii.append(c >= 32 && c < 127 ? c : '.');
                if ((i + 1) % 16 == 0) {
                    hex.append('\n');
                    ascii.append('\n');
                }
            }
            String tail = (len >= HEX_DUMP_BYTES)
                    ? "\n\n(仅显示前 " + HEX_DUMP_BYTES + " 字节)"
                    : "";
            return "HEX:\n" + hex + "\n\nASCII:\n" + ascii + tail;
        } catch (Throwable t) {
            return null;
        }
    }

    private void showHexEditor(final String key, final String path) {
        if (!canTouchUi()) return;

        final EditText input = new EditText(this);
        input.setHint("输入 HEX 字节（可打印 ASCII 0x20-0x7E），例如: 30 31 41");
        input.setTextSize(15);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("写入 HEX - " + key)
                .setView(input)
                .setPositiveButton("写入", (dlg, w) ->
                        writeHexParam(path, input.getText().toString()))
                .setNegativeButton("取消", null)
                .create();
        safeShow(d);
    }

    private void writeHexParam(final String path, final String hexStr) {
        if (path == null || path.isEmpty() || hexStr == null) return;

        StringBuilder octal = new StringBuilder();
        int accepted = 0;
        int rejected = 0;

        for (String raw : hexStr.split("\\s+")) {
            if (raw == null || raw.isEmpty()) continue;

            String p = raw.length() == 1 ? "0" + raw : raw;
            if (p.length() != 2) { rejected++; continue; }

            int v;
            try { v = Integer.parseInt(p, 16); }
            catch (Throwable t) { rejected++; continue; }

            if (v < 0x20 || v > 0x7E) { rejected++; continue; }

            octal.append(String.format(Locale.ROOT, "\\0%03o", v));
            accepted++;
        }

        if (accepted == 0) {
            Toast.makeText(this,
                    "没有合法字节（仅支持可打印 ASCII 0x20-0x7E）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (rejected > 0) {
            Toast.makeText(this,
                    "已忽略 " + rejected + " 个非法字节（非可打印 ASCII）",
                    Toast.LENGTH_SHORT).show();
        }

        final String safePath = shellEscape(path);
        final String cmd = "printf '%b' '" + octal + "'"
                + " | tee " + safePath + " >/dev/null";

        RootHelper.runAsync(cmd, 10, r -> MAIN.post(() -> {
            if (!canTouchUi()) return;
            Toast.makeText(this,
                    r.ok() ? "已写入" : "写入失败: " + r.all(),
                    Toast.LENGTH_SHORT).show();
            loadAllParams();
        }));
    }

    private String readFileSafe(String path) {
        if (path == null) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line;
        } catch (Throwable t) {
            return null;
        }
    }

    private String safeCanonicalPath(File f) {
        if (f == null) return null;
        try {
            String c = f.getCanonicalPath();
            if (c == null || c.length() > MAX_PATH_LENGTH) return null;
            if (c.indexOf('\0') >= 0) return null;
            return c;
        } catch (Throwable t) {
            return null;
        }
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
        GradientDrawable bg = createRoundRect(COLOR_CARD, dp(16));
        bg.setStroke(dp(1), CARD_STROKE_COLOR);
        view.setBackground(bg);
        view.setElevation(dp(1));
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
}