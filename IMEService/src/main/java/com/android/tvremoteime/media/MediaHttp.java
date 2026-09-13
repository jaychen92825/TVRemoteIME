package com.android.tvremoteime.media;

import android.util.Log;

import com.android.tvremoteime.IMEService;

import org.apache.http.util.CharArrayBuffer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MediaHttp {
    public static String get(String uri) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(uri);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(18000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json, text/xml, application/xml, text/plain, */*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 TVRemoteIME Media Browser");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            try {
                CharArrayBuffer buffer = new CharArrayBuffer(Math.max(4096, conn.getContentLength()));
                char[] tmp = new char[2048];
                int len;
                while ((len = reader.read(tmp)) != -1) buffer.append(tmp, 0, len);
                return buffer.toString();
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            Log.e(IMEService.TAG, "media http get failed: " + uri, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
