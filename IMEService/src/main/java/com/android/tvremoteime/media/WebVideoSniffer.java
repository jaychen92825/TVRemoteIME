package com.android.tvremoteime.media;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.android.tvremoteime.VideoPlayHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Loads a user supplied webpage on the TV and observes likely video requests.
 * Request headers remain on the TV and are only applied when a candidate plays.
 */
public final class WebVideoSniffer {
    private static final String TAG = "WebVideoSniffer";
    private static final long SNIFF_WINDOW_MS = 18000L;
    private static final int MAX_CANDIDATES = 24;
    private static final String DOM_PROMPT_PREFIX = "__TVREMOTE_VIDEO__";
    private static volatile WebVideoSniffer instance;

    public static WebVideoSniffer get(Context context) {
        if (instance == null) {
            synchronized (WebVideoSniffer.class) {
                if (instance == null) instance = new WebVideoSniffer(context.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
    private String sessionId = "";
    private String pageUrl = "";
    private String pageTitle = "";
    private String status = "idle";
    private String message = "";
    private int candidateCounter;
    private WebView webView;
    private volatile String webUserAgent = "";

    private WebVideoSniffer(Context context) {
        this.context = context;
    }

    public JSONObject start(String rawUrl) throws Exception {
        final String url = normalizeUrl(rawUrl);
        final String newSessionId = UUID.randomUUID().toString();
        synchronized (lock) {
            sessionId = newSessionId;
            pageUrl = url;
            pageTitle = "";
            status = "loading";
            message = "正在打开网页并寻找视频...";
            candidateCounter = 0;
            candidates.clear();
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                beginWebView(newSessionId, url);
            }
        });
        return snapshot(newSessionId);
    }

    public JSONObject snapshot(String requestedSessionId) throws Exception {
        synchronized (lock) {
            if (!TextUtils.isEmpty(requestedSessionId) && !requestedSessionId.equals(sessionId)) {
                throw new Exception("网页视频会话已过期，请重新嗅探");
            }
            JSONObject obj = new JSONObject();
            obj.put("sessionId", sessionId);
            obj.put("pageUrl", pageUrl);
            obj.put("pageTitle", pageTitle);
            obj.put("status", status);
            obj.put("message", message);
            JSONArray items = new JSONArray();
            List<Candidate> ranked = new ArrayList<>(candidates.values());
            Collections.sort(ranked, new Comparator<Candidate>() {
                @Override
                public int compare(Candidate left, Candidate right) {
                    return right.score - left.score;
                }
            });
            for (Candidate candidate : ranked) items.put(candidate.toPublicJson());
            obj.put("candidates", items);
            obj.put("recommendedCandidateId", ranked.isEmpty() ? "" : ranked.get(0).id);
            return obj;
        }
    }

    public JSONObject play(String requestedSessionId, String candidateId) throws Exception {
        final Candidate candidate;
        final String title;
        synchronized (lock) {
            if (TextUtils.isEmpty(sessionId) || !sessionId.equals(requestedSessionId)) {
                throw new Exception("网页视频会话已过期，请重新嗅探");
            }
            candidate = candidates.get(candidateId);
            if (candidate == null) throw new Exception("没有找到这个视频候选，请重新嗅探");
            title = TextUtils.isEmpty(pageTitle) ? candidate.host : pageTitle;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                VideoPlayHelper.playDirectStream(context, candidate.url, title, candidate.headers);
            }
        });
        JSONObject obj = new JSONObject();
        obj.put("success", true);
        obj.put("candidateId", candidate.id);
        obj.put("title", title);
        return obj;
    }

