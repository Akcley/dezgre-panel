package com.dezgre.mobile;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class AvatarLoader {
    private AvatarLoader() {}

    public static void load(final Activity activity, final ImageView target, final View fallback, final String source) {
        if (activity == null || target == null || source == null || source.trim().isEmpty()) return;
        final String value = source.trim();
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = null;
                HttpURLConnection connection = null;
                try {
                    if (value.startsWith("data:image/")) {
                        int comma = value.indexOf(',');
                        if (comma > 0 && comma + 1 < value.length()) {
                            byte[] bytes = Base64.decode(value.substring(comma + 1), Base64.DEFAULT);
                            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        }
                    } else {
                        URL url = new URL(value);
                        if (!"https".equalsIgnoreCase(url.getProtocol())) return;
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setConnectTimeout(10000);
                        connection.setReadTimeout(12000);
                        connection.setUseCaches(true);
                        connection.setInstanceFollowRedirects(true);
                        InputStream input = connection.getInputStream();
                        bitmap = BitmapFactory.decodeStream(input);
                        input.close();
                    }
                } catch (Exception ignored) {
                    bitmap = null;
                } finally {
                    if (connection != null) connection.disconnect();
                }
                final Bitmap result = bitmap;
                if (result == null) return;
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        target.setImageBitmap(result);
                        target.setVisibility(View.VISIBLE);
                        if (fallback != null) fallback.setVisibility(View.GONE);
                    }
                });
            }
        }, "dezgre-avatar-loader").start();
    }
}
