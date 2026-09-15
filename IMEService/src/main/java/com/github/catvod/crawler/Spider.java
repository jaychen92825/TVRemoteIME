package com.github.catvod.crawler;

import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public abstract class Spider {
    public String siteKey;

    public static Dns safeDns() {
        return Dns.SYSTEM;
    }

    public static OkHttpClient client() {
        return ClientHolder.CLIENT;
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
        static final OkHttpClient CLIENT = new OkHttpClient.Builder()
                .cookieJar(new SessionCookieJar())
                .addInterceptor(new AuthQueryInterceptor())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
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
}
