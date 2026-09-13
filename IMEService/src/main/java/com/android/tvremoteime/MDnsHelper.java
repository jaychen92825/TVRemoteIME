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
 * 在局域网内注册一个固定的mDNS主机名（remote.local），这样盒子的局域网IP
 * 因DHCP重新分配而改变时，控制端书签（http://remote.local:端口/）依旧可用，
 * 不需要每次都重新扫描二维码。如果同一局域网内已有别的设备也叫这个名字，
 * JmDNS会按mDNS协议自动探测冲突并加数字后缀（如remote-2.local）——这一步
 * 是JmDNS自己异步完成的，getAddress()返回的地址会反映探测结束后实际生效
 * 的那个名字，不是永远显示未经冲突处理的"remote.local"字面量。
 */
public class MDnsHelper {
    private static final String TAG = "MDnsHelper";
    public static final String HOSTNAME = "remote";

    //JmDNS对主机名的探测/改名(RFC 6762的probing，冲突时从remote.local改成
    //remote-2.local)是后台异步进行的，create()调用本身不会等它跑完，紧接着
    //读getHostName()很可能还是探测开始前的初始值。等待到已经宣告(announced)
    //完成之后，getHostName()才是这台设备最终实际拿到的名字。
    private static final long ANNOUNCE_WAIT_TIMEOUT_MS = 5000;

    //缓存"实际生效"的主机名(可能因为冲突被改名成remote-2.local这种)，而不是
    //一直用HOSTNAME这个常量拼——之前getAddress()完全无视JmDNS自己的冲突改名
    //结果，不管有没有改名都硬编码显示"remote.local"，导致真正被改成了
    //remote-2.local的这台设备，二维码/地址栏上显示的还是"remote.local"，
    //扫码/访问要么连到网络里另一台先注册成功的设备，要么直接连不上。
    private static volatile String resolvedHostName = null;

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
                    instance.waitForAnnounced(ANNOUNCE_WAIT_TIMEOUT_MS);
                    resolvedHostName = stripTrailingDot(instance.getHostName());
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
        resolvedHostName = null;
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
        String host = resolvedHostName != null ? resolvedHostName : (HOSTNAME + ".local");
        return "http://" + host + ":" + RemoteServer.serverPort + "/";
    }

    private static String stripTrailingDot(String name){
        return (name != null && name.endsWith(".")) ? name.substring(0, name.length() - 1) : name;
    }
}
