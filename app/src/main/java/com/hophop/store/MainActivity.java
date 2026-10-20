package com.hophop.store;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/** Hangar Vault: локальная страница в WebView + мост для каталога, скачивания, установки и Minecraft. */
public class MainActivity extends Activity {
    private static final int OTHER = 0, APK = 1, MC = 2;
    private static final String MC_PKG = "com.mojang.minecraftpe";

    private WebView web;
    private long downloadId = -1;
    private File dlFile;
    private int mode = OTHER;
    private boolean waitingPerm;
    private String palette = "{}";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        palette = makePalette(night);
        web = new WebView(this);
        web.setBackgroundColor(night ? 0xFF141218 : 0xFFFEF7FF);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // настройки вида хранятся здесь
        // Любые переходы наружу блокируем: мост доступен только нашей локальной странице.
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
        });
        web.addJavascriptInterface(new Bridge(), "Store");
        web.loadUrl("file:///android_asset/index.html");

        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(done, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(done, f);
    }

    /** Цвета Material You с обоев (Android 12+) для светлой и тёмной темы. */
    private String makePalette(boolean night) {
        String head = "{\"night\":" + night;
        if (Build.VERSION.SDK_INT < 31) return head + "}";
        int[] light = {
            android.R.color.system_accent1_600, android.R.color.system_accent1_0,
            android.R.color.system_accent1_100, android.R.color.system_accent1_900,
            android.R.color.system_accent2_100, android.R.color.system_accent2_900,
            android.R.color.system_accent3_100, android.R.color.system_accent3_900,
            android.R.color.system_neutral1_10, android.R.color.system_neutral1_900,
            android.R.color.system_neutral2_100, android.R.color.system_neutral2_500,
            android.R.color.system_neutral1_50 };
        int[] dark = {
            android.R.color.system_accent1_200, android.R.color.system_accent1_800,
            android.R.color.system_accent1_700, android.R.color.system_accent1_100,
            android.R.color.system_accent2_700, android.R.color.system_accent2_100,
            android.R.color.system_accent3_700, android.R.color.system_accent3_100,
            android.R.color.system_neutral1_900, android.R.color.system_neutral1_100,
            android.R.color.system_neutral2_700, android.R.color.system_neutral2_400,
            android.R.color.system_neutral1_800 };
        String[] keys = {"primary", "onPrimary", "pc", "onPc", "sc", "onSc", "tc", "onTc",
                "surface", "onSurface", "sv", "outline", "card"};
        StringBuilder j = new StringBuilder(head + ",\"sys\":{");
        for (int t = 0; t < 2; t++) {
            int[] ids = t == 0 ? light : dark;
            j.append(t == 0 ? "\"light\":{" : ",\"dark\":{");
            for (int i = 0; i < keys.length; i++) {
                j.append(i > 0 ? "," : "").append('"').append(keys[i]).append("\":\"")
                 .append(String.format("#%06X", 0xFFFFFF & getColor(ids[i]))).append('"');
            }
            j.append('}');
        }
        return j.append("}}").toString();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(done);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Вернулись с экрана «разрешить установку»: продолжаем.
        if (waitingPerm && Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
            waitingPerm = false;
            openInstaller();
        }
    }

    @Override
    public void onBackPressed() {
        // Сначала страница закрывает свои окна, и только потом выходим.
        web.evaluateJavascript("hsBack()", v -> { if (!"true".equals(v)) finish(); });
    }

    private final BroadcastReceiver done = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return;
            if (mode == APK) openInstaller();
            else if (mode == MC) openMinecraft();
            else Toast.makeText(MainActivity.this, "Сохранено в Загрузки", Toast.LENGTH_LONG).show();
        }
    };

    private Intent viewIntent(String mime) {
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", dlFile);
        return new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void openInstaller() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            waitingPerm = true;
            Toast.makeText(this, "Разреши установку из Hangar Vault", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        startActivity(viewIntent("application/vnd.android.package-archive"));
    }

    /** Мир, пак или аддон открываем прямо в Minecraft Bedrock: он сам предложит импорт. */
    private void openMinecraft() {
        try {
            startActivity(viewIntent("application/octet-stream").setPackage(MC_PKG));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Minecraft не найден. Файл сохранён: " + dlFile.getName(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toString("UTF-8");
    }

    private void toJs(final String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    private class Bridge {
        @JavascriptInterface
        public String palette() { return palette; }

        /** Цвет системных панелей под текущую тему страницы. */
        @JavascriptInterface
        public void bars(final String hex, final boolean dark) {
            runOnUiThread(() -> {
                int c = Color.parseColor(hex);
                getWindow().setStatusBarColor(c);
                getWindow().setNavigationBarColor(c);
                web.setBackgroundColor(c);
                int fl = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
                getWindow().getDecorView().setSystemUiVisibility(fl);
            });
        }

        /** Каталог из сети; без сети отдаём последний сохранённый. */
        @JavascriptInterface
        public void loadCatalog(final String url) {
            new Thread(() -> {
                File cache = new File(getFilesDir(), "catalog.json");
                String text; boolean cached = false;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    text = readAll(c.getInputStream());
                    new JSONObject(text); // проверка, что это JSON
                    try (FileOutputStream fo = new FileOutputStream(cache)) { fo.write(text.getBytes("UTF-8")); }
                } catch (Exception e) {
                    try (FileInputStream fi = new FileInputStream(cache)) { text = readAll(fi); cached = true; }
                    catch (Exception e2) { text = null; }
                }
                if (text == null) toJs("onCatalogError()");
                else toJs("onCatalog(" + JSONObject.quote(text) + "," + cached + ")");
            }).start();
        }

        /** APK ставит, миры и паки Minecraft открывает в игре, остальное кладёт в Загрузки. */
        @JavascriptInterface
        public void get(final String url, final String name) {
            if (url == null || !url.startsWith("https://")) { toast("Неверная ссылка"); return; }
            final String path = url.split("[?#]")[0];
            int dot = path.lastIndexOf('.');
            final String ext = dot > path.lastIndexOf('/') ? path.substring(dot).toLowerCase() : "";
            final int m = ext.equals(".apk") ? APK
                    : ext.matches("\\.(mcworld|mcpack|mcaddon|mctemplate)") ? MC : OTHER;
            final String safe = (name + ext).replaceAll("[^A-Za-z0-9._-]", "_");
            runOnUiThread(() -> {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                mode = m;
                if (m == OTHER) {
                    dlFile = null;
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safe);
                } else {
                    dlFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), safe);
                    if (dlFile.exists()) dlFile.delete();
                    r.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, safe);
                }
                downloadId = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            });
        }

        private void toast(String t) { runOnUiThread(() -> Toast.makeText(MainActivity.this, t, Toast.LENGTH_SHORT).show()); }
    }
}
