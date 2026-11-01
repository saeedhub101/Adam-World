package com.explorer.world3d;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_LOC = 77;
    private static final String RELEASES_API =
            "https://api.github.com/repos/saeedhub101/Adam-World/releases/latest";
    private WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private DownloadManager downloadManager;
    private long updateDownloadId = -1;
    private String downloadedApkPath;
    private boolean updateCheckRunning = false;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == updateDownloadId) {
                    DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                    try (android.database.Cursor cursor = downloadManager.query(query)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                String uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                                downloadedApkPath = Uri.parse(uri).getPath();
                                installDownloadedUpdate();
                            } else {
                                notifyPage("فشل تنزيل التحديث. حاول مرة أخرى.");
                                Toast.makeText(MainActivity.this, "فشل تنزيل التحديث", Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                }
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");

        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(downloadReceiver, filter);

        // Check silently on every app launch; only show a dialog when a newer release exists.
        checkForUpdates(true);
    }

    private class Bridge {
        @JavascriptInterface public void requestLocation() { runOnUiThread(() -> fetchLocation()); }
        @JavascriptInterface public void checkForUpdates() { MainActivity.this.checkForUpdates(false); }

        // Native networking avoids WebView cross-origin restrictions for file:// pages.
        // Public, keyless imagery catalogues only. Limit hosts and response size.
        @JavascriptInterface public void requestSourceJson(String sourceUrl, String requestId) {
            executor.execute(() -> {
                String payload = null;
                String failure = "تعذر الوصول إلى مصدر الصور";
                HttpURLConnection c = null;
                try {
                    URL parsed = new URL(sourceUrl);
                    String host = parsed.getHost();
                    if (!"api.imagery.hotosm.org".equals(host) && !"api.panoramax.xyz".equals(host)) {
                        throw new SecurityException("مصدر غير مسموح");
                    }
                    if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                        throw new SecurityException("يلزم اتصال HTTPS");
                    }
                    c = (HttpURLConnection) parsed.openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(18000);
                    c.setRequestProperty("Accept", "application/geo+json, application/json");
                    c.setRequestProperty("User-Agent", "AdamWorld/1.0 (open imagery metadata)");
                    int status = c.getResponseCode();
                    if (status != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + status);
                    try (InputStream in = c.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                        StringBuilder out = new StringBuilder();
                        char[] buffer = new char[8192];
                        int n;
                        while ((n = reader.read(buffer)) != -1) {
                            out.append(buffer, 0, n);
                            if (out.length() > 4 * 1024 * 1024) throw new Exception("استجابة مصدر الصور كبيرة جداً");
                        }
                        payload = out.toString();
                    }
                } catch (Exception ex) {
                    failure = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                } finally {
                    if (c != null) c.disconnect();
                }
                final String result = payload;
                final String error = result == null ? failure : null;
                runOnUiThread(() -> {
                    if (webView != null) {
                        String script = "window.onNativeSourceResult("
                                + JSONObject.quote(requestId) + ","
                                + (result == null ? "null" : JSONObject.quote(result)) + ","
                                + (error == null ? "null" : JSONObject.quote(error)) + ")";
                        webView.evaluateJavascript(script, null);
                    }
                });
            });
        }

        @JavascriptInterface public void requestOverpass(String query, String requestId) {
            executor.execute(() -> {
                String result = null;
                String error = "تعذر الوصول إلى خوادم بيانات الخريطة";
                String[] servers = {
                        "https://overpass-api.de/api/interpreter",
                        "https://overpass.kumi.systems/api/interpreter",
                        "https://overpass.private.coffee/api/interpreter"
                };
                for (String endpoint : servers) {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(endpoint).openConnection();
                        c.setRequestMethod("POST");
                        c.setConnectTimeout(20000);
                        c.setReadTimeout(50000);
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                        c.setRequestProperty("User-Agent", "AdamWorld/1.0 (Android)");
                        byte[] body = ("data=" + java.net.URLEncoder.encode(query, "UTF-8")).getBytes("UTF-8");
                        try (java.io.OutputStream os = c.getOutputStream()) { os.write(body); }
                        int status = c.getResponseCode();
                        if (status != HttpURLConnection.HTTP_OK) {
                            error = "خادم الخريطة أعاد HTTP " + status;
                            continue;
                        }
                        StringBuilder out = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                            String line;
                            while ((line = reader.readLine()) != null) out.append(line);
                        }
                        result = out.toString();
                        break;
                    } catch (Exception ex) {
                        error = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "network error" : ex.getMessage());
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
                final String payload = result;
                final String failure = result == null ? error : null;
                runOnUiThread(() -> {
                    if (webView != null) {
                        String script = "window.onNativeOverpassResult("
                                + JSONObject.quote(requestId) + ","
                                + (payload == null ? "null" : JSONObject.quote(payload)) + ","
                                + (failure == null ? "null" : JSONObject.quote(failure)) + ")";
                        webView.evaluateJavascript(script, null);
                    }
                });
            });
        }
    }

    private void checkForUpdates(boolean automatic) {
        if (updateCheckRunning) {
            if (!automatic) notifyPage("جارٍ التحقق من وجود تحديث…");
            return;
        }
        updateCheckRunning = true;
        if (!automatic) notifyPage("جارٍ التحقق من GitHub Releases…");
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                int response = connection.getResponseCode();
                if (response != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + response);
                StringBuilder body = new StringBuilder();
                try (InputStream in = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject release = new JSONObject(body.toString());
                String tag = release.optString("tag_name", "");
                int latestCode = parseVersionCode(tag);
                String assetUrl = null;
                String assetName = null;
                long assetSize = 0;
                org.json.JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            assetName = name;
                            assetUrl = asset.optString("browser_download_url", null);
                            assetSize = asset.optLong("size", 0);
                            if ("app-debug.apk".equals(name)) break;
                        }
                    }
                }
                final String version = release.optString("name", tag);
                final String url = assetUrl;
                final String name = assetName;
                final long size = assetSize;
                final boolean hasUpdate = latestCode > BuildConfig.VERSION_CODE && url != null;
                runOnUiThread(() -> {
                    updateCheckRunning = false;
                    if (hasUpdate) showUpdateDialog(version, url, name, size);
                    else if (!automatic) notifyPage("التطبيق محدث بالفعل. الإصدار الحالي: " + BuildConfig.VERSION_NAME);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateCheckRunning = false;
                    if (!automatic) notifyPage("تعذر التحقق من التحديثات. تحقق من اتصال الإنترنت.");
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private int parseVersionCode(String tag) {
        try {
            String digits = tag.replaceAll("[^0-9]", "");
            return Integer.parseInt(digits);
        } catch (Exception e) { return 0; }
    }

    private void showUpdateDialog(String version, String url, String name, long size) {
        String sizeText = size > 0 ? String.format(java.util.Locale.US, "%.1f MB", size / 1048576.0) : "غير معروف";
        new AlertDialog.Builder(this)
                .setTitle("يتوفر تحديث جديد لـ Adam World")
                .setMessage("الإصدار: " + version + "\nالحجم: " + sizeText + "\n\nهل تريد تنزيل التحديث الآن؟")
                .setNegativeButton("لاحقاً", (d, w) -> notifyPage("يوجد تحديث جديد متاح في GitHub Releases."))
                .setPositiveButton("تنزيل التحديث", (d, w) -> downloadUpdate(url, name))
                .show();
    }

    private void downloadUpdate(String url, String name) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Adam World - تحديث");
            request.setDescription("جارٍ تنزيل أحدث إصدار");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "adam-world-update.apk");
            request.setMimeType("application/vnd.android.package-archive");
            updateDownloadId = downloadManager.enqueue(request);
            notifyPage("بدأ تنزيل التحديث. ستظهر رسالة عند اكتماله.");
        } catch (Exception e) {
            notifyPage("تعذر بدء تنزيل التحديث.");
        }
    }

    private void installDownloadedUpdate() {
        try {
            File apk = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "adam-world-update.apk");
            if (!apk.exists()) throw new Exception("APK missing");
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this)
                        .setTitle("السماح بتثبيت التحديث")
                        .setMessage("اسمح لـ Adam World بتثبيت التطبيقات من هذا المصدر، ثم أعد فتح ملف التحديث.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("الإعدادات", (d, w) ->
                                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName()))))
                        .show();
                return;
            }
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(contentUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
            notifyPage("تم تنزيل التحديث. أكّد التثبيت في نافذة Android.");
        } catch (Exception e) {
            notifyPage("تعذر فتح مثبت Android. يمكنك إعادة تنزيل التحديث.");
        }
    }

    private void notifyPage(String message) {
        if (webView != null) {
            String escaped = JSONObject.quote(message);
            webView.post(() -> webView.evaluateJavascript("if(window.onUpdateStatus)onUpdateStatus(" + escaped + ")", null));
        }
    }

    private void fetchLocation() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            }
            if (best != null) { sendLocation(best); return; }
            String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            lm.requestSingleUpdate(provider, new LocationListener() {
                @Override public void onLocationChanged(Location location) { sendLocation(location); }
                @Override public void onStatusChanged(String p, int st, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            }, null);
        } catch (SecurityException | IllegalArgumentException e) {
            webView.evaluateJavascript("onLocationError()", null);
        }
    }

    private void sendLocation(Location l) {
        webView.evaluateJavascript("onLocation(" + l.getLatitude() + "," + l.getLongitude() + ")", null);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_LOC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) fetchLocation();
        else webView.evaluateJavascript("onLocationError()", null);
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        executor.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
