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

import java.security.SecureRandom;
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
    public static final String AUTH_REALM_USER = "tvremoteime";

    /**
     * 控制端HTTP接口的访问口令。首次调用时会自动生成一个随机6位数字口令并持久化，
     * 用于给RemoteServer做HTTP Basic鉴权，避免局域网内任何人/网页无鉴权即可控制盒子。
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
        SecureRandom random = new SecureRandom();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
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
