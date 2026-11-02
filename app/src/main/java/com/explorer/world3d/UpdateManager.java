package com.explorer.world3d;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks GitHub Releases automatically at startup and manually on request. */
final class UpdateManager {
    private static final String RELEASE_API = "https://api.github.com/repos/saeedhub101/Adam-World/releases/latest";
    private static volatile boolean checking = false;
    private static volatile boolean waitingForPermission = false;
    private static final String APK_NAME = "adam-world-update.apk";

    static void check(final Activity activity, final boolean manual) {
        if (checking) return;
        checking = true;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Adam-World-Android");
                if (c.getResponseCode() != 200) throw new IllegalStateException("Release lookup failed");
                JSONObject release = new JSONObject(readAll(c.getInputStream()));
                Matcher m = Pattern.compile("(\\d+)$").matcher(release.optString("tag_name", ""));
                if (!m.find()) throw new IllegalStateException("Invalid release version");
                int latest = Integer.parseInt(m.group(1));
                if (latest <= BuildConfig.VERSION_CODE) {
                    if (manual) show(activity, "لديك أحدث إصدار من Adam World");
                    return;
                }
                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if ("Adam-World.apk".equals(a.optString("name"))) {
                        apkUrl = a.optString("browser_download_url");
                        break;
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) throw new IllegalStateException("APK asset missing");
                show(activity, "يتوفر تحديث جديد. جارٍ تنزيله…");
                download(apkUrl, activity);
            } catch (Exception e) {
                if (manual) show(activity, "تعذر التحقق من التحديث. تحقق من الإنترنت");
            } finally {
                if (c != null) c.disconnect();
                checking = false;
            }
        }).start();
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return out.toString("UTF-8");
    }

    private static void download(String url, Activity activity) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Adam-World-Android");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("APK download failed");
            File dir = new File(activity.getCacheDir(), "updates");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create update folder");
            File apk = new File(dir, APK_NAME);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] b = new byte[16384]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            activity.runOnUiThread(() -> installOrRequestPermission(activity, apk));
        } finally { c.disconnect(); }
    }

    static void resumePendingInstall(Activity activity) {
        File apk = new File(new File(activity.getCacheDir(), "updates"), APK_NAME);
        if (waitingForPermission && apk.isFile() && apk.length() > 0 && canInstall(activity)) {\n            waitingForPermission = false;\n            install(activity, apk);\n        }
    }

    private static void installOrRequestPermission(Activity activity, File apk) {
        Toast.makeText(activity, "تم تنزيل التحديث. جارٍ فتح مثبت Android", Toast.LENGTH_LONG).show();
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForPermission = true;\n            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        install(activity, apk);
    }

    private static boolean canInstall(Activity a) {
        return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls();
    }

    private static void install(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(activity, "تعذر فتح مثبت التحديث", Toast.LENGTH_LONG).show();
        }
    }

    private static void show(Activity a, String message) {
        a.runOnUiThread(() -> Toast.makeText(a, message, Toast.LENGTH_LONG).show());
    }
}