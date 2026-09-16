package com.android.tvremoteime;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.tvremoteime.server.RemoteServer;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import javax.jmdns.impl.JmDNSImpl;

/**
 * 在局域网内注册一个固定的mDNS主机名，这样盒子的局域网IP因DHCP重新分配而
 * 改变时，控制端书签依旧可用，不需要每次都重新扫描二维码。
 *
 * 只用固定的"remote"这一个名字的话，同一局域网内多台设备会全部抢注同一个
 * 名字：mDNS协议本身能探测到这种冲突、给后到的设备自动加数字后缀(比如
 * remote-2.local)，但具体谁被改成"-2"、谁还是原名，取决于设备开机/注册的
 * 先后顺序——用户没法提前知道、只能每次都重新查一遍当前地址，做不到"直接
 * 盲打地址"。
 *
 * 这里改成优先复用用户已经在设置里为这台设备起的DLNA名称后缀(多台设备时
 * 用户本来就会给每台起一个不同的、好记的名字，比如"卧室"/"客厅"，这本身
 * 就是为了在DLNA设备列表里区分它们)：取这个后缀里的ASCII字母数字部分拼进
 * 主机名(比如后缀是"bedroom"，主机名就是remote-bedroom)，全过滤掉的话
 * (比如后缀是纯中文、或者压根没设置)退回固定的"remote"。这样只要用户给
 * 不同设备起了不同(且至少含一些ASCII字符)的名字，地址就是稳定、可预测、
 * 能直接记住盲打出来的，不再依赖谁先抢注成功；两台设备万一还是撞了同一个
 * 后缀，也不会真的连不上——mDNS自己的探测/改名兜底依然在，只是变成小概率
 * 才会触发的最后一道保险，不再是每次都要看运气的默认情况。
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

    //resolvedHostName是在start()自己开的后台线程里才最终确定的(等
    //waitForAnnounced()跑完，可能要好几秒)，MainActivity/IMEService的
    //帮助弹窗都只在各自的某个触发时机(onResume、弹窗打开一次)读一次
    //getAddress()，谁都没有主动去"等"这个后台线程——如果读的时候正好
    //赶在mDNS还没跑完，读到的就是还没生效的兜底值，而且没人会在resolvedHostName
    //真正就绪后回头去刷新界面上已经显示出来的旧值。这里加一个简单的监听
    //者集合，resolvedHostName真正确定的那一刻回调通知，界面侧只需要在
    //自己活跃期间注册一下，收到回调就重新读一次getAddress()刷新显示，
    //不需要各自猜一个"应该等多久再重试"的延时。
    public interface ResolvedListener {
        void onHostResolved();
    }
    private static final Set<ResolvedListener> listeners = new CopyOnWriteArraySet<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void addListener(ResolvedListener listener){
        listeners.add(listener);
    }
    public static void removeListener(ResolvedListener listener){
        listeners.remove(listener);
    }
    private static void notifyResolved(){
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for(ResolvedListener listener : listeners){
                    listener.onHostResolved();
                }
            }
        });
    }

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
                    String hostLabel = buildHostLabel(appContext);
                    JmDNS instance = JmDNS.create(InetAddress.getByName(ip), hostLabel);
                    //waitForAnnounced()只在具体实现类JmDNSImpl上，公开的抽象类
                    //JmDNS并没有声明这个方法——create()工厂方法目前固定返回
                    //JmDNSImpl实例，做一次instanceof保底，万一以后库版本换了
                    //实现类，就跳过等待、直接读(退化成"尽力而为"，不会编译不过
                    //也不会崩)。
                    if(instance instanceof JmDNSImpl){
                        ((JmDNSImpl) instance).waitForAnnounced(ANNOUNCE_WAIT_TIMEOUT_MS);
                    }
                    resolvedHostName = stripTrailingDot(instance.getHostName());
                    notifyResolved();
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

    //DLNA名称后缀在设置里改掉之后，需要重新走一遍start()才能让mDNS主机名
    //跟着换成新的——跟DLNAUtils.setDLNANameSuffix()改完名字要重启DLNA
    //服务是同一个道理，不会自动生效。
    public static void restart(Context context){
        stop();
        start(context);
    }

    //把DLNA名称后缀里能安全当主机名用的部分拼到"remote-"后面：只保留ASCII
    //字母数字，其它字符(中文、空格、标点等)统一折成一个连字符，头尾的连
    //字符再去掉。mDNS本身允许UTF-8主机名，但手机浏览器地址栏对含非ASCII
    //字符的域名会做IDNA/Punycode转换，不同浏览器、不同系统版本对.local
    //这种mDNS域名是否还原得回UTF-8并不一致，直接把中文塞进去反而可能变成
    //另一种"不同设备表现不一样、猜不到"，所以只取ASCII部分，过滤完是空的
    //(比如后缀全是中文，或者压根没设置)就还是用固定的"remote"。
    private static String buildHostLabel(Context context){
        String suffix = sanitizeHostLabelPart(DLNAUtils.getDLNANameSuffix(context));
        return suffix.isEmpty() ? HOSTNAME : (HOSTNAME + "-" + suffix);
    }

    private static String sanitizeHostLabelPart(String raw){
        if(raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < raw.length(); i++){
            char c = raw.charAt(i);
            if((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')){
                sb.append(c);
            } else if(c >= 'A' && c <= 'Z'){
                sb.append(Character.toLowerCase(c));
            } else if(sb.length() > 0 && sb.charAt(sb.length() - 1) != '-'){
                sb.append('-');
            }
        }
        while(sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        while(sb.length() > 0 && sb.charAt(0) == '-') sb.deleteCharAt(0);
        //mDNS单个标签最长63字节，"remote-"前缀已经占了7个，这里留足余量，
        //不需要真的顶到上限。
        if(sb.length() > 40) sb.setLength(40);
        return sb.toString();
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
