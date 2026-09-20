package com.android.tvremoteime.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.android.tvremoteime.IMEService;
import com.android.tvremoteime.VideoPlayHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import player.XLVideoPlayActivity;

/**
 * Owns the media-browser playback session independently from HTTP processor instances.
 * The session survives process restarts and is also the bridge between the web API and
 * XLVideoPlayActivity without creating a reverse dependency from ijkplayer to IMEService.
 */
public class MediaPlaybackManager implements XLVideoPlayActivity.PlaybackLifecycleListener {
    private static final String PREFS = "media_playback";
    private static final String CURRENT_SESSION = "current_session";
    private static final long HISTORY_WRITE_INTERVAL_MS = 5000L;
    private static final long FALLBACK_DEADLINE_MS = 15000L;
    private static final long NEXT_PRELOAD_WINDOW_MS = 5 * 60 * 1000L;
    private static MediaPlaybackManager instance;

    private final Context context;
    private final SharedPreferences preferences;
    private final MediaConfigManager configManager;
    private final MediaLibraryStore libraryStore;
    private final MediaSourceQualityStore qualityStore;
    private final ExecutorService preloadExecutor = Executors.newSingleThreadExecutor();

    private JSONObject session;
    private long lastHistoryWrite;
    private boolean advancing;
    private boolean awaitingPrepared;
    private boolean outroTriggered;
    private PlaybackTarget preloadedNext;
    private String preloadedNextKey = "";
    private long preloadGeneration;
    private static final long MIN_PLAYABLE_WINDOW_MS = 5000L;

    private MediaPlaybackManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.configManager = new MediaConfigManager(this.context);
        this.libraryStore = new MediaLibraryStore(this.context);
        this.qualityStore = new MediaSourceQualityStore(this.context);
        this.session = readSession();
        XLVideoPlayActivity.setPlaybackLifecycleListener(this);
    }

    public static synchronized MediaPlaybackManager get(Context context) {
        if (instance == null) instance = new MediaPlaybackManager(context);
        else XLVideoPlayActivity.setPlaybackLifecycleListener(instance);
        return instance;
    }

    public JSONObject play(Map<String, String> params) throws Exception {
        PlaybackTarget target = buildInitialTarget(params);
        activateTarget(target, params, true);
        VideoPlayHelper.playUrl(context, target.url, 0, false, displayTitle(target));
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("playUrl", target.url);
        result.put("sourceKey", target.source.key);
        result.put("sourceName", target.source.name);
        result.put("fallback", target.fallback);
        return result;
    }

    public synchronized JSONObject snapshot() {
        return cloneObject(session);
    }

    public void decorateStatus(JSONObject result) {
        JSONObject current = snapshot();
        try {
            boolean hasSession = current.length() > 0 && !TextUtils.isEmpty(current.optString("mediaName"));
            result.put("hasSession", hasSession);
            if (!hasSession) return;
            result.put("sourceKey", current.optString("sourceKey"));
            result.put("sourceName", current.optString("sourceName"));
            result.put("mediaId", current.optString("mediaId"));
            result.put("mediaName", current.optString("mediaName"));
            result.put("pic", current.optString("pic"));
            result.put("episode", current.optString("episode"));
            result.put("flag", current.optString("flag"));
            result.put("playId", current.optString("playId"));
            result.put("routes", current.optJSONArray("routes") == null ? new JSONArray() : current.optJSONArray("routes"));
            int episodeIndex = current.optInt("episodeIndex", -1);
            JSONArray episodes = current.optJSONArray("episodes");
            int episodeCount = episodes == null ? 0 : episodes.length();
            result.put("episodeIndex", episodeIndex);
            result.put("episodeCount", episodeCount);
            result.put("canPrev", episodeIndex > 0);
            result.put("canNext", episodeIndex >= 0 && episodeIndex + 1 < episodeCount);
            result.put("queue", buildQueue(episodes, episodeIndex));
            if (episodes != null && episodeIndex >= 0 && episodeIndex + 1 < episodes.length()) {
                JSONObject next = episodes.optJSONObject(episodeIndex + 1);
                if (next != null) result.put("nextEpisode", next.optString("name"));
            }
            result.put("opening", current.optLong("opening", 0));
            result.put("ending", current.optLong("ending", 0));
            if (!result.optBoolean("active")) {
                result.put("position", current.optLong("position", 0));
                result.put("duration", current.optLong("duration", 0));
                result.put("playing", false);
            }
        } catch (Exception ignored) {
        }
    }

    public JSONObject updateMarker(String action, String deltaRaw) throws Exception {
        XLVideoPlayActivity.WebPlaybackStatus status = XLVideoPlayActivity.getWebPlaybackStatus();
        synchronized (this) {
            if (session.length() == 0) throw new Exception("当前没有可设置的播放内容");
            long duration = status.duration > 0 ? status.duration : Math.max(0, session.optLong("duration", 0));
            if ("opening".equals(action)) {
                if (duration <= 0) throw new Exception("当前视频还没有可用的总时长");
                session.put("opening", Math.max(0, status.position));
            } else if ("ending".equals(action)) {
                if (duration <= 0) throw new Exception("当前视频还没有可用的总时长");
                session.put("ending", Math.max(0, duration - status.position));
            } else if ("opening-adjust".equals(action) || "ending-adjust".equals(action)) {
                long delta = parseMarkerDelta(deltaRaw);
                String key = "opening-adjust".equals(action) ? "opening" : "ending";
                session.put(key, Math.max(0, session.optLong(key, 0) + delta));
            } else if ("clear".equals(action)) {
                session.put("opening", 0);
                session.put("ending", 0);
            } else {
                throw new Exception("未知的跳过设置");
            }
            normalizeMarkersLocked(duration);
            persistLocked(true);
            return cloneObject(session);
        }
    }

    private long parseMarkerDelta(String deltaRaw) {
        try {
            long delta = Long.parseLong(deltaRaw == null ? "0" : deltaRaw);
            return Math.max(-10000L, Math.min(10000L, delta));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void normalizeMarkersLocked(long durationMs) throws Exception {
        long opening = Math.max(0, session.optLong("opening", 0));
        long ending = Math.max(0, session.optLong("ending", 0));
        if (durationMs > 0) {
            long maxCombined = Math.max(0, durationMs - MIN_PLAYABLE_WINDOW_MS);
            opening = Math.min(opening, maxCombined);
            ending = Math.min(ending, maxCombined);
            if (opening + ending > maxCombined) {
                ending = Math.max(0, maxCombined - opening);
            }
        }
        session.put("opening", opening);
        session.put("ending", ending);
    }

    public JSONObject playAdjacent(int delta) throws Exception {
        PlaybackTarget target = adjacentTarget(delta);
        if (target == null) throw new Exception(delta < 0 ? "已经是第一集" : "已经是最后一集");
        return playQueueTarget(target);
    }

    public JSONObject playEpisodeAt(int index) throws Exception {
        JSONObject current = snapshot();
        PlaybackTarget target = queueTarget(current, index);
        if (target == null) throw new Exception("找不到这个剧集");
        return playQueueTarget(target);
    }

    public JSONObject switchRoute(String flag) throws Exception {
        JSONObject current = snapshot();
        if (current.length() == 0 || TextUtils.isEmpty(current.optString("mediaName"))) {
            throw new Exception("当前没有可切换线路的播放内容");
        }
        flag = safe(flag);
        if (TextUtils.equals(flag, current.optString("flag"))) return switchResult(current);

        MediaSource source = requireSource(current.optString("sourceKey"));
        if (TextUtils.isEmpty(current.optString("mediaId")) || source.indexs == 1) {
            throw new Exception("当前内容无法重新加载线路");
        }
        MediaDetail detail = detail(source, current.optString("mediaId"));
        MediaEpisode episode = findEpisodeInRoute(detail, flag, current.optString("episode"), current.optInt("episodeIndex", -1));
        if (episode == null) throw new Exception("这条线路没有匹配到当前剧集");

        PlaybackTarget target = createTarget(source, detail, episode, null, false);
        target.url = resolve(source, episode.flag, episode.playId);
        if (TextUtils.isEmpty(target.url)) throw new Exception("这条线路无法解析播放地址");
        target.resumePosition = currentPlaybackPosition(current);
        activateTarget(target, null, false);
        VideoPlayHelper.playUrl(context, target.url, 0, false, displayTitle(target));
        return switchResult(snapshot());
    }

    public JSONObject switchSource(String sourceKey) throws Exception {
        JSONObject current = snapshot();
        if (current.length() == 0 || TextUtils.isEmpty(current.optString("mediaName"))) {
            throw new Exception("当前没有可切换来源的播放内容");
        }
        MediaSource source = requireSource(sourceKey);
        if (TextUtils.equals(source.key, current.optString("sourceKey"))) return switchResult(current);
        if (!source.searchable || source.indexs == 1) throw new Exception("这个源暂不支持播放中切换");

        PlaybackTarget target = targetFromAlternateSource(source, current.optString("mediaName"),
                current.optString("episode"), current.optInt("episodeIndex", -1));
        if (target == null || TextUtils.isEmpty(target.url)) throw new Exception("这个源没有找到可播放的同名内容");
        target.fallback = false;
        target.resumePosition = currentPlaybackPosition(current);
        activateTarget(target, null, false);
        VideoPlayHelper.playUrl(context, target.url, 0, false, displayTitle(target));
        return switchResult(snapshot());
    }

    private JSONObject switchResult(JSONObject current) throws Exception {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("sourceKey", current.optString("sourceKey"));
        result.put("sourceName", current.optString("sourceName"));
        result.put("flag", current.optString("flag"));
        result.put("episode", current.optString("episode"));
        return result;
    }

    private JSONObject playQueueTarget(PlaybackTarget target) throws Exception {
        activateTarget(target, null, false);
        VideoPlayHelper.playUrl(context, target.url, 0, false, displayTitle(target));
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("episode", target.episode == null ? "" : safe(target.episode.name));
        result.put("sourceKey", target.source.key);
        result.put("sourceName", target.source.name);
        result.put("flag", target.episode == null ? "" : safe(target.episode.flag));
        result.put("fallback", target.fallback);
        return result;
    }

    private JSONArray buildQueue(JSONArray episodes, int currentIndex) throws Exception {
        JSONArray queue = new JSONArray();
        if (episodes == null || episodes.length() == 0) return queue;
        int start = Math.max(0, currentIndex - 1);
        int end = Math.min(episodes.length(), Math.max(start + 1, currentIndex + 5));
        for (int i = start; i < end; i++) {
            JSONObject episode = episodes.optJSONObject(i);
            if (episode == null) continue;
            JSONObject item = new JSONObject();
            item.put("index", i);
            item.put("name", episode.optString("name"));
            item.put("current", i == currentIndex);
            queue.put(item);
        }
        return queue;
    }

    public JSONObject resumeCurrent() throws Exception {
        JSONObject current = snapshot();
        if (current.length() == 0 || TextUtils.isEmpty(current.optString("mediaName"))) {
            throw new Exception("没有可继续播放的内容");
        }
        PlaybackTarget target = targetFromSession(current);
        if (target == null || TextUtils.isEmpty(target.url)) {
            target = fallbackForSession(true);
            if (target == null) throw new Exception("当前源无法继续播放，也没有找到可用的备用源");
            target.fallback = true;
        }
        target.resumePosition = Math.max(0, current.optLong("position", 0));
        activateTarget(target, null, true);
        VideoPlayHelper.playUrl(context, target.url, 0, false, displayTitle(target));
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("sourceKey", target.source.key);
        result.put("sourceName", target.source.name);
        result.put("fallback", target.fallback);
        return result;
    }

    @Override
    public int onPrepared(int durationMs) {
        synchronized (this) {
            awaitingPrepared = false;
            if (session.length() == 0) return 0;
            try {
                qualityStore.recordSuccess(session.optString("sourceKey"));
                session.put("duration", Math.max(0, durationMs));
                normalizeMarkersLocked(durationMs);
                long opening = Math.max(0, session.optLong("opening", 0));
                long resume = Math.max(0, session.optLong("resumePosition", 0));
                long start = Math.max(opening, resume);
                if (durationMs > 0 && start >= durationMs - 10000L) start = opening < durationMs - 10000L ? opening : 0;
                session.put("position", start);
                session.put("resumePosition", 0);
                session.put("updatedAt", System.currentTimeMillis());
                persistLocked(true);
                return (int) Math.min(Integer.MAX_VALUE, Math.max(0, start));
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    @Override
    public void onProgress(int positionMs, int durationMs, boolean playing) {
        boolean shouldAdvance = false;
        synchronized (this) {
            if (session.length() == 0 || awaitingPrepared) return;
            try {
                session.put("position", Math.max(0, positionMs));
                if (durationMs > 0) session.put("duration", durationMs);
                session.put("playing", playing);
                session.put("updatedAt", System.currentTimeMillis());
                long now = System.currentTimeMillis();
                if (now - lastHistoryWrite >= HISTORY_WRITE_INTERVAL_MS) persistLocked(false);
                long ending = Math.max(0, session.optLong("ending", 0));
                int knownDuration = session.optInt("duration", durationMs);
                if (knownDuration > 0 && positionMs > 0 && hasAdjacentLocked(1)
                        && positionMs + NEXT_PRELOAD_WINDOW_MS >= knownDuration) {
                    scheduleNextPreloadLocked();
                }
                shouldAdvance = ending > 0 && knownDuration > 0 && positionMs > 0
                        && positionMs + ending >= knownDuration && hasAdjacentLocked(1) && !advancing && !outroTriggered;
                if (shouldAdvance) outroTriggered = true;
            } catch (Exception ignored) {
            }
        }
        if (shouldAdvance) queueAdjacent(1, false);
    }

    @Override
    public boolean onCompletion() {
        synchronized (this) {
            if (session.length() == 0 || !hasAdjacentLocked(1) || advancing) {
                try {
                    int duration = session.optInt("duration", 0);
                    if (duration > 0) session.put("position", duration);
                    session.put("playing", false);
                    persistLocked(true);
                } catch (Exception ignored) {
                }
                return false;
            }
        }
        return queueAdjacent(1, true);
    }

    @Override
    public boolean onPlaybackError() {
        synchronized (this) {
            if (session.length() == 0 || advancing || TextUtils.isEmpty(session.optString("mediaName"))) return false;
            qualityStore.recordFailure(session.optString("sourceKey"));
            advancing = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (MediaPlaybackManager.this) {
                        session.put("failedFlags", addFailedFlag(session.optJSONArray("failedFlags"), session.optString("flag")));
                        persistLocked(false);
                    }
                    PlaybackTarget target = fallbackForSession(true);
                    if (target == null) {
                        XLVideoPlayActivity.finishCurrentPlayback();
                        return;
                    }
                    JSONObject current = snapshot();
                    target.resumePosition = Math.max(0, current.optLong("position", 0));
                    activateTarget(target, null, false);
                    VideoPlayHelper.playUrl(context, target.url, 0, false, displayTitle(target));
                } catch (Exception e) {
                    Log.e(IMEService.TAG, "media playback fallback failed", e);
                    XLVideoPlayActivity.finishCurrentPlayback();
                } finally {
                    synchronized (MediaPlaybackManager.this) {
                        advancing = false;
                    }
                }
            }
        }, "media-playback-fallback").start();
        return true;
    }

    @Override
    public void onStopped(int positionMs, int durationMs) {
        synchronized (this) {
            if (session.length() == 0 || awaitingPrepared) return;
            try {
                if (positionMs >= 0) session.put("position", positionMs);
                if (durationMs > 0) session.put("duration", durationMs);
                session.put("playing", false);
                persistLocked(true);
            } catch (Exception ignored) {
            }
        }
    }

    private PlaybackTarget buildInitialTarget(Map<String, String> params) throws Exception {
        MediaSource source = requireSource(params.get("sourceKey"));
        MediaDetail detail = null;
        String mediaId = safe(params.get("mediaId"));
        if (!TextUtils.isEmpty(mediaId) && source.indexs != 1) {
            try {
                detail = detail(source, mediaId);
            } catch (Exception e) {
                Log.w(IMEService.TAG, "media detail refresh before playback failed: " + e.getMessage());
            }
        }

        MediaEpisode episode = findEpisode(detail, params.get("flag"), params.get("playId"), params.get("episode"), -1);
        if (episode == null) {
            episode = new MediaEpisode();
            episode.flag = safe(params.get("flag"));
            episode.playId = safe(params.get("playId"));
            episode.name = safe(params.get("episode"));
        }
        PlaybackTarget target = createTarget(source, detail, episode, params, false);
        try {
            target.url = resolve(source, episode.flag, episode.playId);
        } catch (Exception e) {
            Log.w(IMEService.TAG, "requested media source failed, trying fallback: " + e.getMessage());
        }
        if (!TextUtils.isEmpty(target.url)) return target;

        PlaybackTarget routeFallback = findAlternateRoute(source, detail, episode.flag, episode.name,
                target.episodeIndex, addFailedFlag(null, episode.flag));
        if (routeFallback != null) {
            routeFallback.fallback = true;
            return routeFallback;
        }

        String title = detail != null ? detail.name : safe(params.get("mediaName"));
        PlaybackTarget fallback = findFallback(title, episode.name, target.episodeIndex, source.key);
        if (fallback == null) throw new Exception("当前源无法解析播放地址，且没有找到可用的备用源");
        fallback.fallback = true;
        return fallback;
    }

    private PlaybackTarget createTarget(MediaSource source, MediaDetail detail, MediaEpisode episode,
                                        Map<String, String> params, boolean fallback) throws Exception {
        PlaybackTarget target = new PlaybackTarget();
        target.source = source;
        target.detail = detail;
        target.episode = episode;
        target.fallback = fallback;
        target.mediaId = detail != null && !TextUtils.isEmpty(detail.id) ? detail.id : safe(params == null ? null : params.get("mediaId"));
        target.mediaName = detail != null && !TextUtils.isEmpty(detail.name) ? detail.name : safe(params == null ? null : params.get("mediaName"));
        target.pic = detail != null && !TextUtils.isEmpty(detail.pic) ? detail.pic : safe(params == null ? null : params.get("pic"));
        target.score = detail != null && !TextUtils.isEmpty(detail.score) ? detail.score : safe(params == null ? null : params.get("score"));
        target.remark = detail != null && !TextUtils.isEmpty(detail.remark) ? detail.remark : safe(params == null ? null : params.get("remark"));
        target.year = detail == null ? "" : safe(detail.year);
        target.type = detail == null ? "" : safe(detail.type);
        target.routeEpisodes = routeEpisodes(detail, episode == null ? "" : episode.flag);
        target.episodeIndex = episodeIndex(target.routeEpisodes, episode == null ? "" : episode.playId);
        target.routes = buildRoutes(detail, episode == null ? "" : episode.flag);
        return target;
    }

    private synchronized void activateTarget(PlaybackTarget target, Map<String, String> params, boolean allowResume) throws Exception {
        if (target == null || target.source == null || TextUtils.isEmpty(target.url)) throw new Exception("无法解析播放地址");
        JSONObject old = libraryStore.getHistoryItem(target.source.key, target.mediaId);
        long opening = old == null ? session.optLong("opening", 0) : old.optLong("opening", 0);
        long ending = old == null ? session.optLong("ending", 0) : old.optLong("ending", 0);
        long resume = 0;
        if (allowResume && old != null && TextUtils.equals(old.optString("playId"), safe(target.episode.playId))) {
            resume = Math.max(0, old.optLong("position", 0));
            long oldDuration = Math.max(0, old.optLong("duration", 0));
            if (oldDuration > 0 && (resume >= oldDuration - 60000L || resume * 100L / oldDuration >= 95L)) resume = 0;
        }
        if (target.resumePosition > 0) resume = target.resumePosition;

        JSONObject next = new JSONObject();
        next.put("sourceKey", safe(target.source.key));
        next.put("sourceName", safe(target.source.name));
        next.put("mediaId", safe(target.mediaId));
        next.put("mediaName", safe(target.mediaName));
        next.put("pic", safe(target.pic));
        next.put("score", safe(target.score));
        next.put("remark", safe(target.remark));
        next.put("year", safe(target.year));
        next.put("type", safe(target.type));
        next.put("episode", target.episode == null ? "" : safe(target.episode.name));
        next.put("flag", target.episode == null ? "" : safe(target.episode.flag));
        next.put("playId", target.episode == null ? "" : safe(target.episode.playId));
        next.put("episodes", target.routeEpisodes == null ? new JSONArray() : target.routeEpisodes);
        next.put("routes", target.routes == null ? new JSONArray() : target.routes);
        next.put("failedFlags", target.failedFlags == null ? new JSONArray() : target.failedFlags);
        next.put("episodeIndex", target.episodeIndex);
        next.put("opening", Math.max(0, opening));
        next.put("ending", Math.max(0, ending));
        next.put("position", 0);
        next.put("duration", 0);
        next.put("resumePosition", resume);
        next.put("playing", true);
        next.put("playUrl", target.url);
        next.put("updatedAt", System.currentTimeMillis());
        session = next;
        awaitingPrepared = true;
        outroTriggered = false;
        lastHistoryWrite = 0;
        clearNextPreloadLocked();
        persistLocked(true);
    }

    private PlaybackTarget adjacentTarget(int delta) throws Exception {
        JSONObject current;
        synchronized (this) {
            current = cloneObject(session);
        }
        int nextIndex = current.optInt("episodeIndex", -1) + delta;
        return queueTarget(current, nextIndex);
    }

    private PlaybackTarget queueTarget(JSONObject current, int nextIndex) throws Exception {
        PlaybackTarget preloaded = consumePreloadedTarget(current, nextIndex);
        if (preloaded != null) return preloaded;

        PlaybackTarget target = queueTargetBase(current, nextIndex);
        if (target == null) return null;
        MediaSource source = target.source;
        MediaEpisode episode = target.episode;
        try {
            target.url = resolve(source, episode.flag, episode.playId);
        } catch (Exception e) {
            Log.w(IMEService.TAG, "next episode failed on current source: " + e.getMessage());
        }
        if (!TextUtils.isEmpty(target.url)) return target;
        try {
            MediaDetail detail = source.indexs == 1 ? null : detail(source, target.mediaId);
            PlaybackTarget routeFallback = findAlternateRoute(source, detail, episode.flag, episode.name,
                    nextIndex, addFailedFlag(target.failedFlags, episode.flag));
            if (routeFallback != null) {
                routeFallback.fallback = true;
                return routeFallback;
            }
        } catch (Exception e) {
            Log.w(IMEService.TAG, "next episode same-source fallback failed: " + e.getMessage());
        }
        PlaybackTarget fallback = findFallback(target.mediaName, episode.name, nextIndex, source.key);
        if (fallback != null) fallback.fallback = true;
        return fallback;
    }

    private PlaybackTarget queueTargetBase(JSONObject current, int nextIndex) throws Exception {
        JSONArray episodes = current.optJSONArray("episodes");
        if (episodes == null || nextIndex < 0 || nextIndex >= episodes.length()) return null;
        JSONObject epJson = episodes.optJSONObject(nextIndex);
        if (epJson == null) return null;

        MediaSource source = requireSource(current.optString("sourceKey"));
        MediaEpisode episode = episodeFromJson(epJson);
        PlaybackTarget target = new PlaybackTarget();
        target.source = source;
        target.episode = episode;
        target.mediaId = current.optString("mediaId");
        target.mediaName = current.optString("mediaName");
        target.pic = current.optString("pic");
        target.score = current.optString("score");
        target.remark = current.optString("remark");
        target.year = current.optString("year");
        target.type = current.optString("type");
        target.routeEpisodes = episodes;
        target.routes = current.optJSONArray("routes");
        target.failedFlags = current.optJSONArray("failedFlags");
        target.episodeIndex = nextIndex;
        return target;
    }

    private synchronized PlaybackTarget consumePreloadedTarget(JSONObject current, int nextIndex) {
        String key = preloadKey(current, nextIndex);
        if (TextUtils.isEmpty(key) || preloadedNext == null || !TextUtils.equals(key, preloadedNextKey)) return null;
        PlaybackTarget target = preloadedNext;
        clearNextPreloadLocked();
        return target;
    }

    private void scheduleNextPreloadLocked() {
        final JSONObject current = cloneObject(session);
        final int nextIndex = current.optInt("episodeIndex", -1) + 1;
        final String key = preloadKey(current, nextIndex);
        if (!TextUtils.isEmpty(key) && TextUtils.equals(key, preloadedNextKey)) return;
        final long generation = ++preloadGeneration;
        preloadedNext = null;
        preloadedNextKey = key;
        if (TextUtils.isEmpty(key)) return;

        preloadExecutor.submit(new Runnable() {
            @Override
            public void run() {
                PlaybackTarget target = null;
                try {
                    target = queueTargetBase(current, nextIndex);
                    if (target != null && target.episode != null) {
                        target.url = resolve(target.source, target.episode.flag, target.episode.playId);
                        if (TextUtils.isEmpty(target.url)) target = null;
                    }
                } catch (Exception e) {
                    Log.w(IMEService.TAG, "next episode preload failed: " + e.getMessage());
                    target = null;
                }
                synchronized (MediaPlaybackManager.this) {
                    if (generation != preloadGeneration || !TextUtils.equals(key, preloadedNextKey)) return;
                    preloadedNext = target;
                }
            }
        });
    }

    private void clearNextPreloadLocked() {
        preloadGeneration++;
        preloadedNext = null;
        preloadedNextKey = "";
    }

    private String preloadKey(JSONObject current, int nextIndex) {
        if (current == null || nextIndex < 0) return "";
        JSONArray episodes = current.optJSONArray("episodes");
        if (episodes == null || nextIndex >= episodes.length()) return "";
        JSONObject next = episodes.optJSONObject(nextIndex);
        if (next == null || TextUtils.isEmpty(next.optString("playId"))) return "";
        return current.optString("sourceKey") + "\n"
                + current.optString("mediaId") + "\n"
                + current.optString("flag") + "\n"
                + current.optString("playId") + "\n"
                + nextIndex + "\n"
                + next.optString("playId");
    }

    private boolean queueAdjacent(final int delta, boolean fromCompletion) {
        synchronized (this) {
            if (advancing || !hasAdjacentLocked(delta)) return false;
            advancing = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PlaybackTarget target = adjacentTarget(delta);
                    if (target == null) {
                        XLVideoPlayActivity.finishCurrentPlayback();
                        return;
                    }
                    activateTarget(target, null, false);
                    VideoPlayHelper.playUrl(context, target.url, 0, false, displayTitle(target));
                } catch (Exception e) {
                    Log.e(IMEService.TAG, "automatic episode advance failed", e);
                    XLVideoPlayActivity.finishCurrentPlayback();
                } finally {
                    synchronized (MediaPlaybackManager.this) {
                        advancing = false;
                    }
                }
            }
        }, fromCompletion ? "media-auto-next" : "media-outro-next").start();
        return true;
    }

    private PlaybackTarget fallbackForSession(boolean excludeCurrent) throws Exception {
        JSONObject current = snapshot();
        try {
            MediaSource currentSource = requireSource(current.optString("sourceKey"));
            if (currentSource.indexs != 1 && !TextUtils.isEmpty(current.optString("mediaId"))) {
                MediaDetail detail = detail(currentSource, current.optString("mediaId"));
                JSONArray failedFlags = addFailedFlag(current.optJSONArray("failedFlags"), current.optString("flag"));
                PlaybackTarget routeFallback = findAlternateRoute(currentSource, detail, current.optString("flag"),
                        current.optString("episode"), current.optInt("episodeIndex", -1), failedFlags);
                if (routeFallback != null) {
                    routeFallback.fallback = true;
                    routeFallback.failedFlags = failedFlags;
                    return routeFallback;
                }
            }
        } catch (Exception e) {
            Log.w(IMEService.TAG, "same-source line fallback failed: " + e.getMessage());
        }
        String exclude = excludeCurrent ? current.optString("sourceKey") : "";
        return findFallback(current.optString("mediaName"), current.optString("episode"),
                current.optInt("episodeIndex", -1), exclude);
    }

    private PlaybackTarget targetFromSession(JSONObject current) throws Exception {
        MediaSource source = requireSource(current.optString("sourceKey"));
        MediaEpisode episode = new MediaEpisode();
        episode.name = current.optString("episode");
        episode.flag = current.optString("flag");
        episode.playId = current.optString("playId");
        PlaybackTarget target = new PlaybackTarget();
        target.source = source;
        target.episode = episode;
        target.mediaId = current.optString("mediaId");
        target.mediaName = current.optString("mediaName");
        target.pic = current.optString("pic");
        target.score = current.optString("score");
        target.remark = current.optString("remark");
        target.year = current.optString("year");
        target.type = current.optString("type");
        target.routeEpisodes = current.optJSONArray("episodes");
        target.routes = current.optJSONArray("routes");
        target.failedFlags = current.optJSONArray("failedFlags");
        target.episodeIndex = current.optInt("episodeIndex", -1);
        try {
            target.url = resolve(source, episode.flag, episode.playId);
        } catch (Exception e) {
            Log.w(IMEService.TAG, "resume current source failed: " + e.getMessage());
        }
        return target;
    }

    private PlaybackTarget findFallback(final String title, final String episodeName, final int episodeIndex,
                                        String excludeSourceKey) throws Exception {
        if (TextUtils.isEmpty(title)) return null;
        List<MediaSource> rawCandidates = new ArrayList<MediaSource>();
        for (MediaSource source : configManager.getSources()) {
            if (!source.isSupported() || !source.searchable || TextUtils.equals(source.key, excludeSourceKey)) continue;
            rawCandidates.add(source);
        }
        final List<MediaSource> candidates = qualityStore.rank(rawCandidates);
        if (candidates.isEmpty()) return null;

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, candidates.size()));
        CompletionService<PlaybackTarget> completion = new ExecutorCompletionService<PlaybackTarget>(executor);
        List<Future<PlaybackTarget>> futures = new ArrayList<Future<PlaybackTarget>>();
        for (final MediaSource source : candidates) {
            futures.add(completion.submit(new Callable<PlaybackTarget>() {
                @Override
                public PlaybackTarget call() {
                    try {
                        return targetFromAlternateSource(source, title, episodeName, episodeIndex);
                    } catch (Exception ignored) {
                        return null;
                    }
                }
            }));
        }
        long deadline = System.currentTimeMillis() + FALLBACK_DEADLINE_MS;
        PlaybackTarget found = null;
        try {
            for (int i = 0; i < candidates.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                Future<PlaybackTarget> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (future == null) break;
                PlaybackTarget candidate = future.get();
                if (candidate != null && !TextUtils.isEmpty(candidate.url)) {
                    found = candidate;
                    break;
                }
            }
        } finally {
            for (Future<PlaybackTarget> future : futures) future.cancel(true);
            executor.shutdownNow();
        }
        return found;
    }

    private PlaybackTarget targetFromAlternateSource(MediaSource source, String title, String episodeName,
                                                      int preferredIndex) throws Exception {
        List<MediaItem> results = search(source, title, true);
        MediaItem match = bestTitleMatch(results, title);
        if (match == null || TextUtils.isEmpty(match.id) || source.indexs == 1) return null;
        MediaDetail detail = detail(source, match.id);
        if (detail == null) return null;
        MediaEpisode episode = findEpisode(detail, null, null, episodeName, preferredIndex);
        if (episode == null) return null;
        PlaybackTarget target = createTarget(source, detail, episode, null, true);
        target.url = resolve(source, episode.flag, episode.playId);
        return TextUtils.isEmpty(target.url) ? null : target;
    }

    private PlaybackTarget findAlternateRoute(MediaSource source, MediaDetail detail, String currentFlag,
                                               String episodeName, int preferredIndex, JSONArray failedFlags) throws Exception {
        if (source == null || detail == null || detail.episodes == null || detail.episodes.isEmpty()) return null;
        List<String> flags = routeFlags(detail);
        if (flags.size() <= 1) return null;
        int currentIndex = flags.indexOf(safe(currentFlag));
        if (currentIndex < 0) currentIndex = 0;
        for (int offset = 1; offset < flags.size(); offset++) {
            String flag = flags.get((currentIndex + offset) % flags.size());
            if (containsString(failedFlags, flag)) continue;
            MediaEpisode episode = findEpisodeInRoute(detail, flag, episodeName, preferredIndex);
            if (episode == null) continue;
            try {
                PlaybackTarget target = createTarget(source, detail, episode, null, true);
                target.url = resolve(source, episode.flag, episode.playId);
                if (!TextUtils.isEmpty(target.url)) {
                    target.failedFlags = failedFlags;
                    return target;
                }
            } catch (Exception e) {
                failedFlags = addFailedFlag(failedFlags, flag);
                Log.w(IMEService.TAG, "alternate line failed: " + flag + " · " + e.getMessage());
            }
        }
        return null;
    }

    private MediaItem bestTitleMatch(List<MediaItem> items, String title) {
        String normalized = normalize(title);
        MediaItem partial = null;
        for (MediaItem item : items) {
            String name = normalize(item.name);
            if (normalized.equals(name)) return item;
            if (partial == null && (name.contains(normalized) || normalized.contains(name))) partial = item;
        }
        return partial;
    }

    private MediaEpisode findEpisode(MediaDetail detail, String flag, String playId, String episodeName, int preferredIndex) {
        if (detail == null || detail.episodes == null || detail.episodes.isEmpty()) return null;
        if (!TextUtils.isEmpty(playId)) {
            for (MediaEpisode episode : detail.episodes) {
                if (TextUtils.equals(playId, episode.playId) && (TextUtils.isEmpty(flag) || TextUtils.equals(flag, episode.flag))) return episode;
            }
        }
        String normalizedEpisode = normalize(episodeName);
        if (!TextUtils.isEmpty(normalizedEpisode)) {
            for (MediaEpisode episode : detail.episodes) {
                if (normalizedEpisode.equals(normalize(episode.name))) return episode;
            }
        }
        if (preferredIndex >= 0) {
            JSONArray route = routeEpisodes(detail, detail.episodes.get(0).flag);
            if (preferredIndex < route.length()) return episodeFromJson(route.optJSONObject(preferredIndex));
        }
        return detail.episodes.get(0);
    }

    private MediaEpisode findEpisodeInRoute(MediaDetail detail, String flag, String episodeName, int preferredIndex) {
        JSONArray route = routeEpisodes(detail, flag);
        if (route.length() == 0) return null;
        String normalizedEpisode = normalize(episodeName);
        if (!TextUtils.isEmpty(normalizedEpisode)) {
            for (int i = 0; i < route.length(); i++) {
                JSONObject obj = route.optJSONObject(i);
                if (obj != null && normalizedEpisode.equals(normalize(obj.optString("name")))) return episodeFromJson(obj);
            }
        }
        if (preferredIndex >= 0 && preferredIndex < route.length()) return episodeFromJson(route.optJSONObject(preferredIndex));
        return episodeFromJson(route.optJSONObject(0));
    }

    private List<String> routeFlags(MediaDetail detail) {
        List<String> flags = new ArrayList<String>();
        if (detail == null || detail.episodes == null) return flags;
        for (MediaEpisode episode : detail.episodes) {
            String flag = safe(episode.flag);
            if (!flags.contains(flag)) flags.add(flag);
        }
        return flags;
    }

    private JSONArray buildRoutes(MediaDetail detail, String currentFlag) throws Exception {
        JSONArray routes = new JSONArray();
        List<String> flags = routeFlags(detail);
        for (String flag : flags) {
            JSONObject item = new JSONObject();
            item.put("flag", flag);
            item.put("name", TextUtils.isEmpty(flag) ? "默认线路" : flag);
            item.put("current", TextUtils.equals(flag, safe(currentFlag)));
            item.put("episodeCount", routeEpisodes(detail, flag).length());
            routes.put(item);
        }
        return routes;
    }

    private JSONArray addFailedFlag(JSONArray source, String flag) {
        JSONArray result = new JSONArray();
        if (source != null) {
            for (int i = 0; i < source.length(); i++) {
                String value = source.optString(i);
                if (!containsString(result, value)) result.put(value);
            }
        }
        String value = safe(flag);
        if (!containsString(result, value)) result.put(value);
        return result;
    }

    private boolean containsString(JSONArray array, String value) {
        if (array == null) return false;
        value = safe(value);
        for (int i = 0; i < array.length(); i++) {
            if (TextUtils.equals(value, array.optString(i))) return true;
        }
        return false;
    }

    private long currentPlaybackPosition(JSONObject current) {
        XLVideoPlayActivity.WebPlaybackStatus status = XLVideoPlayActivity.getWebPlaybackStatus();
        return status.active ? Math.max(0, status.position) : Math.max(0, current.optLong("position", 0));
    }

    private JSONArray routeEpisodes(MediaDetail detail, String flag) {
        JSONArray result = new JSONArray();
        if (detail == null || detail.episodes == null) return result;
        String selectedFlag = flag;
        if (TextUtils.isEmpty(selectedFlag) && !detail.episodes.isEmpty()) selectedFlag = detail.episodes.get(0).flag;
        for (MediaEpisode episode : detail.episodes) {
            if (!TextUtils.equals(safe(selectedFlag), safe(episode.flag))) continue;
            try {
                result.put(episode.toJson());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private int episodeIndex(JSONArray episodes, String playId) {
        if (episodes == null) return -1;
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject episode = episodes.optJSONObject(i);
            if (episode != null && TextUtils.equals(playId, episode.optString("playId"))) return i;
        }
        return episodes.length() > 0 ? 0 : -1;
    }

    private MediaEpisode episodeFromJson(JSONObject obj) {
        if (obj == null) return null;
        MediaEpisode episode = new MediaEpisode();
        episode.name = obj.optString("name");
        episode.playId = obj.optString("playId");
        episode.flag = obj.optString("flag");
        return episode;
    }

    private synchronized boolean hasAdjacentLocked(int delta) {
        JSONArray episodes = session.optJSONArray("episodes");
        int index = session.optInt("episodeIndex", -1) + delta;
        return episodes != null && index >= 0 && index < episodes.length();
    }

    private synchronized void persistLocked(boolean forceHistory) {
        preferences.edit().putString(CURRENT_SESSION, session.toString()).apply();
        long now = System.currentTimeMillis();
        if (!forceHistory && now - lastHistoryWrite < HISTORY_WRITE_INTERVAL_MS) return;
        if (TextUtils.isEmpty(session.optString("mediaId"))) return;
        try {
            JSONObject history = new JSONObject();
            history.put("sourceKey", session.optString("sourceKey"));
            history.put("sourceName", session.optString("sourceName"));
            history.put("id", session.optString("mediaId"));
            history.put("name", session.optString("mediaName"));
            history.put("pic", session.optString("pic"));
            history.put("score", session.optString("score"));
            history.put("remark", session.optString("remark"));
            history.put("year", session.optString("year"));
            history.put("type", session.optString("type"));
            history.put("episode", session.optString("episode"));
            history.put("flag", session.optString("flag"));
            history.put("playId", session.optString("playId"));
            history.put("position", session.optLong("position", 0));
            history.put("duration", session.optLong("duration", 0));
            history.put("opening", session.optLong("opening", 0));
            history.put("ending", session.optLong("ending", 0));
            history.put("updatedAt", session.optLong("updatedAt", now));
            libraryStore.addHistory(history);
            lastHistoryWrite = now;
        } catch (Exception ignored) {
        }
    }

    private JSONObject readSession() {
        try {
            return new JSONObject(preferences.getString(CURRENT_SESSION, "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private MediaSource requireSource(String sourceKey) throws Exception {
        MediaSource source = configManager.getSource(sourceKey);
        if (source == null || !source.isSupported()) throw new Exception("不支持或未找到该媒体源");
        return source;
    }

    private List<MediaItem> search(MediaSource source, String keyword, boolean quick) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).search(keyword, quick);
        return new Type0SourceAdapter(source, configManager.getConfig()).search(keyword);
    }

    private MediaDetail detail(MediaSource source, String id) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).detail(id);
        return new Type0SourceAdapter(source, configManager.getConfig()).detail(id);
    }

    private String resolve(MediaSource source, String flag, String playId) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).resolve(flag, playId);
        return new Type0SourceAdapter(source, configManager.getConfig()).resolve(playId);
    }

    private static JSONObject cloneObject(JSONObject source) {
        try {
            return source == null ? new JSONObject() : new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String displayTitle(PlaybackTarget target) {
        String title = safe(target.mediaName);
        String episode = target.episode == null ? "" : safe(target.episode.name);
        return TextUtils.isEmpty(episode) ? title : title + " · " + episode;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase().replaceAll("[\\s\\p{Punct}·•，。！？：；、【】（）《》]+", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static class PlaybackTarget {
        MediaSource source;
        MediaDetail detail;
        MediaEpisode episode;
        String mediaId;
        String mediaName;
        String pic;
        String score;
        String remark;
        String year;
        String type;
        JSONArray routeEpisodes;
        JSONArray routes;
        JSONArray failedFlags;
        int episodeIndex = -1;
        String url;
        boolean fallback;
        long resumePosition;
    }
}
