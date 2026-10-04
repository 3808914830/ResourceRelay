package com.demo.controlcenter;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FlashGuard {

    private static final String TAG = "FlashGuard";

    private static final String BACKUP_ROOT_LOCAL  = "/data/local/tmp/flash_backup";
    private static final String BACKUP_ROOT_SDCARD = "/sdcard/DriverBackup";
    private static final String IN_PROGRESS_FLAG   = "/data/local/tmp/.flash_in_progress";
    private static final String STAGE_BASE_DIR     = "/data/local/tmp/flash_logs";

    private static final String[] MODULE_DIRS = {
            "/data/adb/modules",
            "/data/adb/ksu/modules",
            "/data/adb/ap/modules"
    };

    private static final Handler GLOBAL_MAIN =
            new Handler(Looper.getMainLooper());

    public static class Item {
        public String name;
        public boolean passed;
        public String reason;

        public Item(String name, boolean passed, String reason) {
            this.name = name;
            this.passed = passed;
            this.reason = reason;
        }
    }

    public static class Result {
        public List<Item> items = new ArrayList<>();
        public Stage stage;
        public String backupDirLocal;
        public String backupDirSdcard;

        public boolean allPassed() {
            for (Item i : items) {
                if (!i.passed) return false;
            }
            return true;
        }
    }

    public interface Callback {
        void onFinish(Result result);
    }

    public interface StepListener {
        void onStep(String name, boolean passed, String reason);
    }

    private interface StepBody {
        void run() throws Throwable;
    }

    public static Result checkAll(Context ctx) {
        return checkAll(ctx, null);
    }

    public static Result checkAll(Context ctx, StepListener listener) {
        Result r = new Result();
        r.stage = new Stage();

        step(r, listener, "环境检查", () -> checkEnvironment(r));
        step(r, listener, "阶段初始化", () -> initStage(r));
        step(r, listener, "备份", () -> doBackup(r));
        step(r, listener, "备份校验", () -> verifyBackup(r));

        return r;
    }

    private static void step(Result r, StepListener listener,
                             String name, StepBody body) {
        r.stage.beginStage(name);
        Throwable err = null;
        try {
            body.run();
        } catch (Throwable t) {
            err = t;
            Log.e(TAG, name + " 异常", t);
        }

        boolean passed = (err == null);
        String reason = null;

        if (!passed) {
            reason = safeMsg(err);
            r.items.add(new Item(name, false, reason));
        }

        r.stage.endStage(name, passed, reason);
        if (listener != null) listener.onStep(name, passed, reason);
    }

    public static void runPreCheck(final Activity activity,
                                   final Callback callback) {
        new Thread(() -> {
            final Result result = checkAll(activity, null);
            GLOBAL_MAIN.post(() -> {
                if (callback != null) callback.onFinish(result);
            });
        }, "flash-precheck").start();
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "未知";
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static void checkEnvironment(Result r) {
        String brand = Build.BRAND + " / " + Build.MODEL;
        String abi = (Build.SUPPORTED_ABIS != null
                && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "unknown";
        Log.i(TAG, "环境: " + brand + " abi=" + abi);

        long free = new File("/data").getUsableSpace();
        long need = 64L * 1024L * 1024L;
        if (free < need) {
            r.items.add(new Item("磁盘空间", false,
                    "可用 " + (free / 1024 / 1024) + "MB，需要至少 64MB"));
        } else {
            r.items.add(new Item("磁盘空间", true, null));
        }

        if (!new File("/data/adb").exists()) {
            r.items.add(new Item("Root 环境", false, "/data/adb 不存在"));
        } else {
            r.items.add(new Item("Root 环境", true, null));
        }

        if (new File(IN_PROGRESS_FLAG).exists()) {
            Log.w(TAG, "上次刷入未完成，残留标记: " + IN_PROGRESS_FLAG);
            r.items.add(new Item("上次刷入状态", false,
                    "检测到上次刷入未完成标记"));
        } else {
            r.items.add(new Item("上次刷入状态", true, null));
        }

        String selinux = readSelinux();
        r.items.add(new Item("SELinux 状态", true, selinux));
    }

    private static String readSelinux() {
        try (BufferedReader br = new BufferedReader(
                new FileReader("/sys/fs/selinux/enforce"))) {
            String line = br.readLine();
            if ("1".equals(line)) return "强制（enforcing）";
            if ("0".equals(line)) return "宽容（permissive）";
        } catch (Throwable ignored) {}
        return "未知";
    }

    private static void initStage(Result r) {
        clearInProgress();

        File local = r.stage.getSessionDir();
        if (local != null && !local.exists()) local.mkdirs();

        try (FileOutputStream fos = new FileOutputStream(IN_PROGRESS_FLAG)) {
            fos.write(r.stage.getSessionId().getBytes());
        } catch (Throwable t) {
            throw new RuntimeException("写入进行中标记失败: " + safeMsg(t));
        }
    }

    public static void clearInProgress() {
        try {
            File f = new File(IN_PROGRESS_FLAG);
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "clearInProgress: 删除失败 " + IN_PROGRESS_FLAG);
            }
        } catch (Throwable t) {
            Log.w(TAG, "clearInProgress 异常", t);
        }
    }

    private static void doBackup(Result r) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File local  = new File(BACKUP_ROOT_LOCAL, ts);
        File sdcard = new File(BACKUP_ROOT_SDCARD, ts);

        boolean localOk  = local.exists() || local.mkdirs();
        boolean sdcardOk = sdcard.exists() || sdcard.mkdirs();

        r.backupDirLocal  = local.getAbsolutePath();
        r.backupDirSdcard = sdcard.getAbsolutePath();

        int totalOk  = 0;
        int totalAll = 0;
        boolean anyModule = false;

        for (String dir : MODULE_DIRS) {
            File src = new File(dir);
            if (!src.exists()) continue;
            anyModule = true;

            int[] sub = copyDir(src,
                    new File(local, "modules_" + sanitize(dir)),
                    new File(sdcard, "modules_" + sanitize(dir)),
                    0);
            totalOk  += sub[0];
            totalAll += sub[1];
        }

        r.items.add(new Item("本地备份目录", localOk,
                localOk ? null : local.getAbsolutePath() + " 创建失败"));
        r.items.add(new Item("SD 备份目录", sdcardOk,
                sdcardOk ? null : sdcard.getAbsolutePath() + " 创建失败"));

        if (!anyModule) {
            r.items.add(new Item("模块目录备份", true,
                    "未发现模块目录（非错误）"));
        } else if (totalAll == 0) {
            r.items.add(new Item("模块目录备份", true,
                    "模块目录为空（非错误）"));
        } else if (totalOk == totalAll) {
            r.items.add(new Item("模块目录备份", true,
                    "全部文件已备份（" + totalOk + "/" + totalAll + "）"));
        } else {
            r.items.add(new Item("模块目录备份", true,
                    "部分备份（" + totalOk + "/" + totalAll
                            + "，失败 " + (totalAll - totalOk) + "）"));
        }
    }

    private static void verifyBackup(Result r) {
        if (r.backupDirLocal == null) {
            r.items.add(new Item("备份存在", false, "本地备份目录为空"));
            return;
        }
        File local = new File(r.backupDirLocal);
        if (!local.exists()) {
            r.items.add(new Item("备份存在", false, "本地备份目录不存在"));
            return;
        }
        r.items.add(new Item("备份存在", true, null));

        File sdcard = (r.backupDirSdcard == null)
                ? null : new File(r.backupDirSdcard);
        boolean dual = (sdcard != null && sdcard.exists());
        r.items.add(new Item("双通道备份", dual,
                dual ? null : "SD 备份目录不可用"));
    }

    private static int[] copyDir(File src, File dstLocal, File dstSdcard,
                                 int depth) {
        int[] stat = new int[]{0, 0};
        if (src == null || !src.exists()) return stat;
        if (depth > 16) {
            Log.w(TAG, "copyDir: 深度超限，跳过 " + src);
            return stat;
        }

        if (!dstLocal.exists()) dstLocal.mkdirs();
        if (dstSdcard != null && !dstSdcard.exists()) dstSdcard.mkdirs();

        File[] children = src.listFiles();
        if (children == null) return stat;

        for (File c : children) {
            if (c == null) continue;

            boolean isSymlink = false;
            try {
                isSymlink = java.nio.file.Files.isSymbolicLink(c.toPath());
            } catch (Throwable ignored) {}

            if (isSymlink) {
                Log.i(TAG, "copyDir: 跳过符号链接 " + c);
                continue;
            }

            File dl = new File(dstLocal, c.getName());
            File ds = (dstSdcard == null) ? null : new File(dstSdcard, c.getName());

            if (c.isDirectory()) {
                int[] sub = copyDir(c, dl, ds, depth + 1);
                stat[0] += sub[0];
                stat[1] += sub[1];
            } else {
                stat[1]++;
                if (copyFile(c, dl)) stat[0]++;

                if (ds != null) {
                    stat[1]++;
                    if (copyFile(c, ds)) stat[0]++;
                }
            }
        }
        return stat;
    }

    private static boolean copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "copyFile fail: " + src + " -> " + dst, t);
            return false;
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("/", "_").replace(":", "_");
    }

    public static class Stage {

        private final String sessionId;
        private final File sessionDir;
        private final List<StageRecord> records = new ArrayList<>();
        private long stageStart = 0L;

        public static class StageRecord {
            public String name;
            public boolean passed;
            public String reason;
            public long costMs;

            public StageRecord(String name, boolean passed,
                               String reason, long costMs) {
                this.name = name;
                this.passed = passed;
                this.reason = reason;
                this.costMs = costMs;
            }
        }

        public Stage() {
            this.sessionId = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            this.sessionDir = new File(STAGE_BASE_DIR, sessionId);
            if (!sessionDir.exists()) {
                boolean ok = sessionDir.mkdirs();
                Log.i(TAG, "session dir create: " + sessionDir + " ok=" + ok);
            }
            Log.i(TAG, "FlashStage session = " + sessionId);
        }

        public String getSessionId() { return sessionId; }
        public File getSessionDir() { return sessionDir; }

        public void beginStage(String name) {
            stageStart = System.currentTimeMillis();
            Log.i(TAG, ">>> STAGE BEGIN: " + name);
            writeLine("stage.log", ">>> BEGIN " + name);
        }

        public void endStage(String name, boolean passed, String reason) {
            long cost = (stageStart > 0)
                    ? (System.currentTimeMillis() - stageStart) : 0L;
            stageStart = 0L;
            records.add(new StageRecord(name, passed, reason, cost));
            Log.i(TAG, "<<< STAGE END: " + name
                    + " passed=" + passed + " cost=" + cost + "ms"
                    + (reason == null ? "" : " reason=" + reason));
            writeLine("stage.log", "<<< END " + name
                    + " passed=" + passed + " cost=" + cost + "ms"
                    + (reason == null ? "" : " reason=" + reason));
        }

        public void writeLine(String fileName, String line) {
            if (sessionDir == null) return;
            File f = new File(sessionDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(line.getBytes());
                fos.write('\n');
            } catch (Throwable ignored) {}
        }

        public List<StageRecord> getRecords() { return records; }

        public boolean allPassed() {
            for (StageRecord r : records) {
                if (!r.passed) return false;
            }
            return true;
        }

        public String buildReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("Flash Report\n");
            sb.append("Session: ").append(sessionId).append('\n');
            sb.append("Time: ").append(new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date())).append('\n');
            sb.append("----\n");
            for (StageRecord r : records) {
                sb.append(r.passed ? "[OK] " : "[FAIL] ")
                  .append(r.name).append("  (")
                  .append(r.costMs).append("ms)");
                if (r.reason != null && !r.reason.isEmpty()) {
                    sb.append("  reason=").append(r.reason);
                }
                sb.append('\n');
            }
            return sb.toString();
        }

        public void saveReport() {
            String r = buildReport();
            writeLine("report.txt", r);
            Log.i(TAG, "report saved:\n" + r);
        }
    }

    /**
     * 别名类：兼容 FlashActivity 里使用的 FlashStage 名称。
     * 内部完全等价于 Stage，不做任何额外事情。
     */
    public static class FlashStage extends Stage {
        public FlashStage() { super(); }
    }

    public static class IOSDialog extends Dialog {

        private static final String COLOR_BG      = "#FFFFFF";
        private static final String COLOR_TITLE   = "#1C1C1E";
        private static final String COLOR_TEXT    = "#1C1C1E";
        private static final String COLOR_SUB     = "#8E8E93";
        private static final String COLOR_BLUE    = "#007AFF";
        private static final String COLOR_RED     = "#FF3B30";
        private static final String COLOR_DIVIDER = "#E5E5EA";

        private String title;
        private List<ResultItem> items;
        private String footer;
        private String cancelText;
        private String confirmText;
        private boolean confirmDanger;
        private Runnable onCancel;
        private Runnable onConfirm;

        public static class ResultItem {
            public String name;
            public boolean passed;
            public String reason;

            public ResultItem(String name, boolean passed, String reason) {
                this.name = name;
                this.passed = passed;
                this.reason = reason;
            }
        }

        public IOSDialog(Context ctx, String title, List<ResultItem> items,
                         String footer, String cancelText, String confirmText,
                         boolean confirmDanger,
                         Runnable onCancel, Runnable onConfirm) {
            super(ctx);
            this.title = title;
            this.items = items;
            this.footer = footer;
            this.cancelText = cancelText;
            this.confirmText = confirmText;
            this.confirmDanger = confirmDanger;
            this.onCancel = onCancel;
            this.onConfirm = onConfirm;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            setContentView(buildContentView());
            setCancelable(false);
            setCanceledOnTouchOutside(false);

            Window w = getWindow();
            if (w != null) {
                w.setBackgroundDrawableResource(android.R.color.transparent);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                w.setAttributes(lp);
                w.setDimAmount(0.4f);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
        }

        private int dp(float v) {
            return (int) (v * getContext().getResources()
                    .getDisplayMetrics().density + 0.5f);
        }

        private GradientDrawable round(String color, float radius) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setColor(Color.parseColor(color));
            d.setCornerRadius(radius);
            return d;
        }

        private View buildContentView() {
            LinearLayout outer = new LinearLayout(getContext());
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(dp(40), dp(20), dp(40), dp(20));
            outer.setGravity(Gravity.CENTER);

            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(COLOR_BG, dp(14)));
            card.setElevation(dp(12));
            outer.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            if (title != null && !title.isEmpty()) {
                TextView tv = new TextView(getContext());
                tv.setText(title);
                tv.setTextSize(17);
                tv.setTextColor(Color.parseColor(COLOR_TITLE));
                tv.setTypeface(null, Typeface.BOLD);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(20), dp(20), dp(20), dp(12));
                card.addView(tv);
            }

            if (items != null && !items.isEmpty()) {
                LinearLayout list = new LinearLayout(getContext());
                list.setOrientation(LinearLayout.VERTICAL);
                list.setPadding(dp(20), 0, dp(20), dp(16));
                for (ResultItem it : items) list.addView(buildItem(it));
                card.addView(list);
            }

            if (footer != null && !footer.isEmpty()) {
                TextView tv = new TextView(getContext());
                tv.setText(footer);
                tv.setTextSize(13);
                tv.setTextColor(Color.parseColor(COLOR_SUB));
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(20), 0, dp(20), dp(16));
                card.addView(tv);
            }

            View divider = new View(getContext());
            divider.setBackgroundColor(Color.parseColor(COLOR_DIVIDER));
            card.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            LinearLayout btns = new LinearLayout(getContext());
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

            TextView btnCancel = buildButton(cancelText, COLOR_BLUE, false);
            btnCancel.setOnClickListener(v -> {
                if (onCancel != null) {
                    try { onCancel.run(); } catch (Throwable ignored) {}
                }
                try { dismiss(); } catch (Throwable ignored) {}
            });
            btns.addView(btnCancel, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            View vDivider = new View(getContext());
            vDivider.setBackgroundColor(Color.parseColor(COLOR_DIVIDER));
            btns.addView(vDivider, new LinearLayout.LayoutParams(
                    dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

            TextView btnConfirm = buildButton(confirmText,
                    confirmDanger ? COLOR_RED : COLOR_BLUE, confirmDanger);
            btnConfirm.setOnClickListener(v -> {
                if (onConfirm != null) {
                    try { onConfirm.run(); } catch (Throwable ignored) {}
                }
                try { dismiss(); } catch (Throwable ignored) {}
            });
            btns.addView(btnConfirm, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            card.addView(btns);
            return outer;
        }

        private View buildItem(ResultItem it) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView icon = new TextView(getContext());
            icon.setText(it.passed ? "\u2714" : "\u2718");
            icon.setTextSize(14);
            icon.setTextColor(Color.parseColor(
                    it.passed ? "#34C759" : "#FF3B30"));
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(getContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMarginStart(dp(10));
            textCol.setLayoutParams(tp);

            TextView name = new TextView(getContext());
            name.setText(it.name);
            name.setTextSize(15);
            name.setTextColor(Color.parseColor(COLOR_TEXT));
            textCol.addView(name);

            if (!it.passed && it.reason != null && !it.reason.isEmpty()) {
                TextView reason = new TextView(getContext());
                reason.setText("原因：" + it.reason);
                reason.setTextSize(13);
                reason.setTextColor(Color.parseColor(COLOR_SUB));
                reason.setPadding(0, dp(2), 0, 0);
                textCol.addView(reason);
            }

            row.addView(textCol);
            return row;
        }

        private TextView buildButton(String text, String color, boolean bold) {
            TextView tv = new TextView(getContext());
            tv.setText(text == null ? "" : text);
            tv.setTextSize(17);
            tv.setTextColor(Color.parseColor(color));
            tv.setGravity(Gravity.CENTER);
            if (bold) tv.setTypeface(null, Typeface.BOLD);
            tv.setBackgroundColor(Color.TRANSPARENT);
            return tv;
        }
    }

    public static class Reflect {

        private static final String TAG_R = "FlashGuard.Reflect";

        public static String getProperty(String key, String def) {
            try {
                Class<?> c = Class.forName("android.os.SystemProperties");
                Method m = c.getMethod("get", String.class, String.class);
                Object r = m.invoke(null, key, def);
                return r == null ? def : r.toString();
            } catch (Throwable t) {
                Log.w(TAG_R, "getProperty fail: " + key, t);
                return def;
            }
        }

        public static int getIntProperty(String key, int def) {
            try {
                Class<?> c = Class.forName("android.os.SystemProperties");
                Method m = c.getMethod("getInt", String.class, int.class);
                Object r = m.invoke(null, key, def);
                return r == null ? def : (Integer) r;
            } catch (Throwable t) {
                return def;
            }
        }

        public static long getLongProperty(String key, long def) {
            try {
                Class<?> c = Class.forName("android.os.SystemProperties");
                Method m = c.getMethod("getLong", String.class, long.class);
                Object r = m.invoke(null, key, def);
                return r == null ? def : (Long) r;
            } catch (Throwable t) {
                return def;
            }
        }

        public static boolean getBoolProperty(String key, boolean def) {
            try {
                Class<?> c = Class.forName("android.os.SystemProperties");
                Method m = c.getMethod("getBoolean",
                        String.class, boolean.class);
                Object r = m.invoke(null, key, def);
                return r == null ? def : (Boolean) r;
            } catch (Throwable t) {
                return def;
            }
        }

        public static int getPid(Process p) {
            if (p == null) return -1;
            try {
                Method m = p.getClass().getMethod("pid");
                Object r = m.invoke(p);
                return r == null ? -1 : (Integer) r;
            } catch (Throwable ignored) {}

            Class<?> cls = p.getClass();
            while (cls != null && cls != Object.class) {
                try {
                    Field f = cls.getDeclaredField("pid");
                    f.setAccessible(true);
                    Object v = f.get(p);
                    if (v instanceof Integer) return (Integer) v;
                } catch (Throwable ignored) {}
                cls = cls.getSuperclass();
            }
            return -1;
        }

        public static boolean isPackageInstalled(Context ctx, String pkg) {
            if (ctx == null || pkg == null) return false;
            try {
                return ctx.getPackageManager().getPackageInfo(pkg, 0) != null;
            } catch (Throwable ignored) {
                try {
                    Class<?> c = Class.forName(
                            "android.app.ApplicationPackageManager");
                    Method m = c.getMethod("getPackageInfo",
                            String.class, int.class);
                    Object r = m.invoke(ctx.getPackageManager(), pkg, 0);
                    return r != null;
                } catch (Throwable ignored2) {}
                return false;
            }
        }

        public static Object getServiceManager(String name) {
            try {
                Class<?> c = Class.forName("android.os.ServiceManager");
                Method m = c.getMethod("getService", String.class);
                return m.invoke(null, name);
            } catch (Throwable t) {
                Log.w(TAG_R, "getServiceManager fail: " + name, t);
                return null;
            }
        }
    }
}