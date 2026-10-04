package com.demo.controlcenter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class RootEnv {

    public static final int MODE_UNKNOWN = 0;
    public static final int MODE_NO_ROOT = 1;
    public static final int MODE_DENIED  = 2;
    public static final int MODE_GRANTED = 3;
    public static final int MODE_PENDING = 4;

    private static final long VER_TIMEOUT_MS      = 1500L;
    private static final long SU_TIMEOUT_MS       = 3000L;
    private static final long FIRST_SU_TIMEOUT_MS = 8000L;

    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/vendor/bin/su", "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
    };

    private static final String KSU_DIR     = "/data/adb/ksu";
    private static final String KSU_MODULE  = "/sys/module/kernelsu";
    private static final String AP_DIR      = "/data/adb/ap";
    private static final String AP_MODULE   = "/sys/module/apatch";
    private static final String MAGISK_DIR  = "/data/adb/magisk";
    private static final String MAGISK_SBIN = "/sbin/.magisk";

    private static final String[][] KSU_VER_CMDS = {
            {"/data/adb/ksu/bin/ksud", "-V"},
            {"ksud",                   "-V"}
    };
    private static final String[][] AP_VER_CMDS = {
            {"/data/adb/ap/bin/apd", "-V"},
            {"apd",                  "-V"}
    };
    private static final String[][] MAGISK_VER_CMDS = {
            {"/system/bin/magisk",      "-v"},
            {"/debug_ramdisk/magisk",   "-v"},
            {"/data/adb/magisk/magisk", "-v"},
            {"magisk",                  "-v"}
    };

    private static volatile boolean sFirstProbe = true;

    public static class Info {
        public int    mode      = MODE_UNKNOWN;
        public String typeName  = "未检测";
        public String version   = "";
        public String suPath    = "";
        public long   lastCheck = 0L;

        public boolean canWrite() { return mode == MODE_GRANTED; }
        public boolean hasSu()    { return mode == MODE_DENIED || mode == MODE_GRANTED; }
        public boolean isUnknown(){ return mode == MODE_UNKNOWN; }
        public boolean isPending(){ return mode == MODE_PENDING; }
    }

    public static Info detect() {
        Info info = new Info();
        info.suPath = findSuPath();
        detectRootType(info);

        if (info.suPath.isEmpty()) {
            info.mode = MODE_NO_ROOT;
            info.typeName = "未 Root";
            info.version  = "";
            info.lastCheck = System.currentTimeMillis();
            return info;
        }

        info.mode = probeSuGranted(info.suPath);
        info.lastCheck = System.currentTimeMillis();
        return info;
    }

    private static String findSuPath() {
        for (String p : SU_PATHS) {
            try {
                if (new File(p).exists()) return p;
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static void detectRootType(Info info) {
        try {
            String  ksuVer       = tryReadVersion(KSU_VER_CMDS);
            boolean ksuVerHit    = (ksuVer != null && !ksuVer.isEmpty());
            boolean ksuKernelHit = new File(KSU_MODULE).exists();
            boolean ksuDirHit    = new File(KSU_DIR).exists();

            String  apVer       = tryReadVersion(AP_VER_CMDS);
            boolean apVerHit    = (apVer != null && !apVer.isEmpty());
            boolean apKernelHit = new File(AP_MODULE).exists();
            boolean apDirHit    = new File(AP_DIR).exists();

            String  mgVer    = tryReadVersion(MAGISK_VER_CMDS);
            boolean mgVerHit = (mgVer != null && !mgVer.isEmpty());
            boolean mgDirHit = (new File(MAGISK_DIR).exists()
                             || new File(MAGISK_SBIN).exists())
                             && !ksuVerHit && !ksuKernelHit;

            if (ksuVerHit) {
                info.typeName = "KernelSU";
                info.version  = ksuVer;
                return;
            }
            if (apVerHit) {
                info.typeName = "APatch";
                info.version  = apVer;
                return;
            }
            if (mgVerHit) {
                info.typeName = "Magisk";
                info.version  = mgVer;
                return;
            }

            if (ksuKernelHit) {
                info.typeName = "KernelSU";
                info.version  = "";
                return;
            }
            if (apKernelHit) {
                info.typeName = "APatch";
                info.version  = "";
                return;
            }
            if (mgDirHit) {
                info.typeName = "Magisk";
                info.version  = "";
                return;
            }

            if (!info.suPath.isEmpty()) {
                if (ksuDirHit) {
                    info.typeName = "KernelSU";
                    info.version  = "";
                    return;
                }
                if (apDirHit) {
                    info.typeName = "APatch";
                    info.version  = "";
                    return;
                }
                info.typeName = "Unknown Root";
            }
        } catch (Throwable ignored) {
        }
    }

    private static String tryReadVersion(String[][] candidates) {
        for (String[] cmd : candidates) {
            String v = readCmdWithTimeout(cmd, VER_TIMEOUT_MS);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static int probeSuGranted(String suPath) {
        String bin = (suPath != null && !suPath.isEmpty()) ? suPath : "su";

        boolean first = sFirstProbe;
        long timeout = first ? FIRST_SU_TIMEOUT_MS : SU_TIMEOUT_MS;

        String out = readCmdWithTimeout(new String[]{bin, "-c", "id"}, timeout);

        if (out == null) {
            return MODE_PENDING;
        }

        sFirstProbe = false;
        return out.contains("uid=0") ? MODE_GRANTED : MODE_DENIED;
    }

    private static String readCmdWithTimeout(String[] cmd, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();

            final Process fp = p;
            final StringBuilder sb = new StringBuilder();
            final Object lock = new Object();
            final boolean[] done = {false};

            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(fp.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        synchronized (lock) {
                            done[0] = true;
                            lock.notifyAll();
                        }
                    }
                }
            }, "rootenv-reader");
            reader.setDaemon(true);
            reader.start();

            synchronized (lock) {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (!done[0]) {
                    long wait = deadline - System.currentTimeMillis();
                    if (wait <= 0) break;
                    try {
                        lock.wait(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!done[0]) {
                try { p.getInputStream().close();  } catch (Throwable ignored) {}
                try { p.destroyForcibly();          } catch (Throwable ignored) {}
                return null;
            }
            return sb.toString().trim();

        } catch (Throwable e) {
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }
}