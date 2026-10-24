package com.dezgre.mobile;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.*;

/** Persistent, bounded sound cache. Never downloads on the UI/FCM delivery thread. */
final class NotificationSoundCache {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> failures = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(24), new ThreadPoolExecutor.AbortPolicy());

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static File directory(Context context) { return new File(context.getFilesDir(), "notification_sounds"); }
    static String key(String store, NotificationConfig config) {
        return hash(store + "\n" + config.soundUrl + "\n" + config.soundRevision);
    }
    static Uri cached(Context context, String store, NotificationConfig config) {
        if (!"custom".equals(config.soundMode) || !validUrl(config.soundUrl)) return null;
        String key = key(store, config);
        File file = new File(directory(context), key + ".mp3");
        return file.isFile() && file.length() > 0
                ? Uri.parse("content://" + context.getPackageName() + ".notification-sounds/" + key + ".mp3") : null;
    }
    static boolean validUrl(String value) {
        try {
            URL url = new URL(value);
            // Sound bytes are served by DEZGRE, never a URL supplied by a push sender or an arbitrary LAN host.
            return "https".equals(url.getProtocol()) && "api.dezgre.com".equalsIgnoreCase(url.getHost())
                    && url.getPort() == -1 && url.getUserInfo() == null
                    && url.getPath().startsWith("/v1/notifications/sounds/");
        } catch (Exception ignored) { return false; }
    }
    static void prepare(Context context, String store, NotificationConfig config) {
        Context app = context.getApplicationContext();
        if (!"custom".equals(config.soundMode) || !validUrl(config.soundUrl) || cached(app, store, config) != null) return;
        String key = key(store, config);
        Long failed = failures.get(key);
        if (failed != null && System.currentTimeMillis() - failed < 60000) return;
        if (!pending.add(key)) return;
        try { worker.execute(() -> {
            try { download(app, key, config.soundUrl); failures.remove(key); }
            catch (Exception ignored) {
                if (failures.size() > 100) failures.clear();
                failures.put(key, System.currentTimeMillis());
            } finally { pending.remove(key); }
        }); } catch (RejectedExecutionException ignored) { pending.remove(key); }
    }
    private static void download(Context context, String key, String url) throws Exception {
        File dir = directory(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cache directory");
        long bytes = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) bytes += f.length();
        if (bytes + MAX_BYTES > 100L * 1024 * 1024) throw new IOException("cache limit");
        File temporary = new File(dir, key + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setRequestProperty("Accept", "audio/mpeg");
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_BYTES)
                throw new IOException("sound response");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192]; int count; int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES || android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("sound limit");
                    output.write(buffer, 0, count);
                }
                if (total == 0) throw new IOException("empty sound");
                output.getFD().sync();
            }
            MediaMetadataRetriever media = new MediaMetadataRetriever();
            try {
                media.setDataSource(temporary.getAbsolutePath());
                if (media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) == null)
                    throw new IOException("invalid audio");
            } finally { media.release(); }
            if (!temporary.renameTo(new File(dir, key + ".mp3"))) throw new IOException("cache publish");
        } finally { connection.disconnect(); temporary.delete(); }
    }

    static void acceptPayload(Context context, String store, String event, JSONObject payload) {
        if (!payload.has("soundMode")) return; // Older servers retain existing configuration.
        NotificationConfigStore configs = new NotificationConfigStore(context);
        NotificationConfig previous = configs.get(store, event);
        NotificationConfig config = new NotificationConfig(event, previous.enabled,
                payload.optString("soundMode", "default"), payload.optString("soundName", ""),
                payload.optString("soundUrl", ""), "", payload.optString("soundRevision", "v1"));
        configs.put(store, config);
        prepare(context, store, config);
    }
}
