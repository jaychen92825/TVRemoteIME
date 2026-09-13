package com.android.tvremoteime.server;

import android.content.Context;

import com.android.tvremoteime.accessibility.ScreenAccessibilityService;

import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

/**
 * 控制端"元素列表"功能：/screenElements读取当前屏幕上可点击的元素列表，
 * /clickElement按列表下标点击其中一个。依赖ScreenAccessibilityService，
 * 用户没在系统设置里开启这个无障碍服务时，两个接口都会明确返回未启用，
 * 不会假装成功。
 */
public class AccessibilityRequestProcesser implements RequestProcesser {
    private Context context;

    public AccessibilityRequestProcesser(Context context){
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if(session.getMethod() == NanoHTTPD.Method.POST){
            switch (fileName) {
                case "/screenElements":
                case "/clickElement":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        switch (fileName) {
            case "/screenElements":
                return getScreenElementsResponse();
            case "/clickElement":
                return getClickElementResponse(params);
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }

    private NanoHTTPD.Response getScreenElementsResponse(){
        ScreenAccessibilityService service = ScreenAccessibilityService.getInstance();
        JSONObject result = new JSONObject();
        try {
            result.put("enabled", service != null);
            JSONArray array = new JSONArray();
            if(service != null){
                List<ScreenAccessibilityService.ElementInfo> elements = service.queryClickableElements();
                for(ScreenAccessibilityService.ElementInfo el : elements){
                    JSONObject obj = new JSONObject();
                    obj.put("id", el.id);
                    obj.put("label", el.label);
                    obj.put("type", el.type);
                    obj.put("left", el.left);
                    obj.put("top", el.top);
                    obj.put("right", el.right);
                    obj.put("bottom", el.bottom);
                    array.put(obj);
                }
            }
            result.put("elements", array);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, result.toString());
    }

    private NanoHTTPD.Response getClickElementResponse(Map<String, String> params){
        ScreenAccessibilityService service = ScreenAccessibilityService.getInstance();
        boolean success = false;
        if(service != null && params.get("id") != null){
            try {
                int id = Integer.parseInt(params.get("id"));
                success = service.clickElement(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, success ? "ok" : "fail");
    }
}
