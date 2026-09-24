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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Loads a user supplied webpage on the TV and observes likely video requests.
 * Request headers remain on the TV and are only applied when a candidate plays.
 */
public final class WebVideoSniffer {
    private static final String TAG = "WebVideoSniffer";
    private static final long SNIFF_WINDOW_MS = 18000L;
    private static final long PLAY_REVALIDATE_MS = 60000L;
    private static final int MAX_CANDIDATES = 24;
    private static final int PROBE_BYTES = 16 * 1024;
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
    private final ExecutorService validationExecutor = Executors.newFixedThreadPool(3);
    private final OkHttpClient validationClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build();
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
            for (int i = ranked.size() - 1; i >= 0; i--) {
                if ("failed".equals(ranked.get(i).validationState)) ranked.remove(i);
            }
            Collections.sort(ranked, new Comparator<Candidate>() {
                @Override
                public int compare(Candidate left, Candidate right) {
                    return candidateRank(right) - candidateRank(left);
                }
            });
            for (Candidate candidate : ranked) items.put(candidate.toPublicJson());
            obj.put("candidates", items);
            String recommendedId = "";
            for (Candidate candidate : ranked) {
                if ("ready".equals(candidate.validationState)) {
                    recommendedId = candidate.id;
                    break;
                }
            }
            obj.put("recommendedCandidateId", recommendedId);
            return obj;
        }
    }

    public JSONObject play(String requestedSessionId, String candidateId) throws Exception {
        final Candidate candidate;
        synchronized (lock) {
            if (TextUtils.isEmpty(sessionId) || !sessionId.equals(requestedSessionId)) {
                throw new Exception("网页视频会话已过期，请重新嗅探");
            }
            candidate = candidates.get(candidateId);
            if (candidate == null) throw new Exception("没有找到这个视频候选，请重新嗅探");
        }

        boolean needsValidation;
        synchronized (lock) {
            needsValidation = !"ready".equals(candidate.validationState)
                    || System.currentTimeMillis() - candidate.validatedAt > PLAY_REVALIDATE_MS;
        }
        if (needsValidation) {
            ValidationResult result = validateCandidate(candidate);
            synchronized (lock) {
                if (!requestedSessionId.equals(sessionId) || candidates.get(candidateId) != candidate) {
                    throw new Exception("网页视频会话已过期，请重新嗅探");
                }
                applyValidationLocked(candidate, result);
            }
        }

        final String playUrl;
        final String title;
        final Map<String, String> playHeaders = new HashMap<>();
        synchronized (lock) {
            if (!"ready".equals(candidate.validationState)) {
                throw new Exception(TextUtils.isEmpty(candidate.validationMessage)
                        ? "这个视频候选无法播放，请选择其他候选"
                        : "这个视频候选不可用：" + candidate.validationMessage);
            }
            addPlaybackHeaders(candidate.headers, candidate.url);
            playUrl = candidate.url;
            playHeaders.putAll(candidate.headers);
            title = TextUtils.isEmpty(pageTitle) ? candidate.host : pageTitle;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                VideoPlayHelper.playDirectStream(context, playUrl, title, playHeaders);
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
                int readyCount = readyCandidateCountLocked();
                int validatingCount = validatingCandidateCountLocked();
                if (validatingCount > 0) {
                    status = "validating";
                    message = "已发现候选，正在验证可播放性...";
                } else {
                    status = "ready";
                    message = readyCount > 0
                            ? "已验证 " + readyCount + " 个可播放视频"
                            : "暂未找到可播放视频。可以确认链接后重新嗅探，部分网站需要先进入具体播放页。";
                }
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
        Candidate created = null;
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
            candidate.fromDom = fromDom;
            candidate.validationState = "validating";
            candidate.validationMessage = "正在验证";
            if (requestHeaders != null) candidate.headers.putAll(requestHeaders);
            addPlaybackHeaders(candidate.headers, requestUrl);
            candidates.put(candidate.id, candidate);
            created = candidate;
            status = "sniffing";
            message = "已发现 " + candidates.size() + " 个视频候选";
        }
        if (created != null) queueValidation(targetSessionId, created.id);
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
        if (fromDom && lowerUrl.matches(".*\\.(ts|m2ts)(\\?.*)?$")) return 45;
        if (fromDom) return 85;
        if (headers != null) {
            String accept = headerValue(headers, "Accept").toLowerCase(Locale.US);
            if (accept.contains("video/") || accept.contains("mpegurl") || accept.contains("dash+xml")) return 70;
        }
        return 0;
    }

    private static int candidateRank(Candidate candidate) {
        if (candidate == null) return Integer.MIN_VALUE;
        int validationBoost = "ready".equals(candidate.validationState) ? 1000
                : ("validating".equals(candidate.validationState) ? 0 : -1000);
        return validationBoost + candidate.score;
    }

    private void queueValidation(final String targetSessionId, final String candidateId) {
        validationExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Candidate candidate;
                synchronized (lock) {
                    if (!targetSessionId.equals(sessionId)) return;
                    candidate = candidates.get(candidateId);
                    if (candidate == null) return;
                }
                ValidationResult result = validateCandidate(candidate);
                synchronized (lock) {
                    if (!targetSessionId.equals(sessionId) || candidates.get(candidateId) != candidate) return;
                    applyValidationLocked(candidate, result);
                    if ("validating".equals(status) && validatingCandidateCountLocked() == 0) {
                        status = "ready";
                        int readyCount = readyCandidateCountLocked();
                        message = readyCount > 0
                                ? "已验证 " + readyCount + " 个可播放视频"
                                : "没有验证到可播放视频，请换一个具体播放页或重新嗅探。";
                    } else if ("sniffing".equals(status) || "loading".equals(status)) {
                        int readyCount = readyCandidateCountLocked();
                        if (readyCount > 0) message = "已验证 " + readyCount + " 个可播放视频，仍在寻找更多候选";
                    }
                }
            }
        });
    }

    private ValidationResult validateCandidate(Candidate candidate) {
        String url;
        String type;
        boolean fromDom;
        Map<String, String> headers = new HashMap<>();
        synchronized (lock) {
            url = candidate.url;
            type = candidate.type;
            fromDom = candidate.fromDom;
            headers.putAll(candidate.headers);
        }

        ValidationResult result = probe(url, type, headers, true);
        if (!result.ready && result.retryWithoutRange) {
            result = probe(url, type, headers, false);
        }
        if (!result.ready && fromDom && isTransportStreamUrl(url) && result.httpCode >= 200 && result.httpCode < 300) {
            result.message = "检测到单个视频分片，不作为主播放源";
        }
        return result;
    }

    private ValidationResult probe(String url, String type, Map<String, String> headers, boolean allowRange) {
        ValidationResult result = new ValidationResult();
        Response response = null;
        InputStream input = null;
        try {
            Request.Builder builder = new Request.Builder().url(url).get();
            builder.header("Accept", "*/*");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = safe(entry.getKey()).trim();
                String value = safe(entry.getValue());
                if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)
                        || "Host".equalsIgnoreCase(name)
                        || "Content-Length".equalsIgnoreCase(name)
                        || "Range".equalsIgnoreCase(name)) continue;
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException ignored) {
                }
            }

            boolean manifestHint = isManifestUrl(url) || "HLS".equals(type) || "DASH".equals(type);
            if (allowRange && !manifestHint) builder.header("Range", "bytes=0-" + (PROBE_BYTES - 1));

            response = validationClient.newCall(builder.build()).execute();
            result.httpCode = response.code();
            result.finalUrl = response.request().url().toString();
            result.contentType = safe(response.header("Content-Type")).toLowerCase(Locale.US);
            result.contentLength = parseContentLength(response.header("Content-Length"));

            if (!response.isSuccessful() && response.code() != 206) {
                result.message = "HTTP " + response.code();
                result.retryWithoutRange = allowRange && (response.code() == 400 || response.code() == 403
                        || response.code() == 405 || response.code() == 416);
                return result;
            }

            ResponseBody body = response.body();
            if (body == null) {
                result.message = "媒体响应为空";
                return result;
            }
            input = body.byteStream();
            byte[] prefix = readPrefix(input, PROBE_BYTES);
            String text = new String(prefix, Charset.forName("UTF-8"));
            String trimmed = text.trim();
            String lowerText = trimmed.toLowerCase(Locale.US);

            boolean hls = text.indexOf("#EXTM3U") >= 0;
            boolean dash = lowerText.indexOf("<mpd") >= 0;
            boolean videoMime = result.contentType.startsWith("video/");
            boolean hlsMime = result.contentType.contains("mpegurl") || result.contentType.contains("vnd.apple.mpegurl");
            boolean dashMime = result.contentType.contains("dash+xml");
            boolean binaryVideo = hasVideoSignature(prefix);
            boolean htmlOrJson = result.contentType.contains("text/html")
                    || result.contentType.contains("application/json")
                    || lowerText.startsWith("<!doctype html")
                    || lowerText.startsWith("<html")
                    || lowerText.startsWith("{")
                    || lowerText.startsWith("[");

            if (hls || (hlsMime && !htmlOrJson)) {
                if (!hls && prefix.length > 0) {
                    result.message = "HLS 响应缺少有效播放列表标记";
                    return result;
                }
                result.ready = true;
                result.type = text.indexOf("#EXT-X-STREAM-INF") >= 0 ? "HLS Master" : "HLS";
                result.message = "已验证 HLS";
                return result;
            }
            if (dash || (dashMime && !htmlOrJson)) {
                if (!dash && prefix.length > 0) {
                    result.message = "DASH 响应缺少有效 MPD";
                    return result;
                }
                result.ready = true;
                result.type = "DASH";
                result.message = "已验证 DASH";
                return result;
            }
            if (htmlOrJson) {
                result.message = "返回的是网页或接口数据，不是视频流";
                return result;
            }
            if (isTransportStreamUrl(url) && fromSegmentLikeResponse(result.contentType, prefix)) {
                result.message = "检测到单个视频分片，不作为主播放源";
                return result;
            }
            if (videoMime || binaryVideo) {
                if (result.contentLength > 0 && result.contentLength < 512 && !binaryVideo) {
                    result.message = "媒体响应过小";
                    return result;
                }
                result.ready = true;
                result.type = sniffedMediaType(result.contentType, result.finalUrl, prefix, type);
                result.message = "已验证视频流";
                return result;
            }

            result.message = TextUtils.isEmpty(result.contentType)
                    ? "无法确认这是可播放媒体"
                    : "不支持的响应类型：" + result.contentType;
            return result;
        } catch (Exception error) {
            result.message = TextUtils.isEmpty(error.getMessage()) ? "媒体校验失败" : error.getMessage();
            return result;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            if (response != null) response.close();
        }
    }

    private void applyValidationLocked(Candidate candidate, ValidationResult result) {
        candidate.validatedAt = System.currentTimeMillis();
        candidate.validationState = result.ready ? "ready" : "failed";
        candidate.validationMessage = safe(result.message);
        candidate.contentType = safe(result.contentType);
        if (!TextUtils.isEmpty(result.type)) candidate.type = result.type;
        if (result.ready && !TextUtils.isEmpty(result.finalUrl)) {
            candidate.url = result.finalUrl;
            candidate.host = safe(Uri.parse(candidate.url).getHost());
            addPlaybackHeaders(candidate.headers, candidate.url);
        }
    }

    private int readyCandidateCountLocked() {
        int count = 0;
        for (Candidate candidate : candidates.values()) {
            if ("ready".equals(candidate.validationState)) count++;
        }
        return count;
    }

    private int validatingCandidateCountLocked() {
        int count = 0;
        for (Candidate candidate : candidates.values()) {
            if ("validating".equals(candidate.validationState)) count++;
        }
        return count;
    }

    private boolean isManifestUrl(String url) {
        String lower = safe(url).toLowerCase(Locale.US);
        return lower.contains(".m3u8") || lower.contains("format=m3u8") || lower.contains(".mpd");
    }

    private boolean isTransportStreamUrl(String url) {
        return safe(url).toLowerCase(Locale.US).matches(".*\\.(ts|m2ts)(\\?.*)?$");
    }

    private boolean fromSegmentLikeResponse(String contentType, byte[] prefix) {
        String type = safe(contentType).toLowerCase(Locale.US);
        if (type.contains("mp2t")) return true;
        return prefix != null && prefix.length > 188
                && (prefix[0] & 0xff) == 0x47 && (prefix[188] & 0xff) == 0x47;
    }

    private byte[] readPrefix(InputStream input, int limit) throws IOException {
        byte[] out = new byte[limit];
        int offset = 0;
        while (offset < limit) {
            int read = input.read(out, offset, limit - offset);
            if (read < 0) break;
            if (read == 0) continue;
            offset += read;
        }
        if (offset == out.length) return out;
        byte[] trimmed = new byte[offset];
        System.arraycopy(out, 0, trimmed, 0, offset);
        return trimmed;
    }

    private long parseContentLength(String value) {
        try {
            return TextUtils.isEmpty(value) ? -1L : Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private boolean hasVideoSignature(byte[] bytes) {
        if (bytes == null) return false;
        if (bytes.length >= 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') return true;
        if (bytes.length >= 4 && (bytes[0] & 0xff) == 0x1a && (bytes[1] & 0xff) == 0x45
                && (bytes[2] & 0xff) == 0xdf && (bytes[3] & 0xff) == 0xa3) return true;
        if (bytes.length >= 3 && bytes[0] == 'F' && bytes[1] == 'L' && bytes[2] == 'V') return true;
        return bytes.length > 188 && (bytes[0] & 0xff) == 0x47 && (bytes[188] & 0xff) == 0x47;
    }

    private String sniffedMediaType(String contentType, String url, byte[] prefix, String fallback) {
        String mime = safe(contentType).toLowerCase(Locale.US);
        String lowerUrl = safe(url).toLowerCase(Locale.US);
        if (mime.contains("webm") || lowerUrl.contains(".webm")) return "WebM";
        if (mime.contains("matroska") || lowerUrl.contains(".mkv")) return "MKV";
        if (mime.contains("flv") || lowerUrl.contains(".flv")) return "FLV";
        if (mime.contains("mp4") || lowerUrl.contains(".mp4") || lowerUrl.contains(".m4v")
                || (prefix != null && prefix.length >= 8 && prefix[4] == 'f' && prefix[5] == 't'
                && prefix[6] == 'y' && prefix[7] == 'p')) return "MP4";
        return TextUtils.isEmpty(fallback) ? "Video" : fallback;
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
        boolean fromDom;
        long validatedAt;
        String validationState = "validating";
        String validationMessage = "";
        String contentType = "";
        final Map<String, String> headers = new HashMap<>();

        JSONObject toPublicJson() throws Exception {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("type", type);
            obj.put("host", host);
            obj.put("score", score);
            obj.put("validationState", validationState);
            obj.put("validationMessage", validationMessage);
            obj.put("displayUrl", url.length() > 180 ? url.substring(0, 180) + "..." : url);
            return obj;
        }
    }

    private static final class ValidationResult {
        boolean ready;
        boolean retryWithoutRange;
        int httpCode;
        long contentLength = -1L;
        String finalUrl = "";
        String contentType = "";
        String type = "";
        String message = "";
    }
}
