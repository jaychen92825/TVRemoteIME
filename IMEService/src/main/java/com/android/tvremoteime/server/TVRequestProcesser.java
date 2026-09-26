package com.android.tvremoteime.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.android.tvremoteime.IMEService;
import com.android.tvremoteime.R;
import com.android.tvremoteime.media.MediaHttp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Created by kingt on 2018/3/3.
 */

public class TVRequestProcesser implements RequestProcesser {
    private static final String PREFS = "tv_sources";
    private static final String PREF_USER_CUSTOM = "user_custom";
    private static final String PREF_OWNERSHIP_READY = "ownership_ready";
    private static final String PREF_LAST_REFRESH = "last_refresh_ms";
    private static final long REFRESH_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    // iptv-org publishes a continuously maintained country playlist. Keeping the
    // URL here (rather than copying today's stream URLs into the APK) lets the
    // built-in list recover as upstream channels change over time.
    private static final String DEFAULT_REMOTE_PLAYLIST =
            "https://iptv-org.github.io/iptv/countries/cn.m3u";

    private final Context context;
    private final SharedPreferences preferences;
    private final File tvFile = new File(RemoteServerFileManager.baseDir, "tv.txt");
    private final File remoteCacheFile = new File(RemoteServerFileManager.baseDir, "tv_default.m3u");

    public TVRequestProcesser(Context context){
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        initTVData();
        initOwnershipState();
    }

    private void initTVData(){
        if(!tvFile.exists()){
            try {
                InputStream ins = context.getResources().openRawResource(R.raw.tv);
                FileOutputStream out = new FileOutputStream(tvFile);
                byte[] b = new byte[1024];
                int n = 0;
                while ((n = ins.read(b)) != -1) {
                    out.write(b, 0, n);
                }
                ins.close();
                out.close();
            }catch (Exception e) {
                Log.e(IMEService.TAG, "init tv data", e);
            }
        }
    }

    /**
     * Older releases copied the bundled tv.txt to app storage once and then kept
     * serving it forever. Existing installs therefore need a migration that can
     * distinguish that untouched copy from a list the user edited manually.
     */
    private void initOwnershipState() {
        if (preferences.getBoolean(PREF_OWNERSHIP_READY, false)) return;

        boolean userCustom = false;
        try {
            String local = readFile(tvFile);
            String bundled = readBundledTV();
            userCustom = local != null && bundled != null && !local.equals(bundled);
        } catch (Exception e) {
            // Preserve unknown existing data instead of replacing it remotely.
            userCustom = tvFile.exists();
            Log.w(IMEService.TAG, "detect tv source ownership failed", e);
        }

        preferences.edit()
                .putBoolean(PREF_USER_CUSTOM, userCustom)
                .putBoolean(PREF_OWNERSHIP_READY, true)
                .apply();
    }

    private boolean isUserCustom() {
        return preferences.getBoolean(PREF_USER_CUSTOM, false);
    }

    private String getDefaultTVData() {
        String cached = readValidRemoteCache();
        long lastRefresh = preferences.getLong(PREF_LAST_REFRESH, 0L);
        boolean stale = cached == null || System.currentTimeMillis() - lastRefresh >= REFRESH_INTERVAL_MS;

        if (stale) {
            try {
                String remote = MediaHttp.getRequired(DEFAULT_REMOTE_PLAYLIST);
                if (isValidM3U(remote)) {
                    writeTextAtomic(remoteCacheFile, remote);
                    return remote;
                }
                Log.w(IMEService.TAG, "default tv playlist is not a valid m3u");
            } catch (Exception e) {
                Log.w(IMEService.TAG, "refresh default tv playlist failed", e);
            } finally {
                // A failed upstream request should not block every page load. Retry
                // on the next refresh window while continuing to serve cache/fallback.
                preferences.edit().putLong(PREF_LAST_REFRESH, System.currentTimeMillis()).apply();
            }
        }

        if (cached != null) return cached;

        try {
            String bundled = readBundledTV();
            if (bundled != null) return bundled;
        } catch (Exception e) {
            Log.w(IMEService.TAG, "read bundled tv data failed", e);
        }
        return "";
    }

    private String readValidRemoteCache() {
        try {
            String cached = readFile(remoteCacheFile);
            return isValidM3U(cached) ? cached : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidM3U(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.length() > 0 && trimmed.charAt(0) == '\uFEFF') {
            trimmed = trimmed.substring(1).trim();
        }
        if (!trimmed.startsWith("#EXTM3U")) return false;
        return trimmed.contains("#EXTINF") && (trimmed.contains("http://") || trimmed.contains("https://"));
    }

    private String readBundledTV() throws IOException {
        InputStream input = context.getResources().openRawResource(R.raw.tv);
        try {
            return readStream(input);
        } finally {
            input.close();
        }
    }

    private String readFile(File file) throws IOException {
        if (file == null || !file.exists()) return null;
        InputStream input = new FileInputStream(file);
        try {
            return readStream(input);
        } finally {
            input.close();
        }
    }

    private String readStream(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), Charset.forName("UTF-8"));
    }

    private void writeTextAtomic(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(target.getAbsolutePath() + ".tmp");
        OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(temp), "utf-8");
        try {
            out.write(text == null ? "" : text);
            out.flush();
        } finally {
            out.close();
        }
        if (target.exists() && !target.delete()) throw new IOException("delete old tv cache failed");
        if (!temp.renameTo(target)) throw new IOException("replace tv cache failed");
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return "/tv.txt".equalsIgnoreCase(fileName);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        //if(!tvFile.exists()) return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "");
        if(session.getMethod() == NanoHTTPD.Method.POST){
            //edit
            String text = params.get("text");
            try {
                OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(tvFile), "utf-8");
                out.write(text == null ? "" : text);
                out.close();
                preferences.edit()
                        .putBoolean(PREF_USER_CUSTOM, true)
                        .putBoolean(PREF_OWNERSHIP_READY, true)
                        .apply();
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
            }catch (IOException e) {
                Log.e(IMEService.TAG, "POST /tv.txt", e);
            }
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "fail");
        }else{
            if (!isUserCustom()) {
                String text = getDefaultTVData();
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, text);
            }
            try {
                InputStream inputStream = tvFile.exists() ? new FileInputStream(tvFile) : context.getResources().openRawResource(R.raw.tv);
                return RemoteServer.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8", inputStream, (long) inputStream.available());
            } catch (IOException ioex) {
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + ioex.getMessage());
            }
        }
    }
}
