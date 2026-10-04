package com.demo.controlcenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

public class RootManager {

    private static volatile RootManager instance;

    private static final long CACHE_TTL          = 5000L;
    private static final long CACHE_TTL_DENIED   = 1000L;
    private static final long CACHE_TTL_UNKNOWN  = 500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Object LOCK = new Object();
    private RootEnv.Info cachedInfo = new RootEnv.Info();
    private long lastCheckTime = 0L;
    private boolean detecting = false;

    private RootManager() {}

    public static RootManager get() {
        if (instance == null) {
            synchronized (RootManager.class) {
                if (instance == null) instance = new RootManager();
            }
        }
        return instance;
    }

    public RootEnv.Info getInfo() {
        synchronized (LOCK) {
            return cachedInfo;
        }
    }

    public boolean canWrite() {
        return getInfo().canWrite();
    }

    public boolean hasSu() {
        return getInfo().hasSu();
    }

    public void refresh()                     { refresh(null, false); }
    public void refresh(Runnable onDone)      { refresh(onDone, false); }
    public void forceRefresh()                { refresh(null, true);  }
    public void forceRefresh(Runnable onDone) { refresh(onDone, true);  }

    private long getTtlForMode(int mode) {
        switch (mode) {
            case RootEnv.MODE_DENIED:  return CACHE_TTL_DENIED;
            case RootEnv.MODE_UNKNOWN: return CACHE_TTL_UNKNOWN;
            default:                   return CACHE_TTL;
        }
    }

    private void refresh(final Runnable onDone, final boolean skipCache) {
        if (!skipCache) {
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                long ttl = getTtlForMode(cachedInfo.mode);
                if (now - lastCheckTime < ttl
                        && cachedInfo.mode != RootEnv.MODE_UNKNOWN) {
                    post(onDone);
                    return;
                }
            }
        }

        synchronized (LOCK) {
            if (detecting) {
                post(onDone);
                return;
            }
            detecting = true;
        }

        new Thread(() -> {
            RootEnv.Info fresh;
            try {
                fresh = RootEnv.detect();
                if (fresh == null) {
                    fresh = new RootEnv.Info();
                    fresh.mode = RootEnv.MODE_NO_ROOT;
                    fresh.typeName = "检测失败";
                }
            } catch (Throwable t) {
                fresh = new RootEnv.Info();
                fresh.mode = RootEnv.MODE_NO_ROOT;
                fresh.typeName = "检测异常";
                fresh.lastCheck = System.currentTimeMillis();
            }

            synchronized (LOCK) {
                if (fresh.mode == RootEnv.MODE_UNKNOWN) {
                    fresh.mode = RootEnv.MODE_NO_ROOT;
                    if ("未检测".equals(fresh.typeName)) {
                        fresh.typeName = "检测超时";
                    }
                }
                cachedInfo = fresh;
                lastCheckTime = System.currentTimeMillis();
                detecting = false;
            }

            post(onDone);

        }, "root-detect").start();
    }

    private void post(Runnable r) {
        if (r != null) MAIN.post(r);
    }

    public boolean requireRoot(Activity activity) {
        if (canWrite()) return true;

        RootEnv.Info info = getInfo();
        String msg;
        if (info.isUnknown()) {
            msg = "正在检测 Root 状态，请稍候再试。";
        } else if (!info.hasSu()) {
            msg = "当前设备未 Root\n\n" +
                    "部分功能需要 Root 权限才能使用。\n" +
                    "您可以浏览应用，但无法执行修改操作。";
        } else {
            msg = "Root 权限未授权\n\n" +
                    "请在 Root 管理器中授权本应用。";
        }

        new AlertDialog.Builder(activity)
                .setTitle("权限不足")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .setNegativeButton("去授权", (d, w) -> openRootGrantPage(activity))
                .show();
        return false;
    }

    public void openRootGrantPage(Activity activity) {
        String[] pkgs = {
                "com.topjohnwu.magisk",
                "io.github.huskydg.magisk",
                "me.weishu.kernelsu",
                "me.bmax.apatch"
        };
        for (String pkg : pkgs) {
            try {
                android.content.Intent i =
                        activity.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    activity.startActivity(i);
                    return;
                }
            } catch (Exception ignored) {}
        }
        new AlertDialog.Builder(activity)
                .setTitle("未找到 Root 管理器")
                .setMessage("请手动打开 Root 管理器授权本应用。")
                .setPositiveButton("知道了", null)
                .show();
    }
}