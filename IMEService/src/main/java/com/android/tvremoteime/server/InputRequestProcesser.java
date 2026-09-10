package com.android.tvremoteime.server;

import android.content.Context;

import com.android.tvremoteime.IMEService;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

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
                if (params.get("code") != null && mDataReceiver != null) {
                    mDataReceiver.onKeyEventReceived(params.get("code"), IMEService.KEY_ACTION_PRESSED);
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/keyup":
                if (params.get("code") != null && mDataReceiver != null) {
                    mDataReceiver.onKeyEventReceived(params.get("code"), IMEService.KEY_ACTION_UP);
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
            case "/keydown":
                if (params.get("code") != null && mDataReceiver != null) {
                    mDataReceiver.onKeyEventReceived(params.get("code"), IMEService.KEY_ACTION_DOWN);
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,"ok");
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

    private static int parseIntSafely(String value){
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int clampInt(int value, int min, int max){
        return value < min ? min : (value > max ? max : value);
    }
}
