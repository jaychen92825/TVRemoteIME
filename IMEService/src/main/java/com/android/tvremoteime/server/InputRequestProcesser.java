package com.android.tvremoteime.server;

import android.content.Context;
import android.view.KeyEvent;

import com.android.tvremoteime.IMEService;
import com.android.tvremoteime.media.MediaPlaybackManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import player.XLVideoPlayActivity;

/**
 * Created by kingt on 2018/1/7.
 */

public class InputRequestProcesser implements RequestProcesser {
    private Context context;
    private RemoteServer remoteServer;

    public InputRequestProcesser(Context context, RemoteServer remoteServer){
        this.context = context;
        this.remoteServer = remoteServer;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if(session.getMethod() == NanoHTTPD.Method.POST){
            switch (fileName) {
                case "/text":
                case "/textLive":
                case "/key":
                case "/keydown":
                case "/keyup":
                case "/player/control":
                case "/player/status":
                case "/player/seek":
                case "/player/speed":
                case "/player/volume":
                case "/player/mute":
                case "/player/stop":
                case "/player/track":
                case "/mouseMove":
                case "/mouseClick":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        RemoteServer.DataReceiver mDataReceiver = remoteServer.getDataReceiver();
        switch (fileName) {
            case "/text":
                if (params.get("text") != null && mDataReceiver != null) {
                    mDataReceiver.onTextReceived(params.get("text"));
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/textLive":
                if (mDataReceiver != null) {
                    //text参数可能为空字符串（用户把控制端输入框删空了），也要同步过去
                    mDataReceiver.onComposingTextReceived(params.get("text") == null ? "" : params.get("text"));
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/key":
                if (params.get("code") != null) {
                    if (dispatchKeyToPlayer(params.get("code"), IMEService.KEY_ACTION_PRESSED)) {
                        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
                    }
                    if (mDataReceiver != null) {
                        mDataReceiver.onKeyEventReceived(params.get("code"), IMEService.KEY_ACTION_PRESSED);
                    }
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/keyup":
                if (params.get("code") != null) {
                    if (dispatchKeyToPlayer(params.get("code"), IMEService.KEY_ACTION_UP)) {
                        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
                    }
                    if (mDataReceiver != null) {
                        mDataReceiver.onKeyEventReceived(params.get("code"), IMEService.KEY_ACTION_UP);
                    }
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/keydown":
                if (params.get("code") != null) {
                    if (dispatchKeyToPlayer(params.get("code"), IMEService.KEY_ACTION_DOWN)) {
                        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
                    }
                    if (mDataReceiver != null) {
                        mDataReceiver.onKeyEventReceived(params.get("code"), IMEService.KEY_ACTION_DOWN);
                    }
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/player/control":
                if (params.get("code") == null || params.get("action") == null) {
                    return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid");
                }
                return RemoteServer.createPlainTextResponse(
                        NanoHTTPD.Response.Status.OK,
                        dispatchDirectPlayerControl(params.get("code"), params.get("action")) ? "handled" : "inactive"
                );
            case "/player/status":
                return playerStatusResponse();
            case "/player/seek":
                return playerSeekResponse(params.get("position"));
            case "/player/speed":
                return playerSpeedResponse(params.get("speed"));
            case "/player/volume":
                return playerVolumeResponse(params.get("volume"));
            case "/player/mute":
                return playerMuteResponse(params.get("muted"));
            case "/player/stop":
                return playerActionResponse(XLVideoPlayActivity.dispatchStopPlayback(), null);
            case "/player/track":
                return playerTrackResponse(params.get("kind"), params.get("index"));
            case "/mouseMove":
                if (mDataReceiver != null) {
                    //单次触控板位移不可能很大，限制一下范围防止畸形/恶意参数导致虚拟光标坐标跳变
                    int dx = clampInt(parseIntSafely(params.get("dx")), -400, 400);
                    int dy = clampInt(parseIntSafely(params.get("dy")), -400, 400);
                    mDataReceiver.onMouseMoveReceived(dx, dy);
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/mouseClick":
                if (mDataReceiver != null) {
                    mDataReceiver.onMouseClickReceived();
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }

    private static boolean dispatchKeyToPlayer(String keyCode, int keyAction){
        int code = parseKeyCode(keyCode);
        if(code == KeyEvent.KEYCODE_UNKNOWN) return false;

        switch (keyAction) {
            case IMEService.KEY_ACTION_PRESSED:
                if(!XLVideoPlayActivity.dispatchRemoteKeyEvent(code, KeyEvent.ACTION_DOWN)) return false;
                XLVideoPlayActivity.dispatchRemoteKeyEvent(code, KeyEvent.ACTION_UP);
                return true;
            case IMEService.KEY_ACTION_DOWN:
                return XLVideoPlayActivity.dispatchRemoteKeyEvent(code, KeyEvent.ACTION_DOWN);
            case IMEService.KEY_ACTION_UP:
                return XLVideoPlayActivity.dispatchRemoteKeyEvent(code, KeyEvent.ACTION_UP);
            default:
                return false;
        }
    }

    private static boolean dispatchDirectPlayerControl(String keyCode, String action){
        int code = parseKeyCode(keyCode);
        if(code == KeyEvent.KEYCODE_UNKNOWN) return false;

        if("press".equalsIgnoreCase(action)) {
            if(!XLVideoPlayActivity.dispatchDirectPlayerControl(code, KeyEvent.ACTION_DOWN)) return false;
            XLVideoPlayActivity.dispatchDirectPlayerControl(code, KeyEvent.ACTION_UP);
            return true;
        }
        if("down".equalsIgnoreCase(action)) {
            return XLVideoPlayActivity.dispatchDirectPlayerControl(code, KeyEvent.ACTION_DOWN);
        }
        if("up".equalsIgnoreCase(action)) {
            return XLVideoPlayActivity.dispatchDirectPlayerControl(code, KeyEvent.ACTION_UP);
        }
        return false;
    }

    private NanoHTTPD.Response playerStatusResponse(){
        XLVideoPlayActivity.WebPlaybackStatus status = XLVideoPlayActivity.getWebPlaybackStatus();
        JSONObject result = new JSONObject();
        try {
            result.put("active", status.active);
            result.put("position", status.position);
            result.put("duration", status.duration);
            result.put("playing", status.playing);
            result.put("speed", status.speed);
            result.put("speedSupported", status.speedSupported);
            result.put("volume", status.volume);
            result.put("muted", status.muted);
            result.put("audioTracks", trackArray(status.audioTracks));
            result.put("subtitleTracks", trackArray(status.subtitleTracks));
            MediaPlaybackManager.get(context).decorateStatus(result);
        } catch (JSONException ignored) {
        }
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, result.toString());
    }

    private static NanoHTTPD.Response playerSeekResponse(String value){
        int position = parseIntSafely(value);
        if (value == null || position < 0) {
            return playerActionResponse(false, "invalid position");
        }
        return playerActionResponse(XLVideoPlayActivity.dispatchAbsoluteSeek(position), null);
    }

    private static NanoHTTPD.Response playerSpeedResponse(String value){
        float speed = parseFloatSafely(value);
        if (Float.isNaN(speed) || speed < 0.5f || speed > 3.0f) {
            return playerActionResponse(false, "invalid speed");
        }
        return playerActionResponse(XLVideoPlayActivity.dispatchPlaybackSpeed(speed), null);
    }

    private static NanoHTTPD.Response playerVolumeResponse(String value){
        if (value == null) return playerActionResponse(false, "invalid volume");
        int volume = parseIntSafely(value);
        if (volume < 0 || volume > 100) return playerActionResponse(false, "invalid volume");
        return playerActionResponse(XLVideoPlayActivity.dispatchVolumePercent(volume), null);
    }

    private static NanoHTTPD.Response playerMuteResponse(String value){
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            return playerActionResponse(false, "invalid muted state");
        }
        return playerActionResponse(XLVideoPlayActivity.dispatchMute(Boolean.parseBoolean(value)), null);
    }

    private static NanoHTTPD.Response playerTrackResponse(String kind, String value){
        if (kind == null || value == null) return playerActionResponse(false, "invalid track");
        int index = parseIntSafely(value);
        if (index < -1) return playerActionResponse(false, "invalid track");
        return playerActionResponse(XLVideoPlayActivity.dispatchTrackSelection(kind, index), null);
    }

    private static JSONArray trackArray(XLVideoPlayActivity.WebTrackInfo[] tracks) throws JSONException {
        JSONArray result = new JSONArray();
        if (tracks == null) return result;
        for (XLVideoPlayActivity.WebTrackInfo track : tracks) {
            if (track == null) continue;
            JSONObject item = new JSONObject();
            item.put("index", track.index);
            item.put("language", track.language == null ? "" : track.language);
            item.put("info", track.info == null ? "" : track.info);
            item.put("selected", track.selected);
            result.put(item);
        }
        return result;
    }

    private static NanoHTTPD.Response playerActionResponse(boolean handled, String message){
        JSONObject result = new JSONObject();
        try {
            result.put("handled", handled);
            if (message != null) result.put("message", message);
        } catch (JSONException ignored) {
        }
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, result.toString());
    }

    private static int parseKeyCode(String keyCode){
        if(keyCode == null) return KeyEvent.KEYCODE_UNKNOWN;
        String value = keyCode.trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return KeyEvent.keyCodeFromString(value);
        }
    }

    private static int parseIntSafely(String value){
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static float parseFloatSafely(String value){
        try {
            return value == null ? Float.NaN : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static int clampInt(int value, int min, int max){
        return value < min ? min : (value > max ? max : value);
    }
}
