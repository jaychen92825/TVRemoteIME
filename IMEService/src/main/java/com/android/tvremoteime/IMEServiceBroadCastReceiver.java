package com.android.tvremoteime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.android.tvremoteime.adb.AdbHelper;

/**
 * Created by kingt on 2018/3/5.
 */

public class IMEServiceBroadCastReceiver extends BroadcastReceiver {
    private final String TAG = "IMEServiceBCR";
    private final String ACTION_BOOT = "android.intent.action.BOOT_COMPLETED";
    private final String MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "receive msg:" + intent.getAction());
       if (ACTION_BOOT.equals(intent.getAction()) ||
               MEDIA_MOUNTED.equals(intent.getAction())) {
           //之前这里是"if(!isDefaultIME(context))"——只有在还没设成默认
           //输入法时才启动服务，正好反了。这个服务承载的是HTTP远程控制/
           //DLNA/mDNS这一整套后台功能，不是"只有当前是默认输入法才需要
           //跑"的东西：就算已经设成默认输入法，Android也只会在真的有输入框
           //获得焦点时才懒加载启动IMEService，用户重启电视之后除非凑巧碰到
           //一个输入框、或者手动打开App点"重启服务"，服务器/DLNA/mDNS会
           //一直不跑，表现就是"开机/打开App之后要等好久才能连上"。去掉这个
           //条件，开机/存储挂载后始终尝试启动服务(已经在跑的话startService()
           //只是重新调一次onStartCommand()，不会重复初始化，无副作用)。
           Log.d(TAG, "startService.....");
           context.startService(new Intent(IMEService.ACTION));
           if(AdbHelper.getInstance() == null) AdbHelper.createInstance();
       }
    }
}
