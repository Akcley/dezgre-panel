package com.dezgre.mobile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ApiClient {
    public static final String BASE_URL = "https://api.dezgre.com/v1";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    public interface Callback {
        void onSuccess(JSONObject json);
        void onError(ApiException error);
    }

    public static final class ApiException extends Exception {
        public final int status;
        public final String code;

        public ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code == null ? "API_ERROR" : code;
        }
    }

    public void get(final String path, final String bearer, final Callback callback) {
        request("GET", path, bearer, null, callback);
    }

    public void post(final String path, final String bearer, final JSONObject body, final Callback callback) {
        request("POST", path, bearer, body, callback);
    }

    public void put(final String path, final String bearer, final JSONObject body, final Callback callback) {
        request("PUT", path, bearer, body, callback);
    }

    public void delete(final String path, final String bearer, final Callback callback) {
        request("DELETE", path, bearer, null, callback);
    }

    private void request(
            final String method,
            final String path,
            final String bearer,
            final JSONObject body,
            final Callback callback
    ) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(BASE_URL + (path.startsWith("/") ? path : "/" + path));
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod(method);
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent", "DEZGRE-Mobile Android");
                    if (bearer != null && !bearer.trim().isEmpty()) {
                        connection.setRequestProperty("Authorization", "Bearer " + bearer.trim());
                    }
                    if (body != null) {
                        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        connection.setFixedLengthStreamingMode(bytes.length);
                        OutputStream output = connection.getOutputStream();
                        output.write(bytes);
                        output.flush();
                        output.close();
                    }

                    int status = connection.getResponseCode();
                    InputStream stream = status >= 200 && status < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream();
                    String raw = readAll(stream);
                    JSONObject json = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
                    if (status >= 200 && status < 300) {
                        callback.onSuccess(json);
                    } else {
                        String message = json.optString("error", "La API respondió con un error.");
                        String code = json.optString("code", "HTTP_" + status);
                        callback.onError(new ApiException(status, code, message));
                    }
                } catch (JSONException e) {
                    callback.onError(new ApiException(502, "INVALID_API_RESPONSE", "La API devolvió una respuesta no válida."));
                } catch (Exception e) {
                    callback.onError(new ApiException(0, "NETWORK_ERROR", "No se pudo conectar con API DEZGRE V1."));
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        });
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) result.append(line);
        reader.close();
        return result.toString();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
