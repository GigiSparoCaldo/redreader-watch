package org.quantumbadger.redreader.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WatchHelper {
    private static final String PREFS = "watch_redreader_prefs";
    private static final String KEY_TRANSLATE = "translate_it_enabled";
    private static final String KEY_COOKIE = "reddit_session_cookie";
    private static final String KEY_MODHASH = "reddit_modhash";

    private static final LruCache<String, String> TRANS_CACHE = new LruCache<>(400);
    private static final ExecutorService TRANS_POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private WatchHelper() {}

    private static SharedPreferences prefs(@NonNull final Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isTranslateEnabled(@NonNull final Context ctx) {
        return prefs(ctx).getBoolean(KEY_TRANSLATE, true);
    }

    public static boolean toggleTranslate(@NonNull final Context ctx) {
        final boolean next = !isTranslateEnabled(ctx);
        prefs(ctx).edit().putBoolean(KEY_TRANSLATE, next).apply();
        return next;
    }

    @NonNull
    public static String getCookie(@NonNull final Context ctx) {
        return prefs(ctx).getString(KEY_COOKIE, "").trim();
    }

    public static void setCookie(@NonNull final Context ctx, @Nullable final String raw) {
        String c = raw == null ? "" : raw.trim();
        if (!c.isEmpty() && !c.contains("=")) {
            c = "reddit_session=" + c;
        }
        prefs(ctx).edit().putString(KEY_COOKIE, c).apply();
    }

    @NonNull
    public static String getModhash(@NonNull final Context ctx) {
        return prefs(ctx).getString(KEY_MODHASH, "").trim();
    }

    public static void setModhash(@NonNull final Context ctx, @Nullable final String uh) {
        prefs(ctx).edit().putString(KEY_MODHASH, uh == null ? "" : uh.trim()).apply();
    }

    @Nullable
    public static String getCached(@Nullable final String text) {
        if (text == null || text.trim().isEmpty()) return text;
        return TRANS_CACHE.get(text);
    }

    public static void translateAsync(
            @NonNull final String text,
            @NonNull final Consumer<String> onResult) {
        final String clean = text.trim();
        if (clean.length() < 3) return;
        final String cached = TRANS_CACHE.get(clean);
        if (cached != null) {
            if (!cached.equals(clean)) {
                MAIN_HANDLER.post(() -> onResult.consume(cached));
            }
            return;
        }
        TRANS_POOL.execute(() -> {
            final String translated = translateSync(clean);
            if (translated != null && !translated.isEmpty() && !translated.equals(clean)) {
                MAIN_HANDLER.post(() -> onResult.consume(translated));
            }
        });
    }

    @NonNull
    private static String translateSync(@NonNull final String text) {
        final String cached = TRANS_CACHE.get(text);
        if (cached != null) return cached;
        final String trimmed = text.length() > 1400 ? text.substring(0, 1400) : text;
        HttpURLConnection conn = null;
        try {
            final String body = "q=" + URLEncoder.encode(trimmed, "UTF-8");
            final URL u = new URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=it&dt=t");
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    final byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    final JSONArray root = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    if ("it".equalsIgnoreCase(root.optString(2, ""))) {
                        TRANS_CACHE.put(text, text);
                        return text;
                    }
                    final JSONArray segs = root.getJSONArray(0);
                    final StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < segs.length(); i++) {
                        final JSONArray seg = segs.optJSONArray(i);
                        if (seg != null && !seg.isNull(0)) sb.append(seg.getString(0));
                    }
                    final String res = sb.toString().trim();
                    if (!res.isEmpty()) {
                        TRANS_CACHE.put(text, res);
                        return res;
                    }
                }
            }
        } catch (final Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return text;
    }
}
