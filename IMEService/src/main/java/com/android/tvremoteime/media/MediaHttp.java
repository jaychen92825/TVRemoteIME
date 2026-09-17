package com.android.tvremoteime.media;

import android.util.Base64;
import android.util.Log;

import com.android.tvremoteime.IMEService;

import org.apache.http.util.CharArrayBuffer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MediaHttp {
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 TVRemoteIME Media Browser";
    private static final Pattern IMAGE_HEADER_MARKER = Pattern.compile("@([A-Za-z0-9_-]+)=");
    private static final Pattern IMAGE_MIME = Pattern.compile("image/[a-z0-9.+-]+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final OkHttpClient BASE_IMAGE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .build();
    private static volatile String cachedHostsKey = "";
    private static volatile OkHttpClient cachedImageClient = BASE_IMAGE_CLIENT;

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
            conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
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
            conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
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
        return getBinary(uri, null);
    }

    public static MediaBinary getBinary(String uri, JSONObject config) throws Exception {
        MediaImageRequest imageRequest = parseImageRequest(uri);
        if (imageRequest.url.regionMatches(true, 0, "data:", 0, 5)) {
            return decodeDataImage(imageRequest.url);
        }
        URL url = normalizeUrl(imageRequest.url);
        if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("不支持的图片地址");
        }

        Request.Builder request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "image/*,*/*")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", DEFAULT_USER_AGENT);
        applyConfigHeaders(request, url.getHost(), config);
        for (Map.Entry<String, String> entry : imageRequest.headers.entrySet()) {
            setHeaderIfValid(request, entry.getKey(), entry.getValue());
        }

        OkHttpClient client = getImageClient(config);

        Response response = null;
        try {
            response = client.newCall(request.build()).execute();
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("图片响应为空");
            InputStream input = new BufferedInputStream(body.byteStream());
            long bodyLength = body.contentLength();
            if (bodyLength > MAX_IMAGE_BYTES) throw new IOException("图片文件过大");
            int capacity = bodyLength > 0 && bodyLength <= MAX_IMAGE_BYTES ? (int) bodyLength : 4096;
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(4096, capacity));
            try {
                byte[] buffer = new byte[16384];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("图片文件过大");
                    output.write(buffer, 0, read);
                }
                MediaBinary result = new MediaBinary();
                result.data = output.toByteArray();
                result.mimeType = resolveImageMime(body.contentType() == null ? null : body.contentType().toString(), result.data);
                return result;
            } finally {
                try {
                    output.close();
                } finally {
                    input.close();
                }
            }
        } finally {
            if (response != null) response.close();
        }
    }

    static MediaImageRequest parseImageRequest(String uri) throws Exception {
        if (uri == null) throw new IOException("图片地址为空");
        Matcher matcher = IMAGE_HEADER_MARKER.matcher(uri);
        int firstMarker = -1;
        boolean hasSupportedMarker = false;
        while (matcher.find()) {
            if (firstMarker < 0) firstMarker = matcher.start();
            if (isSupportedImageMarker(matcher.group(1))) hasSupportedMarker = true;
        }
        if (firstMarker < 0 || !hasSupportedMarker) return new MediaImageRequest(uri);

        matcher.reset();
        matcher.find();
        MediaImageRequest result = new MediaImageRequest(uri.substring(0, firstMarker));
        String key = matcher.group(1);
        int valueStart = matcher.end();
        while (true) {
            boolean hasNext = matcher.find();
            int valueEnd = hasNext ? matcher.start() : uri.length();
            addImageHeader(result.headers, key, uri.substring(valueStart, valueEnd));
            if (!hasNext) break;
            key = matcher.group(1);
            valueStart = matcher.end();
        }
        return result;
    }

    private static void addImageHeader(Map<String, String> headers, String rawKey, String rawValue) {
        String key = normalizeHeaderName(rawKey);
        String value = decodeHeaderValue(rawValue);
        if ("Type".equalsIgnoreCase(key)) return;
        if ("Headers".equalsIgnoreCase(key)) {
            try {
                JSONObject object = new JSONObject(value);
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    String headerKey = keys.next();
                    String headerValue = object.optString(headerKey, "");
                    if (isSafeHeader(headerKey, headerValue)) headers.put(normalizeHeaderName(headerKey), headerValue);
                }
            } catch (Exception ignored) {
            }
            return;
        }
        if (("Cookie".equalsIgnoreCase(key) || "Referer".equalsIgnoreCase(key)
                || "User-Agent".equalsIgnoreCase(key)) && isSafeHeader(key, value)) {
            headers.put(key, value);
        }
    }

    private static boolean isSupportedImageMarker(String key) {
        String normalized = normalizeHeaderName(key);
        return "Headers".equalsIgnoreCase(normalized) || "Cookie".equalsIgnoreCase(normalized)
                || "Referer".equalsIgnoreCase(normalized) || "User-Agent".equalsIgnoreCase(normalized)
                || "Type".equalsIgnoreCase(normalized);
    }

    private static MediaBinary decodeDataImage(String uri) throws Exception {
        int comma = uri.indexOf(',');
        if (comma <= 5) throw new IOException("无效的 data 图片");
        String metadata = uri.substring(5, comma).trim();
        String[] parts = metadata.split(";");
        String mime = parts.length == 0 ? "" : parts[0].trim();
        boolean isBase64 = false;
        for (int i = 1; i < parts.length; i++) {
            if ("base64".equalsIgnoreCase(parts[i].trim())) {
                isBase64 = true;
                break;
            }
        }
        if (!isBase64 || !IMAGE_MIME.matcher(mime).matches()) throw new IOException("不支持的 data 图片格式");
        byte[] data;
        try {
            data = Base64.decode(uri.substring(comma + 1), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IOException("无效的 base64 图片", e);
        }
        if (data.length == 0 || data.length > MAX_IMAGE_BYTES) throw new IOException("图片文件过大");
        MediaBinary result = new MediaBinary();
        result.data = data;
        result.mimeType = resolveImageMime(mime, data);
        return result;
    }

    static String resolveImageMime(String declaredMime, byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new IOException("图片响应为空");
        if (looksLikeHtml(data)) throw new IOException("图片地址返回了网页内容");

        String detected = detectImageMime(data);
        if (detected != null) return detected;

        String declared = declaredMime == null ? "" : declaredMime.trim();
        int semicolon = declared.indexOf(';');
        if (semicolon >= 0) declared = declared.substring(0, semicolon).trim();
        if (declared.toLowerCase().startsWith("image/")) return declared;
        throw new IOException("返回内容不是可识别的图片");
    }

    private static String detectImageMime(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (data.length >= 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && (data[4] & 0xff) == 0x0d && (data[5] & 0xff) == 0x0a && (data[6] & 0xff) == 0x1a && (data[7] & 0xff) == 0x0a) {
            return "image/png";
        }
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
            return "image/gif";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') return "image/bmp";
        return null;
    }

    private static boolean looksLikeHtml(byte[] data) {
        int length = Math.min(data.length, 512);
        int start = 0;
        while (start < length && Character.isWhitespace((char) (data[start] & 0xff))) start++;
        if (start >= length || data[start] != '<') return false;
        String prefix;
        try {
            prefix = new String(data, start, length - start, "UTF-8").toLowerCase();
        } catch (Exception e) {
            return false;
        }
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
                || prefix.startsWith("<head") || prefix.startsWith("<body");
    }

    private static void applyConfigHeaders(Request.Builder request, String host, JSONObject config) {
        if (config == null) return;
        JSONArray rules = config.optJSONArray("headers");
        if (rules == null) return;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null || !containOrMatch(host, rule.optString("host"))) continue;
            JSONObject headers = rule.optJSONObject("header");
            if (headers == null) continue;
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headers.optString(key, "");
                setHeaderIfValid(request, normalizeHeaderName(key), value);
            }
        }
    }

    private static void setHeaderIfValid(Request.Builder request, String key, String value) {
        if (!isSafeHeader(key, value)) return;
        try {
            request.header(key, value);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static OkHttpClient getImageClient(JSONObject config) {
        Map<String, String> hosts = parseHosts(config);
        String key = hosts.toString();
        if (key.equals(cachedHostsKey)) return cachedImageClient;
        synchronized (MediaHttp.class) {
            if (key.equals(cachedHostsKey)) return cachedImageClient;
            cachedImageClient = hosts.isEmpty()
                    ? BASE_IMAGE_CLIENT
                    : BASE_IMAGE_CLIENT.newBuilder().dns(buildDns(hosts)).build();
            cachedHostsKey = key;
            return cachedImageClient;
        }
    }

    private static Dns buildDns(final Map<String, String> hosts) {
        if (hosts.isEmpty()) return Dns.SYSTEM;
        return new Dns() {
            @Override
            public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                String target = hostname;
                String exact = hosts.get(hostname);
                if (exact != null) {
                    target = exact;
                } else {
                    for (Map.Entry<String, String> entry : hosts.entrySet()) {
                        if (containOrMatch(hostname, entry.getKey())) {
                            target = entry.getValue();
                            break;
                        }
                    }
                }
                return Dns.SYSTEM.lookup(target);
            }
        };
    }

    static Map<String, String> parseHosts(JSONObject config) {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        if (config == null) return result;
        JSONArray items = config.optJSONArray("hosts");
        if (items == null) return result;
        for (int i = 0; i < items.length(); i++) {
            String item = items.optString(i, "");
            int split = item.indexOf('=');
            if (split <= 0 || split >= item.length() - 1) continue;
            String from = item.substring(0, split).trim();
            String to = item.substring(split + 1).trim();
            if (from.length() > 0 && to.length() > 0) result.put(from, to);
        }
        return result;
    }

    private static boolean containOrMatch(String text, String regex) {
        if (text == null || regex == null || regex.length() == 0) return false;
        try {
            return text.contains(regex) || text.matches(regex);
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeHeaderName(String key) {
        if ("User_Agent".equalsIgnoreCase(key) || "UserAgent".equalsIgnoreCase(key)) return "User-Agent";
        if ("Referrer".equalsIgnoreCase(key)) return "Referer";
        return key;
    }

    private static String decodeHeaderValue(String value) {
        if (value == null || value.indexOf('%') < 0) return value == null ? "" : value;
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static boolean isSafeHeader(String key, String value) {
        if (key == null || key.length() == 0 || value == null) return false;
        return key.indexOf('\r') < 0 && key.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    static class MediaImageRequest {
        final String url;
        final Map<String, String> headers = new LinkedHashMap<String, String>();

        MediaImageRequest(String url) {
            this.url = url;
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
