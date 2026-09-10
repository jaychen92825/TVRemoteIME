package com.android.tvremoteime.adb;

import android.content.Context;
import android.util.Log;

import com.android.tvremoteime.Environment;
import com.android.tvremoteime.R;
import com.android.tvremoteime.server.RemoteServer;
import com.cgutman.adblib.AdbBase64;
import com.cgutman.adblib.AdbConnection;
import com.cgutman.adblib.AdbCrypto;
import com.cgutman.adblib.AdbStream;
import com.cgutman.adblib.Base64;

import java.io.IOException;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;

/**
 * Created by kingt on 2018/3/7.
 */

public class AdbHelper {
    //触控板拖动：一次从(x1,y1)滑到(x2,y2)的手势，等价于"adb shell input swipe"
    public static class SwipeCommand {
        public final int x1, y1, x2, y2, durationMs;
        public SwipeCommand(int x1, int y1, int x2, int y2, int durationMs){
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.durationMs = durationMs;
        }
    }
    //触控板轻触：在(x,y)处的一次点击，等价于"adb shell input tap"
    public static class TapCommand {
        public final int x, y;
        public TapCommand(int x, int y){ this.x = x; this.y = y; }
    }
    //控制端"ADB连接状态"指示灯用：在没有真正的按键/触控命令要发之前，主动
    //探测一下能不能连上adb，这样用户还没点电源键/触控板之前就能看到能不能用，
    //而不是非要先点一次、失败了才知道。"shell:echo"在设备上没有任何副作用。
    private static final Object PROBE = new Object();

    private static String TAG = "AdbHelper";
    private AdbConnection connection = null;
    private String host;
    private int port;

    private ArrayDeque<Object> sendDataDeque = new ArrayDeque<>();
    private Thread sendDataThread = null;
    private boolean running = false;
    private volatile boolean probing = false;
    private Context context;

    private AdbHelper(){
    }

    public boolean isRunning(){
        return running;
    }

    public void init(Context context, String host, int port) {
        this.context = context;
        this.host = host;
        this.port = port;
        this.running = true;
        this.initSDThread();
    }

    public void stop() {
        this.running = false;
        this.context = null;
        if(this.connection != null){
            try {
                this.connection.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            this.connection = null;
        }
        synchronized (sendDataDeque) {
            sendDataDeque.notifyAll();
        }
        if(sendDataThread != null && sendDataThread.isAlive()) {
            sendDataThread.interrupt();
            try {
                sendDataThread.join();
            } catch (InterruptedException e) {
            }
        }
    }


    private void  initSDThread(){
        final AdbHelper adbHelper = this;
        if(sendDataThread == null || !sendDataThread.isAlive()){
            sendDataThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Object data = 0;
                    while (running) {
                        synchronized (sendDataDeque) {
                            try {
                                if(sendDataDeque.size() == 0)
                                    sendDataDeque.wait();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            if (sendDataDeque.size() > 0) {
                                data = sendDataDeque.pop();
                            }else{
                                continue;
                            }
                        }
                        String msg = null;
                        if(data == PROBE){
                            msg = "shell:echo probe";
                        }else if(data instanceof Integer){
                            msg = "shell:input keyevent " + String.valueOf(data);
                        }else if(data instanceof SwipeCommand){
                            //坐标均为服务端计算得出的int，不含用户可控字符，无需转义
                            SwipeCommand s = (SwipeCommand) data;
                            msg = "shell:input swipe " + s.x1 + " " + s.y1 + " " + s.x2 + " " + s.y2 + " " + s.durationMs;
                        }else if(data instanceof TapCommand){
                            TapCommand t = (TapCommand) data;
                            msg = "shell:input tap " + t.x + " " + t.y;
                        }else{
                            //使用单引号包裹并转义内部单引号，避免文本中的$()、反引号等被远程shell当作命令展开执行
                            msg = "shell:input text '" + ((String)data).replace("'", "'\\''") + "'";
                        }
                        try {
                            if(connection != null || try2Connect()){
                                AdbStream stream = connection.open(msg);
                                if(Environment.needDebug){
                                    Environment.debug(TAG, "已成功发送adb命令：" + msg);
                                    //Environment.toastInHandler(adbHelper.context, "TVRemoteIME成功向adb服务发送命令。"  + msg);
                                }
                            }else {
                                if(Environment.needDebug){
                                    Environment.debug(TAG, "未发送adb命令：" + msg);
                                    //Environment.toastInHandler(adbHelper.context, "TVRemoteIME向adb服务发送命令时失败。");
                                }
                            }
                            //stream.close();
                        } catch (Exception e) {
                            if(Environment.needDebug){
                                Environment.debug(TAG, "发送adb命令时出错：" + msg, e);
                                //Environment.toastInHandler(adbHelper.context, "TVRemoteIME向adb服务发送命令时出错。" + e.toString());
                            }
                        }
                        if(data == PROBE){
                            probing = false;
                        }
                    }
                }
            });
            sendDataThread.start();
        }
    }

    private boolean try2Connect()
    {
        try {
            Socket socket = new Socket(this.host, this.port);
            socket.setSoTimeout(1000 * 10);
            AdbCrypto crypto = null;
            try {
                crypto = AdbCrypto.generateAdbKeyPair(new AdbBase64() {
                    @Override
                    public String encodeToString(byte[] data) {
                        return Base64.encodeToString(data, 16);
                    }
                });
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }

            final AdbHelper adbHelper = this;
            if (connection == null) {
                connection = AdbConnection.create(socket, crypto);
                connection.setOnClosedListener(new AdbConnection.ConnectionOnClosedListener() {
                    @Override
                    public void onClosed() {
                        Log.i(TAG, "adb已断开连接。");
                        Environment.toastInHandler(adbHelper.context, context.getString(R.string.app_name)  + "和adb服务已断开连接。");
                        try {
                            connection.close();
                            connection = null;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }

            connection.connect();
            Log.i(TAG, "adb已连接成功。");
            Environment.toastInHandler(adbHelper.context, context.getString(R.string.app_name)  + "和adb服务已连接成功。");
            return true;
        }catch(Exception e) {
            Log.e(TAG, "adb连接失败，错误信息。" + e.toString(), e);
            connection = null;
            return false;
        }
    }

    public void sendData(Object data){
        sendDataDeque.push(data);
        synchronized (sendDataDeque) {
            sendDataDeque.notifyAll();
        }
    }

    public boolean isConnected(){
        return connection != null;
    }

    private static AdbHelper instance = null;
    public static AdbHelper getInstance(){
        return instance;
    }
    public static boolean isServiceConnected(){
        return instance != null && instance.isConnected();
    }
    //控制端轮询"ADB连接状态"时调用：还没连上就顺手探测一次，让状态灯能在
    //用户真正点电源键/触控板之前就自己变绿，而不是必须先点一次才知道行不行。
    //probing标记避免轮询间隔比连接超时还短时反复堆积探测请求。
    public static void probeConnection(){
        if(instance != null && !instance.isConnected() && !instance.probing){
            instance.probing = true;
            instance.sendData(PROBE);
        }
    }
    public static void createInstance(){
        if(instance == null){
            synchronized (AdbHelper.class){
                if(instance == null)
                    instance = new AdbHelper();
            }
        }
    }
    public static boolean initService(Context context){
        if(instance != null){
            if(!instance.isRunning()){
                instance.init(context, RemoteServer.getLocalIPAddress(context), Environment.adbServerPort);
            }
            return true;
        }
        return false;
    }
    public static void stopService(){
        if(instance != null) instance.stop();
    }
}
