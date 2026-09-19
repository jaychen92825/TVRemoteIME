package com.android.tvremoteime;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.android.tvremoteime.adb.AdbHelper;

/**
 * Created by kingt on 2018/3/5.
 */

public class IMEServiceBroadCastReceiver extends BroadcastReceiver {
    private final String TAG = "IMEServiceBCR";
    private final String ACTION_BOOT = "android.intent.action.BOOT_COMPLETED";
    private final String ACTION_QUICK_BOOT = "android.intent.action.QUICKBOOT_POWERON";
    private final String ACTION_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    private final String MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED";
    private static final String ACTION_RETRY = "com.android.tvremoteime.BOOT_RETRY";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "receive msg:" + intent.getAction());
       if (ACTION_BOOT.equals(intent.getAction()) || ACTION_QUICK_BOOT.equals(intent.getAction()) ||
               ACTION_PACKAGE_REPLACED.equals(intent.getAction()) || MEDIA_MOUNTED.equals(intent.getAction()) ||
               ACTION_RETRY.equals(intent.getAction())) {
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
           try {
               context.startService(new Intent(context, IMEService.class));
           } catch (Exception e) {
               Log.e(TAG, "开机启动远程服务失败", e);
           }
           if(AdbHelper.getInstance() == null) AdbHelper.createInstance();
           if (!ACTION_RETRY.equals(intent.getAction())) {
               scheduleRetry(context, 20 * 1000L, 1);
               scheduleRetry(context, 90 * 1000L, 2);
           }
       }
    }

    private void scheduleRetry(Context context, long delay, int requestCode) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        Intent retry = new Intent(context, IMEServiceBroadCastReceiver.class).setAction(ACTION_RETRY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, requestCode, retry, flags);
        alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pending);
    }
}
