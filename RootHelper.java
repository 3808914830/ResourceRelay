package com.demo.controlcenter;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class RootHelper {

    private RootHelper() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final ExecutorService READ_POOL =
            Executors.newFixedThreadPool(2);

    private static final ExecutorService RUN_POOL =
            Executors.newSingleThreadExecutor();

    private static final ExecutorService KILL_POOL =
            Executors.newSingleThreadExecutor();

    private static final int DEFAULT_TIMEOUT_SEC = 30;

    private static final int DRAIN_TIMEOUT_SEC = 5;
    private static final int DRAIN_KILLED_TIMEOUT_SEC = 2;

    private static final long POLL_INTERVAL_MS = 50L;

    private static final int MAX_LINE_CHARS = 1 << 20;

    private static final long DESTROY_GRACE_MS = 500L;

    private static final long KILL_SU_WAIT_MS = 2000L;

    private static final String[] FATAL_STDERR_KEYWORDS = {
            "permission denied",
            "operation not permitted",
            "read-only file system",
    };

    private static final AtomicReference<String> CURRENT_TASK =
            new AtomicReference<>("");

    private static final Method DESTROY_FORCIBLY_METHOD;
    private static final Method PID_METHOD;

    static {
        Method df = null;
        Method pid = null;
        try { df = Process.class.getMethod("destroyForcibly"); }
        catch (Throwable ignored) {}
        try { pid = Process.class.getMethod("pid"); }
        catch (Throwable ignored) {}
        DESTROY_FORCIBLY_METHOD = df;
        PID_METHOD = pid;
    }

    public static String getCurrentTask() {
        return CURRENT_TASK.get();
    }

    public static class Result {
        public int     exitCode = -1;
        public String  stdout   = "";
        public String  stderr   = "";
        public volatile boolean timeout = false;
        public String  error    = null;

        public boolean ok() {
            if (timeout || error != null) return false;
            if (exitCode != 0) return false;

            if (stderr != null && !stderr.isEmpty()) {
                String s = stderr.toLowerCase(Locale.ROOT);
                for (String kw : FATAL_STDERR_KEYWORDS) {
                    if (s.contains(kw)) return false;
                }
            }
            return true;
        }

        public String all() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) sb.append(stdout);
            if (stderr != null && !stderr.isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
                sb.append("[stderr]\n").append(stderr);
            }
            return sb.toString();
        }
    }

    public interface Callback {
        void onResult(Result r);
    }

    public static Result run(String cmd, long timeoutSec) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "RootHelper.run() must not be called on main thread; use runAsync()");
        }
        if (cmd == null) cmd = "";
        if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SEC;

        Result r = new Result();
        Process p = null;
        Future<String> fOut = null;
        Future<String> fErr = null;

        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(false);
            p = pb.start();

            final Process fp = p;
            final InputStream outIs = fp.getInputStream();
            final InputStream errIs = fp.getErrorStream();

            fOut = READ_POOL.submit(new StreamReader(outIs));
            fErr = READ_POOL.submit(new StreamReader(errIs));

            boolean exited = waitForWithTimeout(fp, timeoutSec);
            if (!exited) {
                r.timeout = true;
                killProcess(fp);
            }

            long drainSec = exited ? DRAIN_TIMEOUT_SEC : DRAIN_KILLED_TIMEOUT_SEC;
            try {
                r.stdout = fOut.get(drainSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                r.stdout = "";
            }
            try {
                r.stderr = fErr.get(drainSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                r.stderr = "";
            }

            if (exited) {
                try {
                    r.exitCode = fp.exitValue();
                } catch (IllegalThreadStateException e) {
                    r.exitCode = -1;
                }
            } else {
                r.exitCode = -1;
            }

        } catch (IOException e) {
            r.error = "IO 异常: " + safeMsg(e);
        } catch (Throwable t) {
            r.error = "执行异常: " + safeMsg(t);
        } finally {
            if (fOut != null) fOut.cancel(false);
            if (fErr != null) fErr.cancel(false);
            if (p != null) {
                try { killProcess(p); } catch (Throwable ignored) {}
            }
        }
        return r;
    }

    public static Result run(String cmd) {
        return run(cmd, DEFAULT_TIMEOUT_SEC);
    }

    public static void runAsync(String cmd, Callback cb) {
        runAsync(cmd, DEFAULT_TIMEOUT_SEC, cb);
    }

    public static void runAsync(String cmd, long timeoutSec, Callback cb) {
        if (cb == null) return;

        try {
            final String fcmd = cmd == null ? "" : cmd;
            final long   fto  = timeoutSec <= 0 ? DEFAULT_TIMEOUT_SEC : timeoutSec;

            RUN_POOL.execute(() -> {
                CURRENT_TASK.set(fcmd);
                try {
                    Result r = run(fcmd, fto);
                    post(cb, r);
                } finally {
                    CURRENT_TASK.compareAndSet(fcmd, "");
                }
            });
        } catch (Throwable t) {
            Result err = new Result();
            err.error = "启动任务失败: " + safeMsg(t);
            post(cb, err);
        }
    }

    public static void killCompat(Process p) {
        if (p == null) return;
        try {
            killProcess(p);
        } catch (Throwable ignored) {}
    }

    private static boolean waitForWithTimeout(Process p, long timeoutSec) {
        if (p == null) return true;

        long deadlineMs = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
            } catch (Throwable t) {
                return false;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void killProcess(Process p) {
        if (p == null) return;

        if (DESTROY_FORCIBLY_METHOD != null) {
            try {
                DESTROY_FORCIBLY_METHOD.invoke(p);
                return;
            } catch (Throwable ignored) {
            }
        }

        try {
            KILL_POOL.execute(() -> slowKill(p));
        } catch (Throwable ignored) {
            try { p.destroy(); } catch (Throwable ignored2) {}
        }
    }

    private static void slowKill(Process p) {
        if (p == null) return;

        try { p.destroy(); } catch (Throwable ignored) {}

        long deadline = System.currentTimeMillis() + DESTROY_GRACE_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return;
            } catch (IllegalThreadStateException e) {
            } catch (Throwable t) {
                return;
            }
            try { Thread.sleep(POLL_INTERVAL_MS); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        final int pid = getPidCompat(p);
        if (pid <= 0) return;

        Process kp = null;
        try {
            kp = new ProcessBuilder("su", "-c", "kill -9 " + pid)
                    .redirectErrorStream(true)
                    .start();

            try (InputStream is = kp.getInputStream()) {
                byte[] buf = new byte[256];
                while (is.read(buf) > 0) {
                }
            }

            long dl = System.currentTimeMillis() + KILL_SU_WAIT_MS;
            while (System.currentTimeMillis() < dl) {
                try {
                    kp.exitValue();
                    break;
                } catch (IllegalThreadStateException e) {
                } catch (Throwable t) {
                    break;
                }
                try { Thread.sleep(POLL_INTERVAL_MS); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (kp != null) {
                try { kp.destroy(); } catch (Throwable ignored2) {}
            }
        }
    }

    private static int getPidCompat(Process p) {
        if (p == null) return -1;

        if (PID_METHOD != null) {
            try {
                Object v = PID_METHOD.invoke(p);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {
            }
        }

        Class<?> cls = p.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField("pid");
                f.setAccessible(true);
                Object v = f.get(p);
                if (v instanceof Integer) return (Integer) v;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return -1;
            }
            cls = cls.getSuperclass();
        }
        return -1;
    }

    private static void post(Callback cb, Result r) {
        MAIN.post(() -> {
            if (cb != null) {
                try { cb.onResult(r); } catch (Throwable ignored) {}
            }
        });
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "未知";
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static final class StreamReader implements Callable<String> {
        private final InputStream is;
        StreamReader(InputStream is) { this.is = is; }

        @Override
        public String call() {
            if (is == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.length() > MAX_LINE_CHARS) {
                        sb.append(line, 0, MAX_LINE_CHARS)
                          .append("...[truncated]\n");
                    } else {
                        sb.append(line).append('\n');
                    }
                }
            } catch (Throwable ignored) {
            }
            return sb.toString();
        }
    }
}