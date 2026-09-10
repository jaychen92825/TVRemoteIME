package com.android.tvremoteime;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.android.tvremoteime.server.RemoteServer;

import java.io.IOException;
import java.net.InetAddress;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * 在局域网内注册一个固定的mDNS主机名（tvremoteime.local），这样盒子的局域网IP
 * 因DHCP重新分配而改变时，控制端书签（http://tvremoteime.local:端口/）依旧可用，
 * 不需要每次都重新扫描二维码。
 */
public class MDnsHelper {
    private static final String TAG = "MDnsHelper";
    public static final String HOSTNAME = "tvremoteime";

    private static JmDNS jmdns = null;
    private static WifiManager.MulticastLock multicastLock = null;

    public static synchronized void start(final Context context){
        if(jmdns != null) return;
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                    if(wifiManager != null){
                        //Android默认会过滤Wi-Fi上的组播包，不拿这个锁mDNS请求收不到
                        WifiManager.MulticastLock lock = wifiManager.createMulticastLock("tvremoteime-mdns");
                        lock.setReferenceCounted(true);
                        lock.acquire();
                        multicastLock = lock;
                    }
                    String ip = RemoteServer.getLocalIPAddress(appContext);
                    if(ip == null || "0.0.0.0".equals(ip)){
                        Log.i(TAG, "未获取到有效局域网IP，跳过mDNS注册。");
                        return;
                    }
                    JmDNS instance = JmDNS.create(InetAddress.getByName(ip), HOSTNAME);
                    ServiceInfo serviceInfo = ServiceInfo.create("_http._tcp.local.",
                            appContext.getString(R.string.app_name), RemoteServer.serverPort, "path=/");
                    instance.registerService(serviceInfo);
                    jmdns = instance;
                    Log.i(TAG, "mDNS已启动：" + getAddress());
                } catch (Exception e) {
                    Log.e(TAG, "启动mDNS服务失败", e);
                }
            }
        }).start();
    }

    public static synchronized void stop(){
        final JmDNS instance = jmdns;
        final WifiManager.MulticastLock lock = multicastLock;
        jmdns = null;
        multicastLock = null;
        if(instance == null && lock == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if(instance != null){
                    try {
                        instance.close();
                    } catch (IOException ignored) {
                    }
                }
                if(lock != null && lock.isHeld()){
                    lock.release();
                }
            }
        }).start();
    }

    public static String getAddress(){
        return "http://" + HOSTNAME + ".local:" + RemoteServer.serverPort + "/";
    }
}
