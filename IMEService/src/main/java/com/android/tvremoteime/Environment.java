package com.android.tvremoteime;

import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.Tag;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.util.List;

/**
 * Created by kingt on 2018/3/6.
 */

public class Environment {
    public final static boolean needDebug = false;

    public static int adbServerPort = 5555;

    private static Handler toastHandler = null;

    private static final String PREFS_NAME = "tvremoteime_settings";
    private static final String PREF_ACCESS_CODE = "access_code";
    private static final String PREF_APP_LAUNCH_COUNT_PREFIX = "app_launch_count_";
    private static final String PREF_KEYBOARD_VIEW_VISIBLE = "keyboard_view_visible";
    public static final String AUTH_REALM_USER = "tvremoteime";

    /**
     * 电视端软键盘视图(那一套D-pad导航的QWERTY网格，IMEService.onCreateInputView
     * 里inflate出来的R.layout.keyboard)是否显示。这个视图里的"帮助"弹窗
     * (helpDialog)带着二维码+地址，是用户扫码把手机连到控制端网页的入口——
     * 没有任何控制端*当前*连着的时候，必须显示这个视图才能让用户找到二维码，
     * 所以不能简单地"默认永远隐藏"；但也不能靠"历史上有没有连过"来判断——
     * 那样的话只要连过一次，哪怕控制端早就关掉了，这台设备往后也再也看不到
     * 二维码，别人想连都没入口。
     *
     * 逻辑：用户在控制端手动切换过一次的话(PREF_KEYBOARD_VIEW_VISIBLE有值)，
     * 尊重用户的选择；没手动切换过的话，按"当前有没有控制端处于活跃状态"
     * (hasActiveClient，由调用方传入，实际数据来自RemoteServer.hasActiveClient()，
     * 见那边关于心跳判活的说明)来定默认值——当前没有活跃客户端，默认显示
     * (带二维码方便扫码连接)；有活跃客户端连着，默认隐藏。
     */
    public static boolean isKeyboardViewVisible(Context context, boolean hasActiveClient){
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if(prefs.contains(PREF_KEYBOARD_VIEW_VISIBLE)){
            return prefs.getBoolean(PREF_KEYBOARD_VIEW_VISIBLE, true);
        }
        return !hasActiveClient;
    }

    public static void setKeyboardViewVisible(Context context, boolean visible){
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_KEYBOARD_VIEW_VISIBLE, visible).apply();
    }

    /**
     * 控制端HTTP接口的访问口令。首次调用时会用固定的默认口令并持久化，用于给
     * RemoteServer做HTTP Basic鉴权，避免局域网内任何人/网页无鉴权即可控制盒子；
     * 用户可以在App主界面"修改访问口令"卡片里随时改成自己的口令(见MainActivity/
     * setAccessCode)。默认口令是固定值而不是随机生成，方便用户第一次连接时
     * 不用先打开App主界面查看随机口令是多少，直接就能输入默认值登录。
     */
    public static String getAccessCode(Context context){
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String code = prefs.getString(PREF_ACCESS_CODE, null);
        if(code == null){
            code = generateAccessCode();
            prefs.edit().putString(PREF_ACCESS_CODE, code).apply();
        }
        return code;
    }

    public static void setAccessCode(Context context, String code){
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_ACCESS_CODE, code).apply();
    }

    private static String generateAccessCode(){
        return "123456";
    }

    /**
     * 应用管理里"常用应用置顶"用：每次通过控制端启动某个应用就计一次数，
     * 应用列表按这个次数从高到低排（同类别内，不影响"非系统应用在前/
     * 系统应用在后"这个大分组）。
     */
    public static void recordAppLaunch(Context context, String packageName){
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int count = prefs.getInt(PREF_APP_LAUNCH_COUNT_PREFIX + packageName, 0);
        prefs.edit().putInt(PREF_APP_LAUNCH_COUNT_PREFIX + packageName, count + 1).apply();
    }

    public static int getAppLaunchCount(Context context, String packageName){
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_APP_LAUNCH_COUNT_PREFIX + packageName, 0);
    }

    public static void debug(String tag, String msg){
        Log.d(tag, msg);
    }
    public static void debug(String tag, String msg, Throwable tr){
        Log.d(tag, msg, tr);
    }

    public static void initToastHandler(){
        if(toastHandler == null) toastHandler = new Handler();
    }
    public static void toastInHandler(final Context context, final String msg){
        if(toastHandler != null){
            toastHandler.post(new Runnable() {
                @Override
                public void run() {
                    toast(context, msg);
                }
            });
        }
    }
    public static void toast(Context context, String msg){
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
    }

    public static boolean isEnableIME(Context context){
        try {
            InputMethodManager imm = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
            List<InputMethodInfo> inputs = imm.getEnabledInputMethodList();
            boolean flag = false;
            for(InputMethodInfo input : inputs){
                if(input.getPackageName().equals(IMEService.class.getPackage().getName())){
                    return true;
                }
            }
        }catch (Exception ignored){ }
        return false;
    }

    public  static boolean isDefaultIME(Context context){
        try {
            String defaultImme = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);

            if (defaultImme != null && defaultImme.startsWith(IMEService.class.getPackage().getName())) {
                return true;
            }
        }catch (Exception ignored){ }
        return false;
    }
}