    private String normalizeUrl(String rawUrl) throws Exception {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (TextUtils.isEmpty(value)) throw new Exception("请输入网页地址");
        if (!value.contains("://")) value = "https://" + value;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new Exception("只支持 http/https 网页地址");
        }
        if (TextUtils.isEmpty(uri.getHost())) throw new Exception("网页地址无效");
        return uri.toString();
    }

    private void beginWebView(final String targetSessionId, final String url) {
        destroyWebView();
        if (!isCurrent(targetSessionId)) return;
        try {
            webView = new WebView(context);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setLoadWithOverviewMode(false);
            settings.setLoadsImagesAutomatically(false);
            settings.setBlockNetworkImage(true);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            if (Build.VERSION.SDK_INT >= 17) settings.setMediaPlaybackRequiresUserGesture(false);
            if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            // shouldInterceptRequest runs off the UI thread. Cache anything we
            // need from WebView here instead of touching the WebView instance
            // from request interception callbacks.
            webUserAgent = safe(settings.getUserAgentString());

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, String requestUrl) {
                    captureSafely(targetSessionId, requestUrl, null, false);
                    return super.shouldInterceptRequest(view, requestUrl);
                }

                @Override
                @TargetApi(21)
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    captureSafely(targetSessionId, request.getUrl() == null ? null : request.getUrl().toString(),
                            request.getRequestHeaders(), false);
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onLoadResource(WebView view, String requestUrl) {
                    captureSafely(targetSessionId, requestUrl, null, false);
                    super.onLoadResource(view, requestUrl);
                }

                @Override
                public void onPageFinished(final WebView view, String finishedUrl) {
                    if (!isCurrent(targetSessionId)) return;
                    synchronized (lock) {
                        pageTitle = safe(view.getTitle());
                        status = "sniffing";
                        message = candidates.isEmpty() ? "网页已打开，正在寻找可播放视频..." : "已发现视频，可继续等待更多候选";
                    }
                    scanDom(view);
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (isCurrent(targetSessionId) && webView == view) scanDom(view);
                        }
                    }, 2500L);
                    super.onPageFinished(view, finishedUrl);
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    if (isCurrent(targetSessionId)) {
                        synchronized (lock) {
                            if (candidates.isEmpty()) {
                                status = "error";
                                message = TextUtils.isEmpty(description) ? "网页加载失败" : description;
                            }
                        }
                    }
                    super.onReceivedError(view, errorCode, description, failingUrl);
                }

                @Override
                @TargetApi(26)
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    if (isCurrent(targetSessionId)) {
                        synchronized (lock) {
                            status = "error";
                            message = detail != null && detail.didCrash()
                                    ? "网页渲染进程崩溃，已安全停止嗅探。可以重试或换一个具体播放页。"
                                    : "网页占用资源过高，系统已停止渲染。可以重试或换一个具体播放页。";
                        }
                    }
                    discardCrashedWebView(view);
                    // Returning true tells WebView that the host handled the
                    // renderer loss. Returning false lets Android terminate
                    // the host app, which is unacceptable for a background
                    // sniffing helper.
                    return true;
                }
            });
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsPrompt(WebView view, String origin, String promptMessage,
                                          String defaultValue, JsPromptResult result) {
                    if (promptMessage != null && promptMessage.startsWith(DOM_PROMPT_PREFIX)) {
                        String encoded = promptMessage.substring(DOM_PROMPT_PREFIX.length());
                        try {
                            captureSafely(targetSessionId, Uri.decode(encoded), null, true);
                        } catch (Exception ignored) {
                        }
                        result.confirm("");
                        return true;
                    }
                    return super.onJsPrompt(view, origin, promptMessage, defaultValue, result);
                }
            });
            webView.loadUrl(url);
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finishSniff(targetSessionId);
                }
            }, SNIFF_WINDOW_MS);
        } catch (Throwable error) {
            synchronized (lock) {
                status = "error";
                message = "网页嗅探启动失败：" + safe(error.getMessage());
            }
            destroyWebView();
        }
    }

    private void scanDom(WebView view) {
        String script = "javascript:(function(){try{" +
                "var n=document.querySelectorAll('video,source');" +
                "for(var i=0;i<n.length;i++){var u=n[i].currentSrc||n[i].src;" +
                "if(u&&u.indexOf('blob:')!==0){prompt('" + DOM_PROMPT_PREFIX + "'+encodeURIComponent(u),'');}}" +
                "for(var j=0;j<document.querySelectorAll('video').length;j++){try{document.querySelectorAll('video')[j].load();}catch(e){}}" +
                "}catch(e){}})()";
        view.loadUrl(script);
    }

    private void finishSniff(String targetSessionId) {
        if (!isCurrent(targetSessionId)) return;
        synchronized (lock) {
            if (!"error".equals(status)) {
                status = "ready";
                message = candidates.isEmpty()
                        ? "暂未发现可播放视频。可以确认链接后重新嗅探，部分网站需要先进入具体播放页。"
                        : "已发现 " + candidates.size() + " 个可播放候选";
            }
        }
        destroyWebView();
    }

    private void capture(String targetSessionId, String requestUrl, Map<String, String> requestHeaders, boolean fromDom) {
        if (!isCurrent(targetSessionId) || TextUtils.isEmpty(requestUrl)) return;
        String lower = requestUrl.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return;
        int score = candidateScore(lower, requestHeaders, fromDom);
        if (score <= 0) return;
        synchronized (lock) {
            if (!targetSessionId.equals(sessionId)) return;
            for (Candidate existing : candidates.values()) {
                if (existing.url.equals(requestUrl)) {
                    if (requestHeaders != null) existing.headers.putAll(requestHeaders);
                    addPlaybackHeaders(existing.headers, requestUrl);
                    return;
                }
            }
            if (candidates.size() >= MAX_CANDIDATES) return;
            Candidate candidate = new Candidate();
            candidate.id = targetSessionId + "-" + (++candidateCounter);
            candidate.url = requestUrl;
            candidate.type = mediaType(lower);
            candidate.host = safe(Uri.parse(requestUrl).getHost());
            candidate.score = score;
            if (requestHeaders != null) candidate.headers.putAll(requestHeaders);
            addPlaybackHeaders(candidate.headers, requestUrl);
            candidates.put(candidate.id, candidate);
            status = "sniffing";
            message = "已发现 " + candidates.size() + " 个视频候选";
        }
    }

    private void captureSafely(String targetSessionId, String requestUrl,
                               Map<String, String> requestHeaders, boolean fromDom) {
        try {
            capture(targetSessionId, requestUrl, requestHeaders, fromDom);
        } catch (RuntimeException error) {
            // A malformed request or an OEM WebView implementation should not
            // be able to take down the remote-control process while sniffing.
            Log.w(TAG, "Ignoring failed media candidate capture", error);
        }
    }

    private int candidateScore(String lowerUrl, Map<String, String> headers, boolean fromDom) {
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains("format=m3u8")) return 100;
        if (lowerUrl.contains(".mpd") || lowerUrl.contains("dash.mpd")) return 95;
        if (lowerUrl.matches(".*\\.(mp4|m4v|webm|mkv|flv|mov)(\\?.*)?$")) return 90;
        if (fromDom && lowerUrl.matches(".*\\.(ts|m2ts)(\\?.*)?$")) return 60;
        if (fromDom) return 85;
        if (headers != null) {
            String accept = headerValue(headers, "Accept").toLowerCase(Locale.US);
            if (accept.contains("video/") || accept.contains("mpegurl") || accept.contains("dash+xml")) return 70;
        }
        return 0;
    }

    private String mediaType(String lowerUrl) {
        if (lowerUrl.contains("m3u8")) return "HLS";
        if (lowerUrl.contains(".mpd")) return "DASH";
        if (lowerUrl.contains(".mp4") || lowerUrl.contains(".m4v")) return "MP4";
        if (lowerUrl.contains(".webm")) return "WebM";
        if (lowerUrl.contains(".mkv")) return "MKV";
        return "Video";
    }

    private void addPlaybackHeaders(Map<String, String> headers, String requestUrl) {
        if (TextUtils.isEmpty(headerValue(headers, "Referer")) && !TextUtils.isEmpty(pageUrl)) {
            headers.put("Referer", pageUrl);
        }
        String userAgent = webUserAgent;
        if (TextUtils.isEmpty(headerValue(headers, "User-Agent")) && !TextUtils.isEmpty(userAgent)) {
            headers.put("User-Agent", userAgent);
        }
        String cookies = safeCookies(requestUrl);
        if (TextUtils.isEmpty(cookies) && !TextUtils.isEmpty(pageUrl)) cookies = safeCookies(pageUrl);
        if (!TextUtils.isEmpty(cookies)) headers.put("Cookie", cookies);
    }

    private String safeCookies(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            return safe(CookieManager.getInstance().getCookie(url));
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read cookies for sniffed request", error);
            return "";
        }
    }

    private String headerValue(Map<String, String> headers, String name) {
        if (headers == null) return "";
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return safe(entry.getValue());
        }
        return "";
    }

    private boolean isCurrent(String targetSessionId) {
        synchronized (lock) {
            return targetSessionId != null && targetSessionId.equals(sessionId);
        }
    }

    private void destroyWebView() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
    }

    private void discardCrashedWebView(WebView crashedView) {
        if (webView == crashedView) webView = null;
        if (crashedView == null) return;
        try {
            crashedView.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Candidate {
        String id;
        String url;
        String type;
        String host;
        int score;
        final Map<String, String> headers = new HashMap<>();

        JSONObject toPublicJson() throws Exception {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("type", type);
            obj.put("host", host);
            obj.put("score", score);
            obj.put("displayUrl", url.length() > 180 ? url.substring(0, 180) + "..." : url);
            return obj;
        }
    }
}
