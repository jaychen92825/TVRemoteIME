package com.android.tvremoteime.server;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;

import com.android.tvremoteime.AppPackagesHelper;
import com.android.tvremoteime.DLNAUtils;
import com.android.tvremoteime.VideoPlayHelper;
import com.android.tvremoteime.accessibility.ScreenAccessibilityService;
import com.android.tvremoteime.adb.AdbHelper;

import java.io.File;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Created by kingt on 2018/1/7.
 */

public class OtherGetRequestProcesser implements RequestProcesser {
    private Context context;

    public OtherGetRequestProcesser(Context context){
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if(session.getMethod() == NanoHTTPD.Method.GET){
            switch (fileName) {
                case "/version":
                case "/sdcard_stat":
                case "/adbStatus":
                case "/accessibilityStatus":
                case "/deviceName":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        switch (fileName) {
            case "/version":
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, AppPackagesHelper.getCurrentPackageVersion(this.context) );
            case "/sdcard_stat":
                return getSDCardStatResponse();
            case "/adbStatus":
                return getAdbStatusResponse();
            case "/accessibilityStatus":
                return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,
                        "{\"enabled\":" + ScreenAccessibilityService.isServiceEnabled() + "}");
            case "/deviceName":
                //复用已有的DLNA投屏名称(MainActivity"⑤DLNA投屏名称"卡片)当作
                //设备名——本来就是用户为区分多台设备起的名字("如：客厅、卧室")，
                //不需要再单独加一套配置。控制页拿这个名字当水印显示，同时开多台
                //设备的控制页时才分得清哪个标签页对应哪台电视。
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,
                        DLNAUtils.getDLNANameSuffix(this.context));
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }

    //电源键/触控板都依赖ADB，之前只能靠"点了没反应"来猜是不是没连上ADB；
    //这里主动探测一次连接状态（还没连上的话顺带在后台尝试连一次），让控制页
    //能提前显示"ADB未连接"，而不是等用户点了按钮才发现不生效。
    private NanoHTTPD.Response getAdbStatusResponse(){
        AdbHelper.createInstance();
        if(AdbHelper.initService(this.context)){
            AdbHelper.probeConnection();
        }
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,
                "{\"connected\":" + AdbHelper.isServiceConnected() + "}");
    }

    private NanoHTTPD.Response getSDCardStatResponse(){
        File path = Environment.getExternalStorageDirectory();
        StatFs stat = new StatFs(path.getPath());
        long totalBytes, availableBytes;
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2){
            totalBytes = (long)stat.getBlockCount() * stat.getBlockSize();
            availableBytes = (long)stat.getAvailableBlocks() * stat.getBlockSize();
        }else{
            totalBytes = stat.getTotalBytes();
            availableBytes = stat.getAvailableBytes();
        }
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,
                "{\"totalBytes\":" + totalBytes + ", \"availableBytes\":" + availableBytes + "}");
    }
}
