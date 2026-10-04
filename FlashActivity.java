package com.demo.controlcenter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.demo.controlcenter.FlashGuard.IOSDialog;
import com.demo.controlcenter.FlashGuard.FlashStage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FlashActivity extends Activity {

    private static final String TAG = "Flash";

    private static final int REQ_PICK_FILE = 3001;

    private static final int  SILENT_ABORT_MS     = 30_000;
    private static final int  DELETE_TIMEOUT_SEC  = 15;
    private static final int  SELINUX_TIMEOUT_SEC = 30;
    private static final int  VERIFY_TIMEOUT_SEC  = 10;
    private static final int  COPY_BUF_SIZE       = 8192;
    private static final int  MAX_LOG_LINES       = 500;
    private static final int  MAX_PENDING_LOGS    = 100;
    private static final int  WATCHDOG_TICK_MS    = 1000;
    private static final long MAX_FILE_SIZE       = 200L * 1024 * 1024;
    private static final long WAIT_EXIT_MS        = 5000L;

    private static final long BACK_KEY_LOCK_MS = 5L * 60L * 1000L;

    private static final int C_BG     = 0xFFF2F2F7;
    private static final int C_CARD   = 0xFFFFFFFF;
    private static final int C_TEXT   = 0xFF1C1C1E;
    private static final int C_SUB    = 0xFF8E8E93;
    private static final int C_BLUE   = 0xFF007AFF;
    private static final int C_GREEN  = 0xFF34C759;
    private static final int C_RED    = 0xFFFF3B30;
    private static final int C_ORANGE = 0xFFFF9500;
    private static final int C_GRAY   = 0xFFE5E5EA;
    private static final int C_WHITE  = 0xFFFFFFFF;

    private static final int CARD_STROKE_COLOR = 0x0F000000;

    private static final String SP_NAME          = "flash_prefs";
    private static final String SP_KEY_LAST_PATH = "last_selected_path";

    private static final Object LOG_TOKEN = new Object();

    private final Handler MAIN = new Handler(Looper.getMainLooper());

    private final AtomicLong runToken = new AtomicLong(0);
    private final AtomicInteger pendingLogCount = new AtomicInteger(0);
    private final AtomicInteger droppedLogCount = new AtomicInteger(0);

    private final Object flashLock = new Object();

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "flash-watchdog");
                t.setDaemon(true);
                return t;
            });

    private float density;

    private TextView     tvFileName;
    private TextView     tvStatus;
    private LinearLayout logContainer;
    private ScrollView   logScroll;

    private TextView btnCancel;
    private TextView btnStart;

    private String  selectedPath = null;
    private String  selectedName = null;

    private volatile boolean flashing        = false;
    private volatile boolean aborted         = false;
    private volatile boolean hasDriverOp     = false;
    private volatile boolean sawDoneMarker   = false;
    private volatile Process currentProcess  = null;
    private volatile boolean copyCancelled   = false;

    private volatile long lastOutputTime = 0L;
    private volatile long flashStartTime = 0L;
    private volatile long flashBeginWallClock = 0L;

    private boolean scrollPending = false;

    private ScheduledFuture<?> watchdogFuture = null;

 private FlashGuard.Stage flashStage = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
        sp.edit().putString(SP_KEY_LAST_PATH, null).apply();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUI();
        Log.i(TAG, "onCreate: 进入 FlashActivity");
        pickFile();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy: 离开 FlashActivity");
        copyCancelled = true;
        flashing = false;
        stopWatchdog();
        killProcessNow();
        MAIN.removeCallbacksAndMessages(LOG_TOKEN);
    }

    @Override
    public void onBackPressed() {
        if (!flashing) {
            super.onBackPressed();
            return;
        }

        long elapsed = System.currentTimeMillis() - flashBeginWallClock;
        boolean timeoutReached = elapsed >= BACK_KEY_LOCK_MS;

        if (!timeoutReached) {
            long remainSec = (BACK_KEY_LOCK_MS - elapsed) / 1000L;
            Log.w(TAG, "onBackPressed: 刷入进行中，剩余锁定 " + remainSec + "s");
            Toast.makeText(this,
                    "刷入进行中，请等待 " + remainSec + " 秒后再退出",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Log.w(TAG, "onBackPressed: 已超过锁定期，二次确认退出");
        final boolean[] handled = {false};
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("刷入进行中")
                .setMessage("刷入可能仍在后台执行，现在退出可能导致刷入中断。\n\n确定退出吗？")
                .setPositiveButton("退出", (dlg, w) -> {
                    handled[0] = true;
                    finishAffinity();
                })
                .setNegativeButton("继续等待", (dlg, w) -> handled[0] = true)
                .setOnCancelListener(dlg -> handled[0] = true)
                .setCancelable(false)
                .create();
        d.setOnDismissListener(dlg -> {
            if (!handled[0]) handled[0] = true;
        });
        d.show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && flashing) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean canTouchUi() {
        return !isFinishing() && !isDestroyed();
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(buildTitle());
        root.addView(buildFileCard());
        root.addView(buildLogCard());
        root.addView(buildButtonRow());

        setContentView(root);
    }

    private TextView buildTitle() {
        TextView title = new TextView(this);
        title.setText("刷入驱动");
        title.setTextSize(22);
        title.setTextColor(C_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(16);
        title.setLayoutParams(tp);
        return title;
    }

    private LinearLayout buildFileCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        applyCardStyle(card);

        TextView label = new TextView(this);
        label.setText("已选文件");
        label.setTextSize(12);
        label.setTextColor(C_SUB);
        card.addView(label);

        tvFileName = new TextView(this);
        tvFileName.setText("等待选择...");
        tvFileName.setTextSize(16);
        tvFileName.setTextColor(C_TEXT);
        tvFileName.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams fnp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        fnp.topMargin = dp(6);
        tvFileName.setLayoutParams(fnp);
        card.addView(tvFileName);

        tvStatus = new TextView(this);
        tvStatus.setText("等待选择文件");
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(C_SUB);
        LinearLayout.LayoutParams tsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tsp.topMargin = dp(12);
        tvStatus.setLayoutParams(tsp);
        card.addView(tvStatus);

        return card;
    }

    private LinearLayout buildLogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lcp.topMargin = dp(12);
        card.setLayoutParams(lcp);
        applyCardStyle(card);

        TextView title = new TextView(this);
        title.setText("执行日志");
        title.setTextSize(12);
        title.setTextColor(C_SUB);
        card.addView(title);

        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams lsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lsp.topMargin = dp(8);
        logScroll.setLayoutParams(lsp);

        logContainer = new LinearLayout(this);
        logContainer.setOrientation(LinearLayout.VERTICAL);
        logScroll.addView(logContainer);
        card.addView(logScroll);

        return card;
    }

    private LinearLayout buildButtonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(16);
        row.setLayoutParams(bp);

        btnCancel = buildButton("取消", C_GRAY, C_TEXT);
        btnCancel.setOnClickListener(v -> {
            vibrate();
            if (flashing) {
                aborted = true;
                appendLog("⚠️ 用户取消刷入，正在中止...", C_ORANGE);
                Log.w(TAG, "用户取消刷入");
                killProcessNow();
                endFlash();
            } else {
                finish();
            }
        });
        row.addView(btnCancel);

        btnStart = buildButton("开始刷入", C_BLUE, C_WHITE);
        btnStart.setOnClickListener(v -> onStartClick());
        row.addView(btnStart);

        return row;
    }

    private void onStartClick() {
        vibrate();

        if (selectedPath == null) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }

        synchronized (flashLock) {
            if (flashing) return;
            flashing = true;
        }

        flashBeginWallClock = System.currentTimeMillis();

        btnStart.setEnabled(false);
        btnStart.setAlpha(0.5f);

        if (!RootManager.get().requireRoot(this)) {
            endFlash();
            return;
        }

        Log.i(TAG, "onStartClick: 启动前置检查链");
        appendLog("[前置检查] 开始环境 / 阶段 / 备份 / 备份校验...", C_ORANGE);

        new Thread(() -> {
            FlashGuard.Result result;
            try {
                result = FlashGuard.checkAll(FlashActivity.this);
            } catch (Throwable t) {
                Log.e(TAG, "前置检查异常", t);
                result = null;
            }

            final FlashGuard.Result fResult = result;
            MAIN.post(() -> {
                if (!canTouchUi()) return;
                if (fResult == null) {
                    appendLog("❌ 前置检查崩溃", C_RED);
                    endFlash();
                    return;
                }
                flashStage = fResult.stage;
                showPreCheckResult(fResult);
            });
        }, "flash-precheck").start();
    }

    private void showPreCheckResult(FlashGuard.Result r) {
        for (FlashGuard.Item it : r.items) {
            appendLog((it.passed ? "✅ " : "❌ ") + it.name
                    + (it.reason == null ? "" : "：" + it.reason),
                    it.passed ? C_GREEN : C_RED);
        }

        if (r.allPassed()) {
            Log.i(TAG, "前置检查全部通过，开始刷入");
            appendLog("✅ 前置检查全部通过，开始刷入", C_GREEN);
            startFlashNow();
            return;
        }

        Log.w(TAG, "前置检查存在未通过项，弹窗询问");
        List<IOSDialog.ResultItem> items = new ArrayList<>();
        for (FlashGuard.Item it : r.items) {
            items.add(new IOSDialog.ResultItem(it.name, it.passed, it.reason));
        }

        IOSDialog dlg = new IOSDialog(
                this,
                "刷入前检查结果",
                items,
                "以上未完成项可能影响刷入安全，是否继续刷入？",
                "取消",
                "继续刷入",
                true,
                () -> {
                    Log.i(TAG, "用户取消刷入（前置检查未过）");
                    FlashGuard.clearInProgress();
                    endFlash();
                },
                () -> {
                    Log.w(TAG, "用户选择继续刷入（前置检查未过）");
                    appendLog("⚠️ 用户选择继续刷入（存在未完成项）", C_ORANGE);
                    FlashGuard.clearInProgress();
                    startFlashNow();
                });
        dlg.show();
    }

    private void startFlashNow() {
        RootEnv.Info info = RootManager.get().getInfo();
        if (info.mode != RootEnv.MODE_GRANTED) {
            appendLog("❌ 未获得 Root 授权，无法执行刷入", C_RED);
            Log.e(TAG, "startFlashNow: Root 未授权");
            endFlash();
            return;
        }

        appendLog("✅ " + info.typeName + " 已授权", C_GREEN);

        final long myToken = runToken.incrementAndGet();

        aborted       = false;
        hasDriverOp   = false;
        sawDoneMarker = false;

        btnCancel.setText("中止");
        btnStart.setEnabled(false);
        btnStart.setAlpha(0.5f);

        String cmd = buildFlashCommand(info.typeName, selectedPath);
        Log.i(TAG, "刷入命令: " + cmd);
        appendLog("\n[执行] $ " + cmd, C_TEXT);

        runFlashAsync(cmd, myToken);
    }

    private static String shellEscape(String s) {
        if (s == null || s.isEmpty()) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String buildFlashCommand(String rootType, String path) {
        if (path == null || path.isEmpty()) {
            return "echo 'ERROR: 路径为空'; exit 1";
        }
        String esc = shellEscape(path);
        String lower = path.toLowerCase();

        if (lower.endsWith(".sh")) {
            return "chmod 755 " + esc + " && sh " + esc + "; "
                    + "ec=$?; [ $ec -eq 0 ] && echo FLASH_DONE_OK; exit $ec";
        }

        if (lower.endsWith(".ko")) {
            return "chmod 755 " + esc + " && insmod " + esc + "; "
                    + "ec=$?; [ $ec -eq 0 ] && echo FLASH_DONE_OK; exit $ec";
        }

        if (lower.endsWith(".zip")) {
            if ("Magisk".equals(rootType)) {
                return "for m in /data/adb/magisk/magisk /debug_ramdisk/magisk "
                        + "/system/bin/magisk /sbin/magisk; do "
                        + "[ -x \"$m\" ] && MAGISK=\"$m\" && break; done; "
                        + "[ -z \"$MAGISK\" ] && echo 'ERROR: magisk not found' && exit 1; "
                        + "\"$MAGISK\" --install-module " + esc + "; "
                        + "ec=$?; [ $ec -eq 0 ] && echo FLASH_DONE_OK; exit $ec";
            }
            if ("KernelSU".equals(rootType)) {
                return "KSUD=$(command -v ksud 2>/dev/null); "
                        + "[ -z \"$KSUD\" ] && KSUD=/data/adb/ksu/bin/ksud; "
                        + "[ ! -x \"$KSUD\" ] && echo 'ERROR: ksud not found' && exit 1; "
                        + "\"$KSUD\" module install " + esc + " 2>&1 || "
                        + "\"$KSUD\" module install --zip " + esc + " 2>&1; "
                        + "ec=$?; [ $ec -eq 0 ] && echo FLASH_DONE_OK; exit $ec";
            }
            if ("APatch".equals(rootType)) {
                return "for m in /data/adb/magisk/magisk /data/adb/ap/bin/magisk "
                        + "/system/bin/magisk; do "
                        + "[ -x \"$m\" ] && MAGISK=\"$m\" && break; done; "
                        + "[ -z \"$MAGISK\" ] && echo 'ERROR: apatch magisk-compat not found' && exit 1; "
                        + "\"$MAGISK\" --install-module " + esc + "; "
                        + "ec=$?; [ $ec -eq 0 ] && echo FLASH_DONE_OK; exit $ec";
            }
            return "echo 'ERROR: 未识别的 Root 类型'; exit 1";
        }

        return "chmod 755 " + esc + " && sh " + esc + "; "
                + "ec=$?; [ $ec -eq 0 ] && echo FLASH_DONE_OK; exit $ec";
    }

    private void runFlashAsync(final String cmd, final long myToken) {
        stopWatchdog();

        new Thread(() -> {
            hasDriverOp    = false;
            sawDoneMarker  = false;
            flashStartTime = System.currentTimeMillis();
            lastOutputTime = flashStartTime;

            Log.i(TAG, "runFlashAsync: 开始执行命令");
            if (flashStage != null) {
                flashStage.beginStage("刷入执行");
                flashStage.writeLine("stage.log", "$ " + cmd);
            }

            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
                pb.redirectErrorStream(true);
                p = pb.start();

                if (runToken.get() != myToken) {
                    RootHelper.killCompat(p);
                    return;
                }
                currentProcess = p;

                startWatchdog(myToken);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (runToken.get() != myToken) return;

                        lastOutputTime = System.currentTimeMillis();

                        if (isDriverOpLine(line)) {
                            hasDriverOp = true;
                        }
                        if (line.contains("FLASH_DONE_OK")) {
                            sawDoneMarker = true;
                        }

                        if (aborted) return;

                        final String l = line;
                        if (flashStage != null) {
                            flashStage.writeLine("stdout.log", l);
                        }
                        appendLog(l, C_TEXT);
                    }
                }

                int exit = waitForExit(p, WAIT_EXIT_MS);
                stopWatchdog();

                if (runToken.get() != myToken) return;

                if (exit == -1) {
                    RootHelper.killCompat(p);
                    Log.e(TAG, "runFlashAsync: 进程超时未退出，强杀");
                    if (flashStage != null) {
                        flashStage.endStage("刷入执行", false, "进程超时未退出");
                    }
                    MAIN.post(() -> {
                        if (runToken.get() != myToken) return;
                        if (!canTouchUi() || aborted) return;
                        appendLog("\n⚠️ 进程超时未退出，已强制终止", C_RED);
                        endFlash();
                    });
                    return;
                }

                boolean realSuccess = (exit == 0) && (hasDriverOp || sawDoneMarker);
                Log.i(TAG, "runFlashAsync: exit=" + exit
                        + " driverOp=" + hasDriverOp
                        + " doneMarker=" + sawDoneMarker
                        + " realSuccess=" + realSuccess);

                if (flashStage != null) {
                    flashStage.endStage("刷入执行", realSuccess,
                            "exit=" + exit + " driverOp=" + hasDriverOp
                                    + " doneMarker=" + sawDoneMarker);
                }

                final int fexit = exit;
                final boolean fReal = realSuccess;

                MAIN.post(() -> {
                    if (runToken.get() != myToken) return;
                    if (!canTouchUi() || aborted) return;
                    if (fReal) {
                        appendLog("\n✅ 脚本执行完成 (exit=" + fexit + ")", C_GREEN);
                        verifyAfterFlash(myToken);
                    } else {
                        appendLog("\n⚠️ 脚本中途退出或未完成 (exit=" + fexit + ")", C_RED);
                        endFlash();
                    }
                });
            } catch (Exception e) {
                stopWatchdog();
                if (runToken.get() != myToken) return;
                final String msg = e.getMessage();
                Log.e(TAG, "runFlashAsync: 异常", e);
                if (flashStage != null) {
                    flashStage.endStage("刷入执行", false, "异常: " + msg);
                }
                MAIN.post(() -> {
                    if (runToken.get() != myToken) return;
                    if (!canTouchUi()) return;
                    if (aborted) {
                        appendLog("ℹ️ 刷入已中止", C_ORANGE);
                    } else {
                        appendLog("错误: " + msg, C_RED);
                    }
                    endFlash();
                });
            } finally {
                if (runToken.get() == myToken) {
                    currentProcess = null;
                }
            }
        }).start();
    }

    private void verifyAfterFlash(final long myToken) {
        appendLog("正在校验模块加载状态...", C_SUB);
        Log.i(TAG, "verifyAfterFlash: 开始校验");

        String check =
                "echo '---MODULES---' ; "
                + "if [ -d /data/adb/modules ]; then "
                + "  ls -1 /data/adb/modules 2>/dev/null | wc -l ; "
                + "else echo MISSING ; fi ; "
                + "echo '---KOMODS---' ; "
                + "CNT=$(for d in /vendor/lib/modules /system/lib/modules /lib/modules; do "
                + "  [ -d \"$d\" ] || continue ; "
                + "  find \"$d\" -maxdepth 1 -name '*.ko' -type f 2>/dev/null ; "
                + "done | wc -l) ; echo $CNT";

        RootHelper.runAsync(check, VERIFY_TIMEOUT_SEC, r -> {
            if (runToken.get() != myToken) return;
            if (!canTouchUi()) return;

            if (!r.ok() && (r.stdout == null || r.stdout.isEmpty())) {
                appendLog("ℹ️ 校验未执行，无法确认模块状态", C_SUB);
                Log.w(TAG, "verifyAfterFlash: 校验未执行");
                endFlash();
                fixSelinuxContextAsync();
                return;
            }

            String out = r.stdout == null ? "" : r.stdout;
            String modSeg = extractSegment(out, "---MODULES---", "---KOMODS---");
            String koSeg  = extractSegment(out, "---KOMODS---", null);

            if (modSeg == null && koSeg == null) {
                appendLog("ℹ️ 校验输出无法解析，跳过确认", C_SUB);
                endFlash();
                fixSelinuxContextAsync();
                return;
            }

            boolean modMissing = (modSeg != null)
                    && modSeg.trim().contains("MISSING");
            int modulesCount = (modSeg == null || modMissing)
                    ? -1 : parseIntSafe(modSeg.trim());
            int koCount = (koSeg == null) ? -1 : parseIntSafe(koSeg.trim());

            Log.i(TAG, "verifyAfterFlash: modules=" + modulesCount
                    + " ko=" + koCount + " missing=" + modMissing);

            if (flashStage != null) {
                flashStage.beginStage("刷后校验");
                boolean ok = (koCount > 0) || (modulesCount > 0);
                flashStage.endStage("刷后校验", ok,
                        "modules=" + modulesCount + " ko=" + koCount);
            }

            if (koCount == -1) {
                if (modulesCount > 0) {
                    appendLog("ℹ️ 模块目录已有 " + modulesCount + " 项，.ko 校验未完成", C_SUB);
                } else {
                    appendLog("⚠️ .ko 文件校验未完成，无法确认模块状态", C_ORANGE);
                }
            } else if (modMissing) {
                if (koCount > 0) {
                    appendLog("⚠️ 模块目录不存在，但检测到 .ko 文件", C_ORANGE);
                } else if (koCount == 0) {
                    appendLog("⚠️ 模块目录与 .ko 均未检测到，可能未真正生效", C_ORANGE);
                } else {
                    appendLog("ℹ️ 模块目录不存在，.ko 校验未完成", C_SUB);
                }
            } else if (modulesCount == -1) {
                if (koCount > 0) {
                    appendLog("✅ 检测到 .ko 文件已就位", C_GREEN);
                } else if (koCount == 0) {
                    appendLog("⚠️ 未检测到 .ko 文件，可能未真正生效", C_ORANGE);
                }
            } else if ((modulesCount > 0) || (koCount > 0)) {
                appendLog("✅ 刷入成功，模块文件已就位", C_GREEN);
            } else if (modulesCount == 0 && koCount == 0) {
                appendLog("⚠️ 脚本报成功，但未检测到模块文件", C_ORANGE);
            } else {
                appendLog("⚠️ 校验结果不完整，无法确认模块状态", C_ORANGE);
            }
            endFlash();
            fixSelinuxContextAsync();
        });
    }

    private static String extractSegment(String out, String startMark, String endMark) {
        if (out == null || startMark == null) return null;
        int idx = out.indexOf(startMark);
        if (idx < 0) return null;
        int from = idx + startMark.length();
        int to = (endMark == null) ? out.length() : out.indexOf(endMark, from);
        if (to < 0) {
            if (endMark != null) return null;
            to = out.length();
        }
        return out.substring(from, to).trim();
    }

    private static int parseIntSafe(String seg) {
        if (seg == null) return -1;
        for (String tok : seg.split("\\s+")) {
            try {
                return Integer.parseInt(tok);
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static int waitForExit(Process p, long timeoutMs) {
        if (p == null) return -1;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return p.exitValue();
            } catch (IllegalThreadStateException ignored) {
            } catch (Throwable t) {
                return -1;
            }
            try { Thread.sleep(50L); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    private static boolean isDriverOpLine(String line) {
        if (line == null) return false;
        String t = line.trim();

        if (t.startsWith("sudo "))    t = t.substring(5).trim();
        if (t.startsWith("busybox ")) t = t.substring(8).trim();
        if (t.startsWith("toybox "))  t = t.substring(7).trim();

        if (t.startsWith("echo ")   || t.startsWith("echo\t"))   return false;
        if (t.startsWith("printf ") || t.startsWith("printf\t")) return false;
        if (t.startsWith("#")) return false;
        if (t.startsWith("+ ") || t.startsWith("+")) return false;

        if (t.startsWith("insmod ")   || t.startsWith("insmod\t"))   return true;
        if (t.startsWith("rmmod ")    || t.startsWith("rmmod\t"))    return true;
        if (t.startsWith("modprobe ") || t.startsWith("modprobe\t")) return true;

        if (t.contains("magisk") && t.contains("--install-module")) return true;
        if (t.contains("ksud")   && t.contains("module install"))   return true;

        return false;
    }

    private void startWatchdog(final long token) {
        watchdogFuture = WATCHDOG.scheduleWithFixedDelay(() -> {
            try {
                if (runToken.get() != token) return;
                if (aborted || hasDriverOp) return;

                long now = System.currentTimeMillis();
                if (now - lastOutputTime > SILENT_ABORT_MS
                        && now - flashStartTime > SILENT_ABORT_MS) {

                    if (aborted) return;
                    aborted = true;
                    Log.e(TAG, "watchdog: 30 秒无输出，强杀");
                    appendLog("\n⚠️ 30 秒无输出，watchdog 强杀进程", C_RED);
                    killProcessNow();

                    MAIN.post(() -> {
                        if (runToken.get() != token) return;
                        if (canTouchUi()) endFlash();
                    });
                }
            } catch (Throwable ignored) {}
        }, WATCHDOG_TICK_MS, WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void stopWatchdog() {
        ScheduledFuture<?> f = watchdogFuture;
        watchdogFuture = null;
        if (f != null) {
            try { f.cancel(false); } catch (Throwable ignored) {}
        }
    }

    private void fixSelinuxContextAsync() {
        final long myToken = runToken.get();

        String fix =
                "TMP=\"\" ; "
                + "if command -v mktemp >/dev/null 2>&1; then "
                + "  TMP=$(mktemp /data/local/tmp/.selinux_list.XXXXXX 2>/dev/null) ; "
                + "fi ; "
                + "if [ -z \"$TMP\" ]; then "
                + "  TMP=/data/local/tmp/.selinux_list.$$ ; "
                + "fi ; "
                + "if [ -L \"$TMP\" ]; then "
                + "  echo \"SELINUX_FAIL tmp\" ; exit 0 ; "
                + "fi ; "
                + ": > \"$TMP\" || { echo \"SELINUX_FAIL tmp\" ; exit 0 ; } ; "
                + "FIND_FAIL=0 ; "
                + "for d in /vendor/lib/modules /system/lib/modules /lib/modules; do "
                + "  [ -d \"$d\" ] || continue ; "
                + "  find \"$d\" -maxdepth 1 -name '*.ko' -type f 2>/dev/null >> \"$TMP\" "
                + "    || FIND_FAIL=1 ; "
                + "done ; "
                + "if [ $FIND_FAIL -eq 1 ]; then "
                + "  if [ ! -s \"$TMP\" ]; then "
                + "    rm -f \"$TMP\" ; echo \"SELINUX_FAIL find\" ; exit 0 ; "
                + "  else "
                + "    echo \"SELINUX_PARTIAL find\" ; "
                + "  fi ; "
                + "fi ; "
                + "FAIL=0; CNT=0 ; "
                + "while IFS= read -r f; do "
                + "  [ -n \"$f\" ] || continue ; "
                + "  CNT=$((CNT + 1)) ; "
                + "  chcon u:object_r:vendor_file:s0 \"$f\" 2>/dev/null "
                + "    || FAIL=$((FAIL + 1)) ; "
                + "  [ $CNT -ge 500 ] && break ; "
                + "done < \"$TMP\" ; "
                + "rm -f \"$TMP\" ; "
                + "sync ; "
                + "echo \"SELINUX_DONE CNT=$CNT FAIL=$FAIL\"";

        RootHelper.runAsync(fix, SELINUX_TIMEOUT_SEC, r -> {
            if (runToken.get() != myToken) return;
            if (!canTouchUi()) return;

            String out = r.stdout == null ? "" : r.stdout;

            if (out.contains("SELINUX_FAIL tmp")) {
                appendLog("ℹ️ SELinux 修复跳过（临时文件不可用）", C_SUB);
                return;
            }
            if (out.contains("SELINUX_FAIL find")) {
                appendLog("ℹ️ SELinux 修复跳过（查找 .ko 失败）", C_SUB);
                return;
            }
            if (out.contains("SELINUX_PARTIAL")) {
                appendLog("⚠️ 部分目录查找失败，SELinux 修复可能不完整", C_ORANGE);
            }
            if (!out.contains("SELINUX_DONE")) {
                appendLog("ℹ️ SELinux 上下文修复未执行", C_SUB);
                return;
            }

            int cnt  = parseKv(out, "CNT=");
            int fail = parseKv(out, "FAIL=");

            if (cnt == 0) {
                appendLog("ℹ️ 未找到 .ko 文件，跳过 SELinux 修复", C_SUB);
                return;
            }
            if (fail == 0) {
                appendLog("✅ 已修复 SELinux 上下文（" + cnt + " 个）", C_GREEN);
            } else if (fail == cnt) {
                appendLog("ℹ️ SELinux 上下文修复跳过（全部失败，可能只读分区）", C_SUB);
            } else {
                appendLog("⚠️ SELinux 上下文部分失败（"
                        + (cnt - fail) + "/" + cnt + " 成功）", C_ORANGE);
            }
        });
    }

    private static int parseKv(String out, String key) {
        if (out == null || key == null) return -1;
        int idx = out.indexOf(key);
        if (idx < 0) return -1;
        int from = idx + key.length();
        int to = from;
        while (to < out.length() && Character.isDigit(out.charAt(to))) to++;
        if (to == from) return -1;
        try {
            return Integer.parseInt(out.substring(from, to));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void deleteInstalledFiles() {
        if (selectedPath == null) return;
        String pathEsc = shellEscape(selectedPath);
        String cmd = "rm -f " + pathEsc + " 2>/dev/null; echo done";
        RootHelper.runAsync(cmd, DELETE_TIMEOUT_SEC,
                r -> appendLog(r.all(), C_SUB));
    }

    private void endFlash() {
        synchronized (flashLock) {
            flashing = false;
        }
        FlashGuard.clearInProgress();
        if (flashStage != null) {
            flashStage.saveReport();
        }
        MAIN.post(() -> {
            if (!canTouchUi()) return;
            btnStart.setEnabled(true);
            btnStart.setAlpha(1f);
            btnCancel.setEnabled(true);
            btnCancel.setAlpha(1f);
            btnCancel.setText("取消");
        });
    }

    private void killProcessNow() {
        final Process p = currentProcess;
        currentProcess = null;
        if (p == null) return;
        RootHelper.killCompat(p);
    }

    private void appendLog(String text, int color) {
        if (pendingLogCount.get() >= MAX_PENDING_LOGS) {
            droppedLogCount.incrementAndGet();
            return;
        }

        pendingLogCount.incrementAndGet();
        final String fText = text;
        final int fColor = color;

        MAIN.postAtTime(() -> {
            try {
                int dropped = droppedLogCount.getAndSet(0);
                if (dropped > 0) {
                    appendLogOnMain("… 已省略 " + dropped + " 行日志", C_SUB);
                }
                appendLogOnMain(fText, fColor);
            } finally {
                pendingLogCount.decrementAndGet();
            }
        }, LOG_TOKEN, SystemClock.uptimeMillis());
    }

    private void appendLogOnMain(String text, int color) {
        if (logContainer == null || !canTouchUi()) return;

        while (logContainer.getChildCount() >= MAX_LOG_LINES) {
            logContainer.removeViewAt(0);
        }

        TextView line = new TextView(this);
        line.setText(text);
        line.setTextSize(13);
        line.setTextColor(color);
        line.setTypeface(Typeface.MONOSPACE);
        line.setPadding(0, dp(3), 0, dp(3));
        logContainer.addView(line);

        if (!scrollPending) {
            scrollPending = true;
            logScroll.postDelayed(() -> {
                scrollPending = false;
                if (canTouchUi()) {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            }, 80);
        }
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(
                    Intent.createChooser(intent, "选择文件"),
                    REQ_PICK_FILE);
        } catch (Exception e) {
            Log.e(TAG, "pickFile: 无文件管理器", e);
            appendLog("没有可用的文件管理器", C_RED);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE) return;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            appendLog("已取消选择", C_SUB);
            finish();
            return;
        }

        handlePickedUri(data.getData());
    }

    private void handlePickedUri(Uri uri) {
        String fileName = getFileName(uri);

        tvFileName.setText(fileName);
        tvFileName.setTextColor(C_TEXT);
        tvStatus.setText("正在复制文件...");
        tvStatus.setTextColor(C_SUB);
        appendLog("已选择: " + fileName, C_BLUE);
        Log.i(TAG, "handlePickedUri: " + fileName);

        copyCancelled = false;

        new Thread(() -> {
            String realPath = copyToPrivate(uri, fileName);

            if (realPath == null || realPath.startsWith("TOO_LARGE:")) {
                final boolean tooLarge = (realPath != null
                        && realPath.startsWith("TOO_LARGE:"));

                MAIN.post(() -> {
                    if (!canTouchUi()) return;
                    if (tooLarge) {
                        appendLog("❌ 文件超过 "
                                + (MAX_FILE_SIZE / 1024 / 1024) + "MB 上限", C_RED);
                    } else {
                        appendLog("❌ 复制文件失败", C_RED);
                    }
                    finish();
                });
                return;
            }

            MAIN.post(() -> {
                if (!canTouchUi()) return;
                selectedPath = realPath;
                selectedName = fileName;

                SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
                sp.edit().putString(SP_KEY_LAST_PATH, realPath).apply();

                tvStatus.setText("已就绪，点「开始刷入」");
                tvStatus.setTextColor(C_GREEN);
                appendLog("✅ 文件已就绪", C_GREEN);
                btnStart.setEnabled(true);
                btnStart.setAlpha(1f);
            });
        }, "flash-copy").start();
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || c == ':'
                    || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|'
                    || c < 0x20
                    || (c >= 0x7F && c <= 0x9F)
                    || c == 0x200B || c == 0x200C || c == 0x200D
                    || c == 0x200E || c == 0x200F
                    || c == 0x202A || c == 0x202B || c == 0x202C
                    || c == 0x202D || c == 0x202E
                    || c == 0x2066 || c == 0x2067 || c == 0x2068 || c == 0x2069
                    || c == 0xFEFF) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString();
        if (s.length() > 100) s = s.substring(0, 100);
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "unknown";
        return s;
    }

    private String copyToPrivate(Uri uri, String fileName) {
        long size = queryFileSize(uri);
        if (size > MAX_FILE_SIZE) {
            return "TOO_LARGE:" + (size / 1024 / 1024);
        }

        File dir = new File(getFilesDir(), "flash");
        if (!dir.exists() && !dir.mkdirs()) return null;

        String safeName = sanitizeFileName(fileName);
        File outFile = new File(dir, System.currentTimeMillis() + "_" + safeName);

        try {
            if (!outFile.getCanonicalPath().startsWith(dir.getCanonicalPath())) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        try (InputStream is = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(outFile)) {

            if (is == null) return null;

            byte[] buf = new byte[COPY_BUF_SIZE];
            int len;
            long total = 0;
            while ((len = is.read(buf)) > 0) {
                if (copyCancelled) {
                    try { fos.close(); } catch (Throwable ignored) {}
                    try { outFile.delete(); } catch (Throwable ignored) {}
                    return null;
                }

                total += len;
                if (total > MAX_FILE_SIZE) {
                    try { fos.close(); } catch (Throwable ignored) {}
                    try { outFile.delete(); } catch (Throwable ignored) {}
                    return "TOO_LARGE:>";
                }
                fos.write(buf, 0, len);
            }
            fos.flush();

            File[] olds = dir.listFiles();
            if (olds != null) {
                for (File f : olds) {
                    if (!f.equals(outFile)) {
                        try { f.delete(); } catch (Throwable ignored) {}
                    }
                }
            }

            return outFile.getAbsolutePath();
        } catch (Exception e) {
            try { outFile.delete(); } catch (Throwable ignored) {}
            return null;
        }
    }

    private long queryFileSize(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            }
        }
        return result == null ? "unknown" : result;
    }

    private TextView buildButton(String text, int bgColor, int textColor) {
        TextView btn = new TextView(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1f);
        p.setMarginStart(dp(6));
        p.setMarginEnd(dp(6));
        btn.setLayoutParams(p);
        btn.setText(text);
        btn.setTextSize(16);
        btn.setTextColor(textColor);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(createRoundRect(bgColor, dp(27)));
        setPressFeedback(btn);
        return btn;
    }

    private void applyCardStyle(View view) {
        GradientDrawable bg = createRoundRect(C_CARD, dp(20));
        bg.setStroke(dp(1), CARD_STROKE_COLOR);
        view.setBackground(bg);
        view.setElevation(dp(2));
    }

    private void setPressFeedback(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private boolean pressed = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!pressed) {
                            pressed = true;
                            vibrate();
                        }
                        v.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.85f)
                                .setDuration(80)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        break;

                    case MotionEvent.ACTION_MOVE: {
                        boolean inside = isInside(v, event);
                        if (pressed && !inside) {
                            pressed = false;
                            v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                    .setDuration(150).start();
                        } else if (!pressed && inside) {
                            pressed = true;
                            vibrate();
                            v.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.85f)
                                    .setDuration(80).start();
                        }
                        break;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pressed = false;
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(350)
                                .setInterpolator(new OvershootInterpolator(2.5f))
                                .start();
                        break;
                }
                return false;
            }

            private boolean isInside(View v, MotionEvent e) {
                float x = e.getX(), y = e.getY();
                return x >= 0 && x <= v.getWidth()
                        && y >= 0 && y <= v.getHeight();
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

    private GradientDrawable createRoundRect(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    private int dp(float dpValue) {
        return (int) (dpValue * density + 0.5f);
    }
}