package com.hophop.store;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
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
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
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
    private static final String REPO = "Airi-Drun-System/HopStore";
    private static final String API = "https://api.github.com/repos/" + REPO;
    private static final int PICK = 77;

    private String palette = "{}";
    private Uri pickedUri;
    private String pickedName = "", pickedMime = "application/octet-stream";
    private long pickedSize = -1;

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

    // ---- режим автора: загрузка файлов в GitHub (Releases + catalog.json) ----

    private SharedPreferences prefs() { return getSharedPreferences("hs", MODE_PRIVATE); }
    private String token() { return prefs().getString("token", ""); }

    @Override
    protected void onActivityResult(int rq, int rc, Intent d) {
        super.onActivityResult(rq, rc, d);
        if (rq != PICK || rc != RESULT_OK || d == null || d.getData() == null) return;
        pickedUri = d.getData();
        pickedMime = getContentResolver().getType(pickedUri);
        if (pickedMime == null) pickedMime = "application/octet-stream";
        pickedName = "file"; pickedSize = -1;
        try (Cursor c = getContentResolver().query(pickedUri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), z = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) pickedName = c.getString(n);
                if (z >= 0 && !c.isNull(z)) pickedSize = c.getLong(z);
            }
        }
        toJs("onPicked(" + JSONObject.quote(pickedName) + "," + Math.max(pickedSize, 0) + ")");
    }

    private String[] api(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        return new String[]{String.valueOf(code), in == null ? "" : readAll(in)};
    }

    /** Добавляет запись в catalog.json (add) и/или убирает запись по id или названию (removeId). */
    private void updateCatalog(JSONObject add, String removeId, String message) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            String[] g = api("GET", API + "/contents/catalog.json?ref=main", null);
            if (!g[0].equals("200")) throw new Exception("Не прочитать каталог: " + g[0]);
            JSONObject meta = new JSONObject(g[1]);
            JSONObject cat = new JSONObject(new String(Base64.decode(meta.getString("content"), Base64.DEFAULT), "UTF-8"));
            JSONArray old = cat.optJSONArray("apps"), neu = new JSONArray();
            if (add != null) neu.put(add);
            for (int i = 0; old != null && i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (removeId != null && (removeId.equals(o.optString("id")) || removeId.equals(o.optString("name")))) continue;
                neu.put(o);
            }
            cat.put("apps", neu);
            JSONObject put = new JSONObject();
            put.put("message", message).put("branch", "main").put("sha", meta.getString("sha"))
               .put("content", Base64.encodeToString(cat.toString(2).replace("\\/", "/").getBytes("UTF-8"), Base64.NO_WRAP));
            String[] p = api("PUT", API + "/contents/catalog.json", put.toString());
            if (p[0].equals("200") || p[0].equals("201")) return;
            if (!p[0].equals("409")) throw new Exception("Не сохранить каталог: " + p[0]);
        }
        throw new Exception("Каталог занят, попробуй ещё раз");
    }

    private static String slug(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return r.isEmpty() ? "file" : r;
    }

    private static String human(long b) {
        if (b >= 1048576) return String.format(Locale.US, b >= 10485760 ? "%.0f МБ" : "%.1f МБ", b / 1048576.0);
        return Math.max(1, b / 1024) + " КБ";
    }

    private void doUpload(JSONObject f) throws Exception {
        String name = f.getString("name");
        int dot = pickedName.lastIndexOf('.');
        String ext = dot > 0 ? pickedName.substring(dot).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "") : "";
        String id = slug(name) + "-" + Long.toString(System.currentTimeMillis() / 1000, 36);

        toJs("onUploadStage('Готовлю релиз')");
        String[] r = api("GET", API + "/releases/tags/files", null);
        if (r[0].equals("404"))
            r = api("POST", API + "/releases", "{\"tag_name\":\"files\",\"name\":\"Files\",\"target_commitish\":\"main\"}");
        if (!r[0].equals("200") && !r[0].equals("201")) throw new Exception("Релиз: " + r[0]);
        long relId = new JSONObject(r[1]).getLong("id");

        HttpURLConnection c = (HttpURLConnection) new URL("https://uploads.github.com/repos/" + REPO + "/releases/" + relId
                + "/assets?name=" + URLEncoder.encode(id + ext, "UTF-8")).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(20000); c.setReadTimeout(120000);
        if (pickedSize > 0) c.setFixedLengthStreamingMode(pickedSize); else c.setChunkedStreamingMode(0);
        c.setRequestProperty("Authorization", "Bearer " + token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("Content-Type", pickedMime);
        try (InputStream in = getContentResolver().openInputStream(pickedUri); OutputStream out = c.getOutputStream()) {
            byte[] buf = new byte[65536]; int n, last = -1; long sent = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n); sent += n;
                int p = pickedSize > 0 ? (int) (sent * 100 / pickedSize) : 0;
                if (p != last) { last = p; toJs("onUploadProgress(" + p + ")"); }
            }
        }
        int code = c.getResponseCode();
        String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code != 201) throw new Exception("Загрузка файла: " + code);
        String url = new JSONObject(resp).getString("browser_download_url");

        toJs("onUploadStage('Обновляю каталог')");
        JSONObject e = new JSONObject();
        e.put("id", id).put("name", name);
        if (!f.optString("author").isEmpty()) e.put("author", f.getString("author"));
        if (!f.optString("type").isEmpty()) e.put("type", f.getString("type"));
        if (!f.optString("version").isEmpty()) e.put("version", f.getString("version"));
        e.put("size", human(Math.max(pickedSize, 0)));
        if (!f.optString("desc").isEmpty()) e.put("desc", f.getString("desc"));
        e.put("file", url);
        updateCatalog(e, null, "Добавлено: " + name);
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

        @JavascriptInterface
        public String owner() { return "{\"has\":" + !token().isEmpty() + "}"; }

        @JavascriptInterface
        public void clearToken() { prefs().edit().remove("token").apply(); }

        /** Сохраняем ключ только если GitHub подтвердил право записи в репозиторий. */
        @JavascriptInterface
        public void setToken(final String t) {
            new Thread(() -> {
                String old = token();
                prefs().edit().putString("token", t.trim()).apply();
                try {
                    String[] r = api("GET", API, null);
                    boolean ok = r[0].equals("200") && new JSONObject(r[1]).optJSONObject("permissions") != null
                            && new JSONObject(r[1]).getJSONObject("permissions").optBoolean("push");
                    // у fine-grained ключей поле permissions бывает пустым: проверяем чтением каталога
                    if (!ok && r[0].equals("200")) ok = api("GET", API + "/contents/catalog.json?ref=main", null)[0].equals("200");
                    if (!ok) { prefs().edit().putString("token", old).apply(); }
                    toJs("onTokenResult(" + ok + "," + JSONObject.quote(ok ? "" : "Ключ не подошёл (код " + r[0] + ")") + ")");
                } catch (Exception e) {
                    prefs().edit().putString("token", old).apply();
                    toJs("onTokenResult(false," + JSONObject.quote("Нет связи с GitHub") + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void pick() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                startActivityForResult(i, PICK);
            });
        }

        @JavascriptInterface
        public void upload(final String json) {
            if (pickedUri == null || token().isEmpty()) { toJs("onUploadDone(false,'Выбери файл')"); return; }
            runOnUiThread(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            new Thread(() -> {
                String err = null;
                try { doUpload(new JSONObject(json)); } catch (Exception e) { err = String.valueOf(e.getMessage()); }
                runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
                toJs(err == null ? "onUploadDone(true,'')" : "onUploadDone(false," + JSONObject.quote(err) + ")");
            }).start();
        }

        @JavascriptInterface
        public void remove(final String id) {
            new Thread(() -> {
                try { updateCatalog(null, id, "Убрано: " + id); toJs("onRemoved(true,'')"); }
                catch (Exception e) { toJs("onRemoved(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")"); }
            }).start();
        }

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
                    HttpURLConnection c = (HttpURLConnection) new URL(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis() / 20000).openConnection();
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
