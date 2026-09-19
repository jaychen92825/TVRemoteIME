package com.github.catvod.crawler;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

public abstract class Spider {
    public String siteKey;
    private static final ConfigDns CONFIG_DNS = new ConfigDns();
    private static final ConfigHeaderInterceptor CONFIG_HEADERS = new ConfigHeaderInterceptor();

    public static Dns safeDns() {
        return CONFIG_DNS;
    }

    public static OkHttpClient client() {
        return ClientHolder.CLIENT;
    }

    public static void configureNetwork(JSONObject config) {
        CONFIG_DNS.configure(config);
        CONFIG_HEADERS.configure(config);
    }

    public void init(Context context) throws Exception {
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "";
    }

    public String liveContent(String url) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return null;
    }

    public String action(String action) throws Exception {
        return null;
    }

    public void destroy() {
    }

    private static class ClientHolder {
        static final X509TrustManager TRUST_MANAGER = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        static final OkHttpClient CLIENT = createClient();

        private static OkHttpClient createClient() {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .cookieJar(new SessionCookieJar())
                    .dns(CONFIG_DNS)
                    .addInterceptor(new AuthQueryInterceptor())
                    .addNetworkInterceptor(CONFIG_HEADERS)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true);
            try {
                // Type-3 spiders are built against CatVod/FongMi's permissive TLS
                // client. Scope the compatibility behavior to the Spider client only.
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{TRUST_MANAGER}, new SecureRandom());
                builder.sslSocketFactory(sslContext.getSocketFactory(), TRUST_MANAGER);
                builder.hostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            } catch (Exception ignored) {
            }
            return builder.build();
        }
    }

    private static class ConfigDns implements Dns {
        private volatile Map<String, String> hosts = Collections.emptyMap();

        void configure(JSONObject config) {
            LinkedHashMap<String, String> parsed = new LinkedHashMap<String, String>();
            JSONArray items = config == null ? null : config.optJSONArray("hosts");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    String item = items.optString(i, "");
                    int split = item.indexOf('=');
                    if (split <= 0 || split >= item.length() - 1) continue;
                    String from = item.substring(0, split).trim();
                    String to = item.substring(split + 1).trim();
                    if (from.length() > 0 && to.length() > 0) parsed.put(from, to);
                }
            }
            hosts = Collections.unmodifiableMap(parsed);
        }

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
    }

    private static class ConfigHeaderInterceptor implements Interceptor {
        private volatile List<HeaderRule> rules = Collections.emptyList();
        private final ConcurrentHashMap<String, String> redirectMap = new ConcurrentHashMap<String, String>();

        void configure(JSONObject config) {
            ArrayList<HeaderRule> parsed = new ArrayList<HeaderRule>();
            JSONArray items = config == null ? null : config.optJSONArray("headers");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    JSONObject object = item.optJSONObject("header");
                    String host = item.optString("host", "");
                    if (host.length() == 0 || object == null) continue;
                    LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
                    Iterator<String> keys = object.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = object.optString(key, "");
                        if (isSafeHeader(key, value)) headers.put(normalizeHeaderName(key), value);
                    }
                    if (!headers.isEmpty()) parsed.add(new HeaderRule(host, headers));
                }
            }
            rules = Collections.unmodifiableList(parsed);
            redirectMap.clear();
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Request.Builder builder = request.newBuilder();
            String host = request.url().host();
            for (HeaderRule rule : rules) {
                if (!containOrMatch(host, rule.host)) continue;
                for (Map.Entry<String, String> entry : rule.headers.entrySet()) {
                    try {
                        builder.header(entry.getKey(), entry.getValue());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            request = builder.build();
            Response response = chain.proceed(request);
            String encoding = response.header("Content-Encoding");
            if ("deflate".equalsIgnoreCase(encoding) && response.body() != null) {
                response = inflateDeflate(response);
            }
            String requestUrl = request.url().toString();
            if (response.code() == 406 && redirectMap.containsKey(requestUrl)) {
                String location = redirectMap.remove(requestUrl);
                okhttp3.Protocol protocol = response.protocol();
                response.close();
                return new Response.Builder()
                        .request(request)
                        .protocol(protocol)
                        .code(302)
                        .message("Found")
                        .header("Location", location)
                        .body(ResponseBody.create(null, new byte[0]))
                        .build();
            }
            if (response.code() == 302) {
                String location = response.header("Location");
                if (location != null && location.length() > 0) redirectMap.put(location, requestUrl);
            }
            return response;
        }

        private Response inflateDeflate(final Response response) {
            final MediaType contentType = response.body().contentType();
            InputStream input = new InflaterInputStream(response.body().byteStream(), new Inflater(true));
            final BufferedSource source = Okio.buffer(Okio.source(input));
            ResponseBody body = new ResponseBody() {
                @Override
                public MediaType contentType() {
                    return contentType;
                }

                @Override
                public long contentLength() {
                    return -1;
                }

                @Override
                public BufferedSource source() {
                    return source;
                }
            };
            return response.newBuilder()
                    .removeHeader("Content-Encoding")
                    .removeHeader("Content-Length")
                    .body(body)
                    .build();
        }
    }

    private static class HeaderRule {
        final String host;
        final Map<String, String> headers;

        HeaderRule(String host, Map<String, String> headers) {
            this.host = host;
            this.headers = Collections.unmodifiableMap(headers);
        }
    }

    private static class SessionCookieJar implements CookieJar {
        private final List<Cookie> cookies = new ArrayList<Cookie>();

        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> values) {
            long now = System.currentTimeMillis();
            for (Cookie value : values) {
                Iterator<Cookie> iterator = cookies.iterator();
                while (iterator.hasNext()) {
                    Cookie old = iterator.next();
                    if (old.name().equals(value.name()) && old.domain().equals(value.domain())
                            && old.path().equals(value.path())) iterator.remove();
                }
                if (value.expiresAt() > now) cookies.add(value);
            }
        }

        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            long now = System.currentTimeMillis();
            ArrayList<Cookie> result = new ArrayList<Cookie>();
            Iterator<Cookie> iterator = cookies.iterator();
            while (iterator.hasNext()) {
                Cookie cookie = iterator.next();
                if (cookie.expiresAt() <= now) iterator.remove();
                else if (cookie.matches(url)) result.add(cookie);
            }
            return result;
        }
    }

    private static class AuthQueryInterceptor implements Interceptor {
        private final ConcurrentHashMap<String, String> authByHost = new ConcurrentHashMap<String, String>();

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            HttpUrl url = request.url();
            String auth = url.queryParameter("auth");
            if (auth != null) {
                authByHost.put(url.host(), auth);
            } else {
                auth = authByHost.get(url.host());
                if (auth != null) request = request.newBuilder().url(url.newBuilder()
                        .addQueryParameter("auth", auth).build()).build();
            }
            return chain.proceed(request);
        }
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

    private static boolean isSafeHeader(String key, String value) {
        if (key == null || key.length() == 0 || value == null) return false;
        return key.indexOf('\r') < 0 && key.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }
}
