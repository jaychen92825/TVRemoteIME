package com.android.tvremoteime.media;

import android.util.Log;

import com.android.tvremoteime.IMEService;

import org.apache.http.util.CharArrayBuffer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

public class MediaHttp {
    public static String get(String uri) {
        try {
            return getRequired(uri);
        } catch (Exception e) {
            Log.e(IMEService.TAG, "media http get failed: " + uri, e);
            return null;
        }
    }

    public static String getRequired(String uri) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = normalizeUrl(uri);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(18000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json, text/xml, application/xml, text/plain, */*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 TVRemoteIME Media Browser");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
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
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static File downloadRequired(String uri, File file) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = normalizeUrl(uri);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/java-archive, application/octet-stream, */*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 TVRemoteIME Media Browser");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            InputStream input = new BufferedInputStream(conn.getInputStream());
            FileOutputStream output = new FileOutputStream(file);
            try {
                byte[] buffer = new byte[16384];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                }
                output.flush();
                if (total == 0) throw new IOException("返回内容为空");
                return file;
            } finally {
                try {
                    output.close();
                } finally {
                    input.close();
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static MediaBinary getBinary(String uri) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = normalizeUrl(uri);
            if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("不支持的图片地址");
            }
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(18000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "image/*,*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 TVRemoteIME Media Browser");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            InputStream input = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(4096, conn.getContentLength()));
            try {
                byte[] buffer = new byte[16384];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > 10 * 1024 * 1024) throw new IOException("图片文件过大");
                    output.write(buffer, 0, read);
                }
                MediaBinary result = new MediaBinary();
                result.data = output.toByteArray();
                result.mimeType = conn.getContentType();
                if (result.mimeType == null || !result.mimeType.toLowerCase().startsWith("image/")) result.mimeType = "image/jpeg";
                return result;
            } finally {
                try {
                    output.close();
                } finally {
                    input.close();
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static URL normalizeUrl(String uri) throws Exception {
        URL url = new URL(uri);
        String host = url.getHost();
        if (host == null) return url;
        String asciiHost = IDN.toASCII(host);
        if (host.equals(asciiHost)) return url;
        URI normalized = new URI(url.getProtocol(), null, asciiHost, url.getPort(), url.getPath(), url.getQuery(), url.getRef());
        return normalized.toURL();
    }
}
