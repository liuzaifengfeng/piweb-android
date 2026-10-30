package com.piweb.launcher;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 与 policy-server.js（127.0.0.1:30142）通信，读写权限策略。 */
final class PolicyClient {

    interface Callback {
        void onResult(String level, String mode, String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PolicyClient() {
    }

    static void get(final Callback cb) {
        request("GET", null, cb);
    }

    static void set(final String level, final String mode, final Callback cb) {
        try {
            JSONObject o = new JSONObject();
            o.put("level", level);
            o.put("mode", mode);
            request("POST", o.toString(), cb);
        } catch (Throwable e) {
            final String msg = String.valueOf(e.getMessage());
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    cb.onResult(level, mode, msg);
                }
            });
        }
    }

    private static void request(final String method, final String body, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                String level = null, mode = null, error = null;
                try {
                    conn = (HttpURLConnection) new URL(DeploymentConfig.policyUrl() + "/policy").openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    conn.setRequestMethod(method);
                    if (!DeploymentConfig.token().isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + DeploymentConfig.token());
                    if (body != null) {
                        conn.setDoOutput(true);
                        conn.setRequestProperty("content-type", "application/json");
                        OutputStream os = conn.getOutputStream();
                        try {
                            os.write(body.getBytes(StandardCharsets.UTF_8));
                        } finally {
                            os.close();
                        }
                    }
                    int code = conn.getResponseCode();
                    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String text = read(in);
                    if (code >= 400) {
                        error = "HTTP " + code + " " + text;
                    } else {
                        JSONObject o = new JSONObject(text);
                        level = o.optString("level", "app");
                        mode = o.optString("mode", "ask");
                    }
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName() + ": " + t.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final String fl = level, fm = mode, fe = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(fl, fm, fe);
                    }
                });
            }
        }).start();
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            br.close();
        }
        return sb.toString();
    }
}
