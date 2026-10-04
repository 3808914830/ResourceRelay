package com.demo.controlcenter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class CoreManager {

    public static final int CMD_INIT   = 1001;
    public static final int CMD_SCAN   = 1002;
    public static final int CMD_FLASH  = 1003;
    public static final int CMD_DELETE = 1004;
    public static final int CMD_EXEC   = 1005;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final long DEBOUNCE_MS = 500L;

    private static final AtomicBoolean SCAN_RUNNING   = new AtomicBoolean(false);
    private static final AtomicBoolean INIT_RUNNING   = new AtomicBoolean(false);
    private static final AtomicBoolean FLASH_RUNNING  = new AtomicBoolean(false);
    private static final AtomicBoolean DELETE_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean EXEC_RUNNING   = new AtomicBoolean(false);

    private static final AtomicLong lastScanTime = new AtomicLong(0L);
    private static final AtomicLong lastInitTime = new AtomicLong(0L);

    private static volatile Packet lastScanPacket = null;

    private static final String[] SCAN_PATHS = {
            "/data/adb/modules",
            "/data/adb/ksu/modules",
            "/data/adb/ap/modules"
    };

    public static class Packet {
        public int     cmd;
        public boolean success;
        public String  message;
        public String  suPath;
        public String  rootType;
        public int     code;
        public List<ModuleInfo> modules = new ArrayList<>();

        public static final int OK           = 0;
        public static final int NO_SU        = 1;
        public static final int DENIED       = 2;
        public static final int ENV_ABNORMAL = 3;
        public static final int TIMEOUT      = 4;

        public Packet copy() {
            Packet c = new Packet();
            c.cmd = this.cmd;
            c.success = this.success;
            c.message = this.message;
            c.suPath = this.suPath;
            c.rootType = this.rootType;
            c.code = this.code;
            if (this.modules != null) {
                c.modules = new ArrayList<>(this.modules.size());
                for (ModuleInfo m : this.modules) {
                    c.modules.add(m == null ? null : m.copy());
                }
            }
            return c;
        }
    }

    public static class ModuleInfo {
        public String name;
        public String path;
        public String status;

        public ModuleInfo copy() {
            ModuleInfo m = new ModuleInfo();
            m.name = this.name;
            m.path = this.path;
            m.status = this.status;
            return m;
        }
    }

    public static abstract class Receiver {
        public abstract void onReceive(Packet packet);
    }

    public static void send(Context context, int cmd, Object[] args, Receiver receiver) {
        if (receiver == null) return;

        switch (cmd) {
            case CMD_INIT:
                handleInit(receiver);
                break;
            case CMD_SCAN:
                handleScan(receiver);
                break;
            case CMD_FLASH: {
                String flashPath = args != null && args.length > 0
                        ? (String) args[0] : null;
                handleFlashWithPath(flashPath, receiver);
                break;
            }
            case CMD_DELETE: {
                String path = args != null && args.length > 0
                        ? (String) args[0] : null;
                handleDelete(path, receiver);
                break;
            }
            case CMD_EXEC: {
                String shell = args != null && args.length > 0
                        ? (String) args[0] : null;
                handleExec(shell, receiver);
                break;
            }
            default: {
                Packet p = new Packet();
                p.cmd = cmd;
                p.success = false;
                p.code = Packet.ENV_ABNORMAL;
                p.message = "未知指令: " + cmd;
                post(receiver, p);
            }
        }
    }

    private static void handleInit(final Receiver receiver) {
        long now = System.currentTimeMillis();
        long last = lastInitTime.get();

        if (now - last < DEBOUNCE_MS || INIT_RUNNING.get()) {
            postInitResult(receiver);
            return;
        }
        lastInitTime.set(now);
        INIT_RUNNING.set(true);

        final boolean[] replied = {false};

        final Runnable timeoutTask = new Runnable() {
            @Override
            public void run() {
                if (replied[0]) return;
                replied[0] = true;
                INIT_RUNNING.set(false);
                Packet p = new Packet();
                p.cmd = CMD_INIT;
                p.success = false;
                p.code = Packet.TIMEOUT;
                p.message = "Root 检测超时";
                post(receiver, p);
            }
        };
        MAIN.postDelayed(timeoutTask, 5000);

        RootManager.get().forceRefresh(new Runnable() {
            @Override
            public void run() {
                if (replied[0]) return;
                replied[0] = true;
                MAIN.removeCallbacks(timeoutTask);
                INIT_RUNNING.set(false);
                postInitResult(receiver);
            }
        });
    }

    private static void postInitResult(Receiver receiver) {
        RootEnv.Info info = RootManager.get().getInfo();

        Packet p = new Packet();
        p.cmd = CMD_INIT;
        p.suPath = info.suPath;
        p.rootType = info.typeName;

        if (info.mode == RootEnv.MODE_GRANTED) {
            p.success = true;
            p.code = Packet.OK;
            p.message = info.typeName + " 已授权";
        } else if (info.mode == RootEnv.MODE_DENIED) {
            p.success = false;
            p.code = Packet.DENIED;
            p.message = "有 Root 未授权";
        } else if (info.mode == RootEnv.MODE_NO_ROOT) {
            p.success = false;
            p.code = Packet.NO_SU;
            p.message = "设备无 Root";
        } else {
            p.success = false;
            p.code = Packet.ENV_ABNORMAL;
            p.message = "环境异常或检测中";
        }
        post(receiver, p);
    }

    private static void handleScan(final Receiver receiver) {
        long now = System.currentTimeMillis();
        long last = lastScanTime.get();

        if (now - last < DEBOUNCE_MS || SCAN_RUNNING.get()) {
            Packet cached = lastScanPacket;
            if (cached != null) {
                post(receiver, cached);
            } else {
                Packet p = new Packet();
                p.cmd = CMD_SCAN;
                p.success = false;
                p.code = Packet.ENV_ABNORMAL;
                p.message = "扫描进行中，请稍候";
                post(receiver, p);
            }
            return;
        }
        lastScanTime.set(now);
        SCAN_RUNNING.set(true);

        StringBuilder sb = new StringBuilder();
        for (String path : SCAN_PATHS) {
            sb.append("echo '@DIR:").append(path).append("'; ");
            sb.append("ls -1 ").append(shellEscape(path))
              .append(" 2>/dev/null; ");
        }
        String cmd = sb.toString();

        RootHelper.runAsync(cmd, 15, new RootHelper.Callback() {
            @Override
            public void onResult(RootHelper.Result r) {
                SCAN_RUNNING.set(false);

                Packet p = new Packet();
                p.cmd = CMD_SCAN;

                if (r == null) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = "扫描失败";
                    post(receiver, p);
                    return;
                }
                if (r.timeout) {
                    p.success = false;
                    p.code = Packet.TIMEOUT;
                    p.message = "扫描超时";
                    post(receiver, p);
                    return;
                }
                if (r.error != null) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = r.error;
                    post(receiver, p);
                    return;
                }

                p.success = true;
                p.code = Packet.OK;

                String out = r.all();
                Set<String> seen = new HashSet<>();
                String currentDir = null;

                if (out != null && !out.isEmpty()) {
                    String[] lines = out.split("\n");
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;

                        if (trimmed.startsWith("@DIR:")) {
                            currentDir = trimmed.substring("@DIR:".length());
                            continue;
                        }

                        if (trimmed.startsWith("[")) continue;

                        if ("lost+found".equals(trimmed)) continue;
                        if (trimmed.contains("/")) continue;
                        if (trimmed.startsWith(".")) continue;
                        if (currentDir == null) continue;

                        String fullPath = currentDir + "/" + trimmed;
                        if (!seen.add(fullPath)) continue;

                        ModuleInfo m = new ModuleInfo();
                        m.name = trimmed;
                        m.path = fullPath;
                        m.status = "已刷入";
                        p.modules.add(m);
                    }
                }

                p.message = "扫描完成，共 " + p.modules.size() + " 个驱动";
                lastScanPacket = p;
                post(receiver, p);
            }
        });
    }

    public static void handleFlashWithPath(String filePath, Receiver receiver) {
        final String path = (filePath != null && !filePath.isEmpty())
                ? filePath
                : "/sdcard/Driver/driver.sh";

        if (!FLASH_RUNNING.compareAndSet(false, true)) {
            Packet p = new Packet();
            p.cmd = CMD_FLASH;
            p.success = false;
            p.code = Packet.ENV_ABNORMAL;
            p.message = "上一个刷入任务进行中，请稍候";
            post(receiver, p);
            return;
        }

        RootEnv.Info info = RootManager.get().getInfo();

        if (info.mode != RootEnv.MODE_GRANTED) {
            FLASH_RUNNING.set(false);
            Packet p = new Packet();
            p.cmd = CMD_FLASH;
            p.success = false;
            p.code = (info.mode == RootEnv.MODE_NO_ROOT)
                    ? Packet.NO_SU : Packet.DENIED;
            p.message = (info.mode == RootEnv.MODE_NO_ROOT)
                    ? "设备无 Root" : "Root 未授权";
            post(receiver, p);
            return;
        }

        String rootType = info.typeName == null ? "" : info.typeName;
        String cmd = buildFlashCommand(rootType, path);

        RootHelper.runAsync(cmd, 90, new RootHelper.Callback() {
            @Override
            public void onResult(RootHelper.Result r) {
                FLASH_RUNNING.set(false);

                Packet p = new Packet();
                p.cmd = CMD_FLASH;

                if (r == null) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = "刷入失败";
                    post(receiver, p);
                    return;
                }
                if (r.timeout) {
                    p.success = false;
                    p.code = Packet.TIMEOUT;
                    p.message = "刷入超时（90秒）";
                    post(receiver, p);
                    return;
                }
                if (r.error != null) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = r.error;
                    post(receiver, p);
                    return;
                }

                String out = r.all();
                boolean hasDone  = out != null && out.contains("FLASH_DONE_OK");
                boolean hasError = out != null && out.contains("ERROR:");

                if (r.ok() && hasDone && !hasError) {
                    p.success = true;
                    p.code = Packet.OK;
                    p.message = "刷入完成";
                    lastScanPacket = null;
                } else if (hasError) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = extractError(out);
                } else if (!hasDone) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = "脚本执行但未完成（缺少完成标记）";
                } else {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = "刷入失败 (exit=" + r.exitCode + ")";
                }
                post(receiver, p);
            }
        });
    }

    private static String buildFlashCommand(String rootType, String path) {
        String esc = shellEscape(path);

        if (path.endsWith(".zip")) {
            if (rootType.contains("Magisk")) {
                return "for m in /data/adb/magisk/magisk /debug_ramdisk/magisk "
                        + "/system/bin/magisk /sbin/magisk; do "
                        + "[ -x \"$m\" ] && MAGISK=\"$m\" && break; done; "
                        + "[ -z \"$MAGISK\" ] && echo 'ERROR: magisk not found' && exit 1; "
                        + "\"$MAGISK\" --install-module " + esc + "; echo FLASH_DONE_OK";
            } else if (rootType.contains("KernelSU")) {
                return "KSUD=$(command -v ksud 2>/dev/null); "
                        + "[ -z \"$KSUD\" ] && KSUD=/data/adb/ksu/bin/ksud; "
                        + "[ ! -x \"$KSUD\" ] && echo 'ERROR: ksud not found' && exit 1; "
                        + "\"$KSUD\" module install " + esc + " 2>&1 || "
                        + "\"$KSUD\" module install --zip " + esc + " 2>&1; "
                        + "echo FLASH_DONE_OK";
            } else if (rootType.contains("APatch")) {
                return "for m in /data/adb/magisk/magisk /data/adb/ap/bin/magisk "
                        + "/system/bin/magisk; do "
                        + "[ -x \"$m\" ] && MAGISK=\"$m\" && break; done; "
                        + "[ -z \"$MAGISK\" ] && echo 'ERROR: apatch compat not found' && exit 1; "
                        + "\"$MAGISK\" --install-module " + esc + "; echo FLASH_DONE_OK";
            } else {
                return "echo "
                        + shellEscape("ERROR: 未识别的 Root 类型: " + rootType)
                        + "; exit 1";
            }
        } else {
            return "chmod 755 " + esc + " && sh " + esc + "; "
                    + "echo FLASH_DONE_OK";
        }
    }

    private static String extractError(String out) {
        if (out == null) return "未知错误";
        String[] lines = out.split("\n");
        for (String line : lines) {
            if (line.contains("ERROR:")) return line.trim();
        }
        return "未知错误";
    }

    private static void handleDelete(String path, Receiver receiver) {
        Packet p = new Packet();
        p.cmd = CMD_DELETE;

        if (TextUtils.isEmpty(path)) {
            p.success = false;
            p.code = Packet.ENV_ABNORMAL;
            p.message = "路径为空";
            post(receiver, p);
            return;
        }

        if (isProtectedRoot(path)) {
            p.success = false;
            p.code = Packet.ENV_ABNORMAL;
            p.message = "拒绝删除模块根目录";
            post(receiver, p);
            return;
        }

        if (path.contains("..")) {
            p.success = false;
            p.code = Packet.ENV_ABNORMAL;
            p.message = "路径非法";
            post(receiver, p);
            return;
        }

        if (!DELETE_RUNNING.compareAndSet(false, true)) {
            p.success = false;
            p.code = Packet.ENV_ABNORMAL;
            p.message = "上一个删除任务进行中，请稍候";
            post(receiver, p);
            return;
        }

        final String cmd = "rm -rf " + shellEscape(path);

        RootHelper.runAsync(cmd, 15, new RootHelper.Callback() {
            @Override
            public void onResult(RootHelper.Result r) {
                DELETE_RUNNING.set(false);

                Packet p = new Packet();
                p.cmd = CMD_DELETE;

                if (r == null) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = "删除失败";
                } else if (r.timeout) {
                    p.success = false;
                    p.code = Packet.TIMEOUT;
                    p.message = "删除超时";
                } else if (!r.ok()) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = r.error != null ? r.error : "删除失败";
                } else {
                    p.success = true;
                    p.code = Packet.OK;
                    p.message = "已删除";
                    lastScanPacket = null;
                }
                post(receiver, p);
            }
        });
    }

    private static boolean isProtectedRoot(String path) {
        if (path == null) return false;

        String canonical;
        try {
            canonical = new File(path).getCanonicalPath();
        } catch (Throwable t) {
            canonical = path;
        }
        if (canonical == null) return true;

        while (canonical.length() > 1 && canonical.endsWith("/")) {
            canonical = canonical.substring(0, canonical.length() - 1);
        }

        for (String root : SCAN_PATHS) {
            if (canonical.equals(root)) return true;
        }
        return false;
    }

    private static void handleExec(String shell, Receiver receiver) {
        Packet p = new Packet();
        p.cmd = CMD_EXEC;

        if (TextUtils.isEmpty(shell)) {
            p.success = false;
            p.code = Packet.ENV_ABNORMAL;
            p.message = "命令为空";
            post(receiver, p);
            return;
        }

        if (!EXEC_RUNNING.compareAndSet(false, true)) {
            p.success = false;
            p.code = Packet.ENV_ABNORMAL;
            p.message = "上一个命令进行中，请稍候";
            post(receiver, p);
            return;
        }

        RootHelper.runAsync(shell, 30, new RootHelper.Callback() {
            @Override
            public void onResult(RootHelper.Result r) {
                EXEC_RUNNING.set(false);

                Packet p = new Packet();
                p.cmd = CMD_EXEC;

                if (r == null) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = "命令失败";
                } else if (r.timeout) {
                    p.success = false;
                    p.code = Packet.TIMEOUT;
                    p.message = "命令超时";
                } else if (!r.ok()) {
                    p.success = false;
                    p.code = Packet.ENV_ABNORMAL;
                    p.message = r.error != null ? r.error : "命令失败";
                } else {
                    p.success = true;
                    p.code = Packet.OK;
                    String out = r.all();
                    p.message = (out == null || out.isEmpty()) ? "(无输出)" : out;
                }
                post(receiver, p);
            }
        });
    }

    private static void post(final Receiver receiver, final Packet p) {
        if (receiver == null || p == null) return;
        final Packet copy = p.copy();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    receiver.onReceive(copy);
                } catch (Throwable ignored) {}
            }
        });
    }

    private static String shellEscape(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}