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
import android.view.Window;
import android.util.DisplayMetrics;
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
    private static final int PICK = 77, AVATAR = 78, ICON = 79, IMG = 80;
    private static final long MAX_UPLOAD = 52428800L;
    private String base = "", anon = "";

    private String palette = "{}";
    private Uri pickedUri;
    private String pickedName = "", pickedMime = "application/octet-stream";
    private long pickedSize = -1;
    private String imgTag = "";
    private final java.util.Map<String, Object[]> imgs = new java.util.HashMap<>();
    private Uri iconUri;
    private String iconMime = "image/png", iconName = "icon.png";
    private long iconSize = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        palette = makePalette(night);
        web = new WebView(this);
        web.setBackgroundColor(night ? 0xFF141218 : 0xFFFEF7FF);
        setContentView(web);
        edgeToEdge();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // настройки вида хранятся здесь
        // Любые переходы наружу блокируем: мост доступен только нашей локальной странице.
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.addJavascriptInterface(new Bridge(), "Store");
        web.loadUrl("file:///android_asset/index.html");

        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(done, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(done, f);
    }

    private float insTop, insBottom;

    /** Приложение рисуется под системными панелями: сверху и снизу без белых полос. */
    private void edgeToEdge() {
        Window w = getWindow();
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        if (Build.VERSION.SDK_INT >= 28) w.getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.getDecorView().setOnApplyWindowInsetsListener((v, ins) -> {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int bottom = ins.getSystemWindowInsetBottom();
            boolean keyboard = bottom > dm.heightPixels * 0.2f;
            // Клавиатура: сжимаем окно страницы, чтобы поле ввода не пряталось под ней.
            web.setPadding(0, 0, 0, keyboard ? bottom : 0);
            insTop = ins.getSystemWindowInsetTop() / dm.density;
            insBottom = keyboard ? 0 : bottom / dm.density;
            pushInsets();
            return ins.consumeSystemWindowInsets();
        });
    }

    private void pushInsets() {
        if (web != null) web.evaluateJavascript("window.setIns&&setIns(" + insTop + "," + insBottom + ")", null);
    }

    /** Значок: срезаем ровные поля (прозрачные или одного цвета), центрируем и делаем квадрат 256x256. */
    private File squareIcon(Uri u) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1024) sample *= 2;
            o = new android.graphics.BitmapFactory.Options(); o.inSampleSize = sample;
            android.graphics.Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(u)) { b = android.graphics.BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight(), bg = b.getPixel(0, 0);
            int top = 0, bot = h - 1, left = 0, right = w - 1;
            while (top < bot && rowSame(b, top, bg)) top++;
            while (bot > top && rowSame(b, bot, bg)) bot--;
            while (left < right && colSame(b, left, bg, top, bot)) left++;
            while (right > left && colSame(b, right, bg, top, bot)) right--;
            int cw = right - left + 1, ch = bot - top + 1;
            if (cw < w * 0.2f || ch < h * 0.2f) { left = 0; top = 0; cw = w; ch = h; }
            int side = (int) (Math.max(cw, ch) * 1.08f);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            if (Color.alpha(bg) > 200) cv.drawColor(bg);
            float k = 256f / side;
            float dw = cw * k, dh = ch * k;
            cv.drawBitmap(b, new android.graphics.Rect(left, top, left + cw, top + ch),
                    new android.graphics.RectF((256 - dw) / 2, (256 - dh) / 2, (256 + dw) / 2, (256 + dh) / 2),
                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            File f = new File(getCacheDir(), "icon-square.png");
            try (OutputStream os = new FileOutputStream(f)) { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os); }
            return f;
        } catch (Exception e) { return null; }
    }

    private static boolean near(int a, int b) {
        if (Color.alpha(a) < 24 && Color.alpha(b) < 24) return true;
        return Math.abs(Color.alpha(a) - Color.alpha(b)) < 24 && Math.abs(Color.red(a) - Color.red(b)) < 24
                && Math.abs(Color.green(a) - Color.green(b)) < 24 && Math.abs(Color.blue(a) - Color.blue(b)) < 24;
    }
    private static boolean rowSame(android.graphics.Bitmap b, int y, int bg) {
        for (int x = 0; x < b.getWidth(); x += 2) if (!near(b.getPixel(x, y), bg)) return false;
        return true;
    }
    private static boolean colSame(android.graphics.Bitmap b, int x, int bg, int y0, int y1) {
        for (int y = y0; y <= y1; y += 2) if (!near(b.getPixel(x, y), bg)) return false;
        return true;
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

    private String lastUrl, dlKey;
    private volatile boolean dlBusy;

    /** Один человек = одно скачивание: сервер считает по паре (запись, пользователь). */
    private void countDl(String id) {
        try { call("rpc/count_download", new JSONObject().put("p_id", id).toString()); } catch (Exception ignored) { }
    }

    /** Раз в 0,4 с шлёт странице процент загрузки; в конце состояние done/fail/cancel. */
    private void watchDownload(final long id, final String key) {
        new Thread(() -> {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            String end = "cancel";
            try {
                while (true) {
                    int st = -1, pct = -1;
                    try (Cursor q = dm.query(new DownloadManager.Query().setFilterById(id))) {
                        if (q == null || !q.moveToFirst()) break;
                        st = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long got = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long all = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (all > 0) pct = (int) (got * 100 / all);
                    }
                    if (st == DownloadManager.STATUS_SUCCESSFUL) { end = "done"; break; }
                    if (st == DownloadManager.STATUS_FAILED) { end = "fail"; break; }
                    toJs("onDl(" + JSONObject.quote(key) + "," + pct + ",'" + (st == DownloadManager.STATUS_RUNNING ? "run" : "wait") + "')");
                    Thread.sleep(400);
                }
            } catch (Exception ignored) { }
            dlBusy = false;
            if (end.equals("done")) countDl(key);
            toJs("onDl(" + JSONObject.quote(key) + ",100,'" + end + "')");
        }).start();
    }

    private final BroadcastReceiver done = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return;
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            try (Cursor q = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (q != null && q.moveToFirst() && q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) {
                    int why = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    Toast.makeText(MainActivity.this, "Не скачалось (код " + why + "), открываю ссылку в браузере", Toast.LENGTH_LONG).show();
                    if (lastUrl != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(lastUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
            }
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
            Toast.makeText(this, "Minecraft Bedrock не установлен. Файл в Загрузках: " + dlFile.getName(), Toast.LENGTH_LONG).show();
        }
    }

    // ---- аккаунты и файлы: Supabase (Auth + REST + Storage) ----

    private SharedPreferences prefs() { return getSharedPreferences("hs", MODE_PRIVATE); }
    private boolean loggedIn() { return !prefs().getString("refresh", "").isEmpty(); }
    private String uid() { return prefs().getString("uid", ""); }

    @Override
    protected void onActivityResult(int rq, int rc, Intent d) {
        super.onActivityResult(rq, rc, d);
        if ((rq != PICK && rq != AVATAR && rq != ICON && rq != IMG) || rc != RESULT_OK || d == null || d.getData() == null) return;
        Uri u = d.getData();
        String mime = getContentResolver().getType(u), name = "file";
        long size = -1;
        if (mime == null) mime = "application/octet-stream";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), z = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) name = c.getString(n);
                if (z >= 0 && !c.isNull(z)) size = c.getLong(z);
            }
        }
        if (rq == PICK) {
            pickedUri = u; pickedMime = mime; pickedName = name; pickedSize = size;
            toJs("onPicked(" + JSONObject.quote(name) + "," + Math.max(size, 0) + ")");
            if (name.toLowerCase(Locale.ROOT).endsWith(".apk") && size > 0 && size <= MAX_UPLOAD) readApk(u);
            return;
        }
        if (rq == IMG) {
            imgs.put(imgTag, new Object[]{u, mime, name, size});
            toJs("onImgPicked(" + JSONObject.quote(imgTag) + "," + JSONObject.quote(name) + ")");
            return;
        }
        if (rq == ICON) {
            iconUri = u; iconMime = mime; iconName = name; iconSize = size;
            final Uri iu = u; final String inm = name;
            new Thread(() -> {
                File sq = squareIcon(iu);
                if (sq != null) { iconUri = Uri.fromFile(sq); iconMime = "image/png"; iconName = "icon.png"; iconSize = sq.length(); }
                toJs("onIconPicked(" + JSONObject.quote(inm) + ")");
            }).start();
            return;
        }
        final Uri au = u; final String am = mime, an = name; final long as = size;
        new Thread(() -> {
            try {
                String url = putFile(au, am, an, as, "avatar");
                call("rpc/update_profile", new JSONObject().put("p_name", prefs().getString("name", ""))
                        .put("p_bio", prefs().getString("bio", "")).put("p_avatar", url).toString());
                loadProfile();
                toJs("onSaved(true,'')");
            } catch (Exception e) { toJs("onSaved(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")"); }
        }).start();
    }

    /** Один запрос к серверу. Возвращает {код, тело}. Токен обновляется сам. */
    private String[] http(String method, String url, String body, String ctype, boolean authed) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("apikey", anon);
        c.setRequestProperty("Authorization", "Bearer " + (authed ? access() : anon));
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ctype);
            if (method.equals("POST")) c.setRequestProperty("Prefer", "return=minimal");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        return new String[]{String.valueOf(code), in == null ? "" : readAll(in)};
    }

    private String call(String path, String body) throws Exception {
        String[] r = http(body == null ? "GET" : "POST", base + "/rest/v1/" + path, body, "application/json", true);
        if (!r[0].startsWith("2")) throw new Exception(errText(r));
        return r[1];
    }

    private String errText(String[] r) {
        if (r[0].equals("401")) return "Нужно войти заново";
        try {
            JSONObject j = new JSONObject(r[1]);
            String m = j.optString("message", j.optString("msg", j.optString("error_description", "")));
            if (m.contains("row-level security")) return "Нет прав на это действие";
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) { }
        return "Ошибка сервера " + r[0];
    }

    /** Действующий токен доступа; если истекает, обновляем по refresh_token. */
    private synchronized String access() throws Exception {
        SharedPreferences p = prefs();
        if (p.getLong("exp", 0) - System.currentTimeMillis() / 1000 > 60) return p.getString("access", "");
        String[] r = http("POST", base + "/auth/v1/token?grant_type=refresh_token",
            new JSONObject().put("refresh_token", p.getString("refresh", "")).toString(), "application/json", false);
        if (!r[0].equals("200")) { p.edit().remove("refresh").remove("access").apply(); throw new Exception("Нужно войти заново"); }
        saveSession(new JSONObject(r[1]));
        return prefs().getString("access", "");
    }

    private void saveSession(JSONObject j) throws Exception {
        prefs().edit().putString("access", j.getString("access_token")).putString("refresh", j.getString("refresh_token"))
            .putLong("exp", System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600))
            .putString("uid", j.getJSONObject("user").getString("id")).apply();
    }

    private void loadProfile() throws Exception {
        JSONArray a = new JSONArray(call("profiles?id=eq." + uid() + "&select=username,name,bio,avatar_url,badge,role,accent", null));
        if (a.length() == 0) throw new Exception("Профиль не найден");
        JSONObject p = a.getJSONObject(0);
        prefs().edit().putString("username", p.getString("username")).putString("name", p.optString("name"))
            .putString("bio", p.optString("bio")).putString("accent", p.optString("accent")).putString("avatar", p.optString("avatar_url"))
            .putString("role", p.optString("role")).apply();
    }

    private static String slug(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return r.isEmpty() ? "file" : r;
    }

    private static String human(long b) {
        if (b >= 1048576) return String.format(Locale.US, b >= 10485760 ? "%.0f МБ" : "%.1f МБ", b / 1048576.0);
        return Math.max(1, b / 1024) + " КБ";
    }

    /** Достаёт из apk название, версию и значок: форма заполняется сама. */
    private void readApk(final Uri u) {
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "pick.apk");
                try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                android.content.pm.PackageManager pm = getPackageManager();
                android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null || pi.applicationInfo == null) return;
                android.content.pm.ApplicationInfo ai = pi.applicationInfo;
                ai.sourceDir = f.getAbsolutePath(); ai.publicSourceDir = f.getAbsolutePath();
                String label = String.valueOf(ai.loadLabel(pm));
                android.graphics.drawable.Drawable d = ai.loadIcon(pm);
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bm);
                d.setBounds(0, 0, 256, 256); d.draw(cv);
                File ic = new File(getCacheDir(), "icon-auto.png");
                try (OutputStream out = new FileOutputStream(ic)) { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
                iconUri = Uri.fromFile(ic); iconMime = "image/png"; iconName = "icon.png"; iconSize = ic.length();
                toJs("onApkMeta(" + new JSONObject().put("label", label).put("version", pi.versionName == null ? "" : pi.versionName).toString() + ")");
            } catch (Exception ignored) { }
        }).start();
    }

    /** Заливает выбранный файл в хранилище и возвращает публичную ссылку. */
    private String putFile(Uri uri, String mime, String name, long size, String kind) throws Exception {
        if (size > MAX_UPLOAD) throw new Exception("Файл больше 50 МБ. Вставь ссылку вместо файла");
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "") : "";
        String path = uid() + "/" + (kind.equals("file") ? "" : kind + "-") + slug(name.replaceAll("\\.[^.]*$", ""))
                + "-" + Long.toString(System.currentTimeMillis() / 1000, 36) + ext;
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/storage/v1/object/files/" + path).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(20000); c.setReadTimeout(120000);
        if (size > 0) c.setFixedLengthStreamingMode(size); else c.setChunkedStreamingMode(0);
        c.setRequestProperty("apikey", anon);
        c.setRequestProperty("Authorization", "Bearer " + access());
        c.setRequestProperty("Content-Type", mime);
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = c.getOutputStream()) {
            byte[] buf = new byte[65536]; int n, last = -1; long sent = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n); sent += n;
                int p = size > 0 ? (int) (sent * 100 / size) : 0;
                if (kind.equals("file") && p != last) { last = p; toJs("onUploadProgress(" + p + ")"); }
            }
        }
        int code = c.getResponseCode();
        String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code >= 300) throw new Exception(errText(new String[]{String.valueOf(code), resp}));
        return base + "/storage/v1/object/public/files/" + path;
    }

    private void dropFile(String url) {
        try {
            String pre = base + "/storage/v1/object/public/files/";
            if (url != null && url.startsWith(pre)) http("DELETE", base + "/storage/v1/object/files/" + url.substring(pre.length()), null, null, true);
        } catch (Exception ignored) { }
    }

    private void doUpload(JSONObject f) throws Exception {
        final String editId = f.optString("id");
        String icon = f.optString("icon"), banner = f.optString("banner");
        if (f.optBoolean("newBanner") && imgs.get("ibanner") != null) {
            Object[] g = imgs.get("ibanner");
            String oldB = banner;
            banner = putFile((Uri) g[0], (String) g[1], (String) g[2], (Long) g[3], "banner");
            if (!oldB.isEmpty()) dropFile(oldB);
        }
        if (f.optBoolean("newIcon") && iconUri != null) {
            String oldIcon = icon;
            icon = putFile(iconUri, iconMime, iconName, iconSize, "icon");
            if (!oldIcon.isEmpty()) dropFile(oldIcon);
        }
        String url = f.optString("file"), size = f.optString("size"), link = f.optString("link");
        String oldUrl = null;
        if (!link.isEmpty()) {
            if (!link.startsWith("https://")) throw new Exception("Ссылка должна начинаться с https://");
            oldUrl = url; url = (f.optBoolean("site") ? "site:" : "") + link; size = "";
        } else if (f.optBoolean("newFile")) {
            if (pickedUri == null) throw new Exception("Файл не выбран");
            oldUrl = url;
            url = putFile(pickedUri, pickedMime, pickedName, pickedSize, "file");
            size = human(Math.max(pickedSize, 0));
        }
        if (url.isEmpty()) throw new Exception("Нет файла");
        toJs("onUploadStage('Сохраняю')");
        JSONArray tags = new JSONArray();
        for (String t : f.optString("tags").split(",")) if (!t.trim().isEmpty()) tags.put(t.trim());
        if (editId.isEmpty()) {
            call("items", new JSONObject().put("name", f.getString("name")).put("version", f.optString("version"))
                .put("description", f.optString("desc")).put("tags", tags).put("type", f.optString("type"))
                .put("file_url", url).put("size", size).put("icon_url", icon).put("banner_url", banner).toString());
        } else {
            call("rpc/update_item", new JSONObject().put("p_id", editId).put("p_name", f.getString("name"))
                .put("p_version", f.optString("version")).put("p_desc", f.optString("desc")).put("p_tags", tags)
                .put("p_type", f.optString("type")).put("p_file", url).put("p_size", size).put("p_icon", icon).put("p_banner", banner).toString());
            if (oldUrl != null && !oldUrl.equals(url)) dropFile(oldUrl);
        }
    }

    private static String meta(String html, String prop) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<meta[^>]+(?:property|name)=[\"']" + prop + "[\"'][^>]*content=[\"']([^\"']*)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1).trim();
        m = java.util.regex.Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']" + prop + "[\"']", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1).trim() : "";
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
        public void init(String url, String key) { base = url.replaceAll("/+$", ""); anon = key; }

        @JavascriptInterface
        public String me() {
            try {
                SharedPreferences p = prefs();
                return new JSONObject().put("in", loggedIn()).put("username", p.getString("username", ""))
                    .put("name", p.getString("name", "")).put("bio", p.getString("bio", ""))
                    .put("accent", p.getString("accent", "")).put("avatar", p.getString("avatar", "")).put("admin", p.getString("role", "").equals("admin")).put("dev", p.getString("role", "").matches("admin|dev")).toString();
            } catch (Exception e) { return "{}"; }
        }

        /** Вход и регистрация по нику и паролю. Код приглашения проверяет сервер. */
        @JavascriptInterface
        public void auth(final boolean signup, final String user, final String pass, final String invite) {
            new Thread(() -> {
                String err = null;
                try {
                    String u = user.trim().toLowerCase(Locale.ROOT);
                    if (!u.matches("[a-z0-9_]{3,20}")) throw new Exception("Ник: 3-20 символов, латиница, цифры и _");
                    if (pass.length() < 6) throw new Exception("Пароль не короче 6 символов");
                    String email = u + "@hangar.user";
                    if (signup) {
                        String[] r = http("POST", base + "/auth/v1/signup", new JSONObject().put("email", email).put("password", pass)
                            .put("data", new JSONObject().put("username", u).put("invite", invite.trim())).toString(), "application/json", false);
                        if (!r[0].startsWith("2")) {
                            String m = errText(r);
                            if (m.contains("Database error")) m = "Неверный код приглашения или такой ник уже есть";
                            else if (m.contains("already")) m = "Такой ник уже есть";
                            throw new Exception(m);
                        }
                    }
                    String[] t = http("POST", base + "/auth/v1/token?grant_type=password",
                        new JSONObject().put("email", email).put("password", pass).toString(), "application/json", false);
                    if (!t[0].equals("200")) throw new Exception(t[0].equals("400") ? "Неверный ник или пароль" : errText(t));
                    saveSession(new JSONObject(t[1]));
                    loadProfile();
                } catch (java.io.IOException e) { err = "Нет связи с сервером";
                } catch (Exception e) { err = String.valueOf(e.getMessage()); }
                toJs("onAuth(" + (err == null) + "," + JSONObject.quote(err == null ? "" : err) + ")");
            }).start();
        }

        @JavascriptInterface
        public void logout() { prefs().edit().clear().apply(); }

        /** Каталог и профили с сервера; без сети показываем сохранённую копию. */
        @JavascriptInterface
        public void loadCatalog() {
            new Thread(() -> {
                File cache = new File(getFilesDir(), "catalog.json");
                String text; boolean cached = false;
                try {
                    if (!loggedIn()) { toJs("onNeedLogin()"); return; }
                    JSONArray pr = new JSONArray(call("profiles?select=id,username,name,bio,avatar_url,badge,accent,role,banner_url", null));
                    JSONArray it = new JSONArray(call("items?select=*&order=created_at.desc", null));
                    JSONObject authors = new JSONObject(), ids = new JSONObject();
                    for (int i = 0; i < pr.length(); i++) {
                        JSONObject p = pr.getJSONObject(i);
                        ids.put(p.getString("id"), p.getString("username"));
                        authors.put(p.getString("username"), new JSONObject().put("name", p.optString("name"))
                            .put("bio", p.optString("bio")).put("avatar", p.optString("avatar_url")).put("badge", p.optString("badge")).put("accent", p.optString("accent")).put("role", p.optString("role")).put("banner", p.optString("banner_url")));
                    }
                    JSONArray apps = new JSONArray();
                    for (int i = 0; i < it.length(); i++) {
                        JSONObject o = it.getJSONObject(i);
                        apps.put(new JSONObject().put("id", o.getString("id")).put("name", o.optString("name"))
                            .put("author", ids.optString(o.optString("author"), "")).put("type", o.optString("type"))
                            .put("version", o.optString("version")).put("size", o.optString("size")).put("dl", o.optInt("dl", 0))
                            .put("desc", o.optString("description")).put("tags", o.optJSONArray("tags") == null ? new JSONArray() : o.getJSONArray("tags"))
                            .put("file", o.optString("file_url")).put("icon", o.optString("icon_url")).put("banner", o.optString("banner_url")));
                    }
                    text = new JSONObject().put("authors", authors).put("apps", apps).toString();
                    try (FileOutputStream fo = new FileOutputStream(cache)) { fo.write(text.getBytes("UTF-8")); }
                } catch (Exception e) {
                    if (String.valueOf(e.getMessage()).contains("войти заново")) { toJs("onNeedLogin()"); return; }
                    try (FileInputStream fi = new FileInputStream(cache)) { text = readAll(fi); cached = true; }
                    catch (Exception e2) { text = null; }
                }
                if (text == null) toJs("onCatalogError()");
                else toJs("onCatalog(" + JSONObject.quote(text) + "," + cached + ")");
            }).start();
        }

        @JavascriptInterface
        public void saveProfile(final String json) {
            new Thread(() -> {
                try {
                    JSONObject p = new JSONObject(json);
                    String nm = p.optString("name").trim();
                    call("rpc/update_profile", new JSONObject().put("p_name", nm.isEmpty() ? prefs().getString("username", "") : nm)
                        .put("p_bio", p.optString("bio")).put("p_accent", p.optString("accent")).put("p_banner", p.has("banner") ? p.optString("banner") : null).toString());
                    loadProfile();
                    toJs("onSaved(true,'')");
                } catch (Exception e) { toJs("onSaved(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")"); }
            }).start();
        }

        @JavascriptInterface
        public void pickAvatar() {
            runOnUiThread(() -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), AVATAR));
        }

        /** Значки выдаёт только админ: остальным сервер не даст. */
        @JavascriptInterface
        public void setBadge(final String who, final String badge) {
            new Thread(() -> {
                try {
                    call("rpc/set_badge", new JSONObject().put("p_username", who).put("p_badge", badge).toString());
                    toJs("onSaved(true,'')");
                } catch (Exception e) { toJs("onSaved(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")"); }
            }).start();
        }

        /** Роли выдаёт только админ: остальным сервер не даст. */
        @JavascriptInterface
        public void setRole(final String who, final String role) {
            new Thread(() -> {
                try {
                    call("rpc/set_role", new JSONObject().put("p_username", who).put("p_role", role).toString());
                    toJs("onSaved(true,'')");
                } catch (Exception e) { toJs("onSaved(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")"); }
            }).start();
        }

        /** Заявка на роль разработчика: пользователь шлёт, админ видит список. */
        @JavascriptInterface
        public void sendRequest(final String text) {
            new Thread(() -> {
                try {
                    call("rpc/send_request", new JSONObject().put("p_text", text).toString());
                    toJs("onReqSent(true,'')");
                } catch (Exception e) { toJs("onReqSent(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")"); }
            }).start();
        }

        @JavascriptInterface
        public void loadRequests() {
            new Thread(() -> {
                String r;
                try { r = new JSONArray(call("dev_requests?select=text,profiles(username,name)", null)).toString(); }
                catch (Exception e) { r = "[]"; }
                toJs("onRequests(" + JSONObject.quote(r) + ")");
            }).start();
        }

        @JavascriptInterface
        public void decideRequest(final String who, final boolean ok) {
            new Thread(() -> {
                try {
                    call("rpc/decide_request", new JSONObject().put("p_username", who).put("p_ok", ok).toString());
                    toJs("onReqSent(true,'')");
                } catch (Exception e) { toJs("onReqSent(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")"); }
            }).start();
        }

        /** Универсальный запрос к базе: GET, POST или DELETE. Ответ приходит в onRest(id, ok, текст). */
        @JavascriptInterface
        public void rest(final String id, final String method, final String path, final String body) {
            new Thread(() -> {
                try {
                    if (!method.matches("GET|POST|DELETE")) throw new Exception("Метод не поддерживается");
                    String[] r = http(method, base + "/rest/v1/" + path, body.isEmpty() ? null : body, "application/json", true);
                    if (!r[0].startsWith("2")) throw new Exception(errText(r));
                    toJs("onRest(" + JSONObject.quote(id) + ",true," + JSONObject.quote(r[1]) + ")");
                } catch (Exception e) {
                    toJs("onRest(" + JSONObject.quote(id) + ",false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void pickImage(final String tag) {
            imgTag = tag;
            runOnUiThread(() -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), IMG));
        }

        /** Заливает выбранную по тегу картинку и отвечает в onRest(id, ok, ссылка). */
        @JavascriptInterface
        public void uploadImage(final String tag, final String id) {
            new Thread(() -> {
                try {
                    Object[] g = imgs.get(tag);
                    if (g == null) throw new Exception("Картинка не выбрана");
                    String url = putFile((Uri) g[0], (String) g[1], (String) g[2], (Long) g[3], "img");
                    toJs("onRest(" + JSONObject.quote(id) + ",true," + JSONObject.quote(url) + ")");
                } catch (Exception e) {
                    toJs("onRest(" + JSONObject.quote(id) + ",false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            }).start();
        }

        /** Есть ли новая версия самого магазина: смотрим последний релиз на GitHub. */
        @JavascriptInterface
        public void checkUpdate() {
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/Airi-Drun-System/HopStore/releases/latest").openConnection();
                    c.setConnectTimeout(10000); c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "HangarVault");
                    if (c.getResponseCode() != 200) return;
                    JSONObject j = new JSONObject(readAll(c.getInputStream()));
                    int remote = Integer.parseInt(j.getString("tag_name").replaceAll("[^0-9]", ""));
                    int mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                    JSONArray as = j.getJSONArray("assets");
                    if (remote > mine && as.length() > 0)
                        toJs("onUpdate(" + remote + "," + JSONObject.quote(as.getJSONObject(0).getString("browser_download_url")) + ")");
                } catch (Exception ignored) { }
            }).start();
        }

        /** Название и картинка со страницы сайта (og:image или значок). Ответ в onMeta(json). */
        @JavascriptInterface
        public void fetchMeta(final String url) {
            new Thread(() -> {
                JSONObject o = new JSONObject();
                try {
                    if (!url.startsWith("https://")) return;
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(10000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) HangarVault");
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) > 0 && b.size() < 300000) b.write(buf, 0, n);
                    }
                    String h = b.toString("UTF-8");
                    String img = meta(h, "og:image"), title = meta(h, "og:title");
                    if (img.isEmpty()) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<link[^>]+rel=[\"'](?:shortcut )?icon[\"'][^>]*href=[\"']([^\"']+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(h);
                        if (m.find()) img = m.group(1);
                    }
                    if (title.isEmpty()) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<title[^>]*>([^<]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(h);
                        if (m.find()) title = m.group(1).trim();
                    }
                    if (!img.isEmpty()) o.put("image", new URL(new URL(url), img).toString());
                    o.put("title", title);
                } catch (Exception ignored) { }
                toJs("onMeta(" + o.toString() + ")");
            }).start();
        }

        @JavascriptInterface
        public void pickIcon() {
            runOnUiThread(() -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), ICON));
        }

        @JavascriptInterface
        public void pick() {
            runOnUiThread(() -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK));
        }

        @JavascriptInterface
        public void upload(final String json) {
            runOnUiThread(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            new Thread(() -> {
                String err = null;
                try { doUpload(new JSONObject(json)); } catch (Exception e) { err = String.valueOf(e.getMessage()); }
                runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
                toJs(err == null ? "onUploadDone(true,'')" : "onUploadDone(false," + JSONObject.quote(err) + ")");
            }).start();
        }

        /** Убрать запись: автор или админ (сервер проверяет сам). */
        @JavascriptInterface
        public void remove(final String id, final String file) {
            new Thread(() -> {
                try {
                    String[] r = http("DELETE", base + "/rest/v1/items?id=eq." + URLEncoder.encode(id, "UTF-8"), null, null, true);
                    if (!r[0].startsWith("2")) throw new Exception(errText(r));
                    dropFile(file);
                    toJs("onRemoved(true,'')");
                } catch (Exception e) { toJs("onRemoved(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")"); }
            }).start();
        }

        /** Цвет системных панелей под текущую тему страницы. */
        @JavascriptInterface
        public void bars(final String hex, final boolean dark) {
            runOnUiThread(() -> {
                int c = Color.parseColor(hex);
                web.setBackgroundColor(c);
                int fl = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
                getWindow().getDecorView().setSystemUiVisibility(fl);
            });
        }

        /** APK ставит, миры и паки Minecraft открывает в игре, остальное кладёт в Загрузки. */
        @JavascriptInterface
        public void get(String rawUrl, final String name) {
            final String url = rawUrl == null ? null : rawUrl.trim();
            if (url != null && !url.startsWith("site:") && dlBusy) { toast("Уже скачивается, подожди"); return; }
            lastUrl = url;
            if (url != null && url.startsWith("site:https://")) {
                runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.substring(5)))));
                new Thread(() -> countDl(name)).start();
                return;
            }
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
                dlBusy = true; dlKey = name;
                toJs("onDl(" + JSONObject.quote(name) + ",0,'run')");
                watchDownload(downloadId, name);
            });
        }

        @JavascriptInterface
        public void cancel() {
            runOnUiThread(() -> ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).remove(downloadId));
        }

        private void toast(String t) { runOnUiThread(() -> Toast.makeText(MainActivity.this, t, Toast.LENGTH_SHORT).show()); }
    }
}
