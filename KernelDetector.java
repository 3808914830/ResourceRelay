package com.demo.controlcenter;

import android.os.Build;

import java.io.BufferedReader;
import java.io.FileReader;

public class KernelDetector {

    private static final String[][] HARMONY_MAP = {
            {"2.0", "4.14.116"},
            {"2.1", "4.14.116"},
            {"3.0", "4.14.116"},
            {"3.1", "4.14.116"},
            {"4.0", "5.10.43"},
            {"4.1", "5.10.43"},
            {"4.2", "5.10.43"},
    };

    private static final String[] MODEL_414 = {
            "JSN", "JKM", "HRY", "YAL", "HLK", "SEA", "VOG", "ELE",
            "LYA", "EVR", "PAR", "MAR", "YOK"
    };

    private static final String[] MODEL_510 = {
            "NOH", "NOP", "OCE", "ELS", "ANA", "JAD", "ABR",
            "CET", "MNA", "LNA", "HBN"
    };

    public static boolean isHuaweiOrHonor() {
        String brand = Build.BRAND == null ? "" : Build.BRAND;
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        return brand.equalsIgnoreCase("HUAWEI")
                || brand.equalsIgnoreCase("HONOR")
                || manufacturer.equalsIgnoreCase("HUAWEI")
                || manufacturer.equalsIgnoreCase("HONOR");
    }

    private static String readProcVersion() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/version"));
            String line = r.readLine();
            r.close();
            if (line == null) return null;

            String[] parts = line.split("\\s+");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("version".equals(parts[i]) && i + 1 < parts.length) {
                    String v = parts[i + 1];
                    if (v.matches("\\d+\\.\\d+.*")) {
                        int dash = v.indexOf('-');
                        if (dash > 0) v = v.substring(0, dash);
                        return v;
                    }
                }
            }

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+\\.\\d+\\.\\d+)")
                    .matcher(line);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    private static String readUname() {
        try {
            Process p = Runtime.getRuntime().exec("uname -r");
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            if (line == null) return null;
            line = line.trim();
            int dash = line.indexOf('-');
            if (dash > 0) line = line.substring(0, dash);
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getHarmonyVersion() {
        String display = Build.DISPLAY == null ? "" : Build.DISPLAY;
        int idx = display.toLowerCase().indexOf("harmonyos ");
        if (idx >= 0) {
            String rest = display.substring(idx + 10).trim();
            String[] parts = rest.split("\\.");
            if (parts.length >= 2) return parts[0] + "." + parts[1];
        }
        String release = Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE;
        String[] parts = release.split("\\.");
        if (parts.length >= 2) return parts[0] + "." + parts[1];
        return null;
    }

    public static String getKernelVersion() {
        String v = readProcVersion();
        if (v != null && !v.isEmpty()) {
            return v;
        }

        v = readUname();
        if (v != null && !v.isEmpty()) {
            return v;
        }

        if (isHuaweiOrHonor()) {
            String hv = getHarmonyVersion();
            if (hv != null) {
                try {
                    float ver = Float.parseFloat(hv);
                    if (ver >= 5.0f) return "未知";
                } catch (Exception ignored) {}

                for (String[] e : HARMONY_MAP) {
                    if (hv.equals(e[0])) return e[1];
                }
            }

            String model = Build.MODEL == null ? "" : Build.MODEL.toUpperCase();
            for (String p : MODEL_414) {
                if (model.startsWith(p)) return "4.14.116";
            }
            for (String p : MODEL_510) {
                if (model.startsWith(p)) return "5.10.43";
            }
        }

        return "未知";
    }

    public static boolean isKernelMeasurable() {
        String kv = getKernelVersion();
        return !"未知".equals(kv);
    }
}