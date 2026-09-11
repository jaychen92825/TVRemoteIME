package com.android.tvremoteime.server;


import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.android.tvremoteime.Environment;
import com.android.tvremoteime.IMEService;
import com.android.tvremoteime.R;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.*;

/**
 * Created by kingt on 2018/1/7.
 */

public class RemoteServer extends NanoHTTPD
{
    public interface DataReceiver{
        /**
         *
         * @param keyCode
         * @param keyAction : 0 = keypressed, 1 = keydown, 2 = keyup
         */
        void onKeyEventReceived(String keyCode, int keyAction);

        /**
         * 提交（最终确定）一段文本，等价于用户在软键盘上把这段文字敲完了。
         * @param text
         */
        void onTextReceived(String text);

        /**
         * 输入过程中的实时预览文本（对应输入法的"正在输入/组字"状态，尚未提交），
         * 每次控制端文本框内容变化都会调用，用于实现输入内容与电视端实时同步。
         * @param text
         */
        void onComposingTextReceived(String text);

        /**
         * 触控板相对位移（单位：屏幕像素，已在服务端按灵敏度换算），
         * 用来模拟"手指在电视屏幕上拖动光标"。
         * @param dx
         * @param dy
         */
        void onMouseMoveReceived(int dx, int dy);

        /**
         * 触控板轻触（未产生明显拖动的一次按下+抬起），等价于鼠标左键单击/触屏点击。
         */
        void onMouseClickReceived();
    }

    public static int serverPort = 12345;
    private boolean isStarted = false;
    private DataReceiver mDataReceiver = null;
    private Context mContext = null;
    private RemoteServerFileManager.Factory fileManagerFactory = new RemoteServerFileManager.Factory();
    private ArrayList<RequestProcesser> getRequestProcessers = new ArrayList<>();
    private ArrayList<RequestProcesser> postRequestProcessers = new ArrayList<>();

    public void setDataReceiver(DataReceiver receiver){
        mDataReceiver = receiver;
    }
    public DataReceiver getDataReceiver(){
        return mDataReceiver;
    }
    public boolean isStarting(){
        return isStarted;
    }

    public RemoteServer(int port, Context context) {
        super(port);
        mContext = context;
        this.addGetRequestProcessers();
        this.addPostRequestProcessers();
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        isStarted = true;
        setTempFileManagerFactory(fileManagerFactory);
        super.start(timeout, daemon);
    }

    @Override
    public void stop() {
        super.stop();
        isStarted = false;
    }

    public static String getLocalIPAddress(Context context){
        WifiManager wifiManager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        if(ipAddress == 0){
            try {
                Enumeration<NetworkInterface> enumerationNi = NetworkInterface.getNetworkInterfaces();
                while (enumerationNi.hasMoreElements()) {
                    NetworkInterface networkInterface = enumerationNi.nextElement();
                    String interfaceName = networkInterface.getDisplayName();
                    if (interfaceName.equals("eth0") || interfaceName.equals("wlan0")) {
                        Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses();

                        while (enumIpAddr.hasMoreElements()) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                return inetAddress.getHostAddress();
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                Log.e(IMEService.TAG, "获取本地IP出错", e);
            }
        }else {
            return String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        }
        return "0.0.0.0";
    }

    public String getServerAddress() {
        return getServerAddress(mContext);
    }
    public static String getServerAddress(Context context){
        String ipAddress = getLocalIPAddress(context);
        if(Environment.needDebug) {
            Environment.debug(IMEService.TAG, "ip-address:" + ipAddress);
        }
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/";
    }

    public static Response createPlainTextResponse(Response.IStatus status, String text){
        return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text);
    }

    public static Response createJSONResponse(Response.IStatus status, String text){
        return newFixedLengthResponse(status, "application/json", text);
    }

    private void addGetRequestProcessers(){
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/index.html", R.raw.index, NanoHTTPD.MIME_HTML));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/style.css", R.raw.style, "text/css"));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/jquery_min.js", R.raw.jquery_min, "application/x-javascript"));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/ime_core.js", R.raw.ime_core, "application/x-javascript"));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/ic_dl_folder.png", R.raw.ic_dl_folder, "image/png"));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/ic_dl_other.png", R.raw.ic_dl_other, "image/png"));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/ic_dl_video.png", R.raw.ic_dl_video, "image/png"));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/favicon.ico", R.drawable.ic_launcher, "image/x-icon"));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/manifest.json", R.raw.manifest, "application/manifest+json"));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/sw.js", R.raw.sw, "application/javascript"));
        this.getRequestProcessers.add(new RawRequestProcesser(this.mContext, "/icon.png", R.drawable.ic_launcher, "image/png"));
        this.getRequestProcessers.add(new FileRequestProcesser(this.mContext));
        this.getRequestProcessers.add(new AppIconRequestProcesser(this.mContext));
        this.getRequestProcessers.add(new TVRequestProcesser(this.mContext));
        this.getRequestProcessers.add(new OtherGetRequestProcesser(this.mContext));
    }
    private void addPostRequestProcessers(){
        this.postRequestProcessers.add(new InputRequestProcesser(this.mContext, this));
        this.postRequestProcessers.add(new UploadRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new AppRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new AccessibilityRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new PlayRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new FileRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new TVRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new TorrentRequestProcesser(this.mContext));
        this.postRequestProcessers.add(new OtherPostRequestProcesser(this.mContext));
    }


    //扫二维码免密登录用的会话口令，登录成功后种一个cookie，服务重启（进程内存清空）
    //后所有会话失效，需要重新扫码或手动输入口令——不做持久化存储，简单换取安全。
    private static final String SESSION_COOKIE_NAME = "tvrc_auth";
    private static final Set<String> validSessionTokens =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static String stripQuery(String uri){
        if(uri == null) return "";
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }

    private static String generateSessionToken(){
        byte[] buf = new byte[16];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for(byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static boolean hasValidSessionCookie(IHTTPSession session){
        String cookieHeader = session.getHeaders().get("cookie");
        if(TextUtils.isEmpty(cookieHeader)) return false;
        for(String part : cookieHeader.split(";")){
            String[] kv = part.trim().split("=", 2);
            if(kv.length == 2 && SESSION_COOKIE_NAME.equals(kv[0]) && validSessionTokens.contains(kv[1])){
                return true;
            }
        }
        return false;
    }

    /**
     * 扫二维码免密登录：二维码里编码的是 /login?code=口令 这个直接可访问的地址，
     * 用普通GET+浏览器原生跳转即可完成，不需要任何JS技巧。验证通过后种一个
     * HttpOnly的session cookie，此后同一浏览器的所有请求都会自动带上，不会再弹
     * 原生的Basic Auth登录框。
     */
    private Response handleLogin(IHTTPSession session){
        String code = session.getParms().get("code");
        String accessCode = Environment.getAccessCode(mContext);
        if(TextUtils.isEmpty(accessCode) || !accessCode.equals(code)){
            return createPlainTextResponse(Response.Status.FORBIDDEN, "口令错误，请重新扫码或在应用主界面查看当前口令。");
        }
        String token = generateSessionToken();
        validSessionTokens.add(token);
        markClientConnectedAndRefreshKeyboardView();
        Response resp = newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_HTML,
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><script>location.replace('/');</script></head><body>登录成功，正在跳转…</body></html>");
        resp.addHeader("Set-Cookie", SESSION_COOKIE_NAME + "=" + token + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax");
        return resp;
    }

    /**
     * 控制端接口的鉴权：优先看有没有登录成功后种下的session cookie，没有的话退回
     * HTTP Basic鉴权（手动访问地址、没走过/login的场景）。口令保存在应用私有
     * SharedPreferences中，首次运行自动生成，在应用主界面/输入法帮助页可查看。
     * 避免局域网内任意设备或网页（CSRF）无鉴权即可访问文件管理、装卸载应用、
     * 按键输入等接口。
     */
    private Response checkAuth(IHTTPSession session){
        String accessCode = Environment.getAccessCode(mContext);
        if(TextUtils.isEmpty(accessCode)) return null;

        if(hasValidSessionCookie(session)) return null;

        String authHeader = session.getHeaders().get("authorization");
        if(authHeader != null && authHeader.toLowerCase().startsWith("basic ")){
            try {
                byte[] decoded = Base64.decode(authHeader.substring(6).trim(), Base64.DEFAULT);
                String credentials = new String(decoded, "UTF-8");
                int idx = credentials.indexOf(':');
                String password = idx >= 0 ? credentials.substring(idx + 1) : credentials;
                if (accessCode.equals(password)) {
                    return null;
                }
            } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
            }
        }
        Response resp = createPlainTextResponse(Response.Status.UNAUTHORIZED, "需要访问口令，请在应用主界面查看当前口令。");
        resp.addHeader("WWW-Authenticate", "Basic realm=\"" + Environment.AUTH_REALM_USER + "\"");
        return resp;
    }

    //控制端第一次鉴权成功时，把电视端软键盘(带二维码，参见Environment里
    //isKeyboardViewVisible的说明)的默认显示逻辑从"显示"切到"隐藏"，并立即
    //让正在跑的IMEService重新评估一次要不要显示，不用等下次输入框焦点变化。
    private void markClientConnectedAndRefreshKeyboardView(){
        if(Environment.markClientConnected(mContext)){
            IMEService.refreshKeyboardViewVisibility();
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = stripQuery(session.getUri());
        Log.i(IMEService.TAG, "接收到HTTP请求：" + session.getMethod() + " " + path);

        if(session.getMethod() == Method.GET && "/login".equals(path)){
            return handleLogin(session);
        }

        Response authFailure = checkAuth(session);
        if(authFailure != null) return authFailure;
        markClientConnectedAndRefreshKeyboardView();
        if(!session.getUri().isEmpty()) {
            String fileName = path;
            if (session.getMethod() == Method.GET) {
                for(RequestProcesser processer : this.getRequestProcessers){
                    if(processer.isRequest(session, fileName)){
                        return processer.doResponse(session, fileName, session.getParms(), null);
                    }
                }
            } else if (session.getMethod() == Method.POST) {
                Map<String, String> files = new HashMap<String, String>();
                try {
                    session.parseBody(files);
                } catch (IOException ioex) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,  "SERVER INTERNAL ERROR: IOException: " + ioex.getMessage());
                } catch (NanoHTTPD.ResponseException rex) {
                    return createPlainTextResponse(rex.getStatus(),  rex.getMessage());
                }
                for(RequestProcesser processer : this.postRequestProcessers){
                    if(processer.isRequest(session, fileName)){
                        return processer.doResponse(session, fileName, session.getParms(), files);
                    }
                }
            }
        }
        //default page: index.html
        return this.getRequestProcessers.get(0).doResponse(session, "", null, null);
    }
}
