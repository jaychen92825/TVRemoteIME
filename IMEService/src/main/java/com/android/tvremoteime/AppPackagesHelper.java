package com.android.tvremoteime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
/**
 * Created by kingt on 2018/1/9.
 */

public class AppPackagesHelper {

    public static class AppInfo implements Serializable{
        private String lable;
        private String packageName;
        private String apkPath;
        private boolean isSysApp;
        private boolean starred;

        public String getLable() {
            return lable;
        }

        public void setLable(String lable) {
            this.lable = lable;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public boolean isSysApp() {
            return isSysApp;
        }

        public void setSysApp(boolean sysApp) {
            isSysApp = sysApp;
        }

        public boolean isStarred() {
            return starred;
        }

        public void setStarred(boolean starred) {
            this.starred = starred;
        }
        public JSONObject toJSONObject()
        {
            JSONObject obj = new JSONObject();
            try {
                obj.put("lable", getLable());
                obj.put("packageName", getPackageName());
                obj.put("apkPath", getApkPath());
                obj.put("isSysApp",  isSysApp());
                obj.put("starred", isStarred());
            }catch (JSONException e) {
                e.printStackTrace();
            }
            return obj;
        }

        public String getApkPath() {
            return apkPath;
        }

        public void setApkPath(String apkPath) {
            this.apkPath = apkPath;
        }
    }

    public static String getCurrentPackageVersion(Context context){
        String version = "1.0.0";
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = packageInfo.versionName;
        }catch (PackageManager.NameNotFoundException e){}
        return version;
    }

    public static int getCurrentVersionCode(Context context){
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        }catch (PackageManager.NameNotFoundException e){
            return 0;
        }
    }

    public static List<AppInfo> queryAppInfo(final Context context, boolean containSysApp){
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> listAppcations = pm
                .getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES);
        List<AppInfo> appInfos = new ArrayList<AppInfo>();
        for (ApplicationInfo app : listAppcations) {
            if(containSysApp || (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                boolean isSysApp = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                //过滤掉系统底层的app
                if(isSysApp &&
                        (app.packageName.startsWith("com.android.") || app.packageName.equals("android")))continue;
                AppInfo appInfo = new AppInfo();
                appInfo.setLable((String) app.loadLabel(pm));
                appInfo.setPackageName(app.packageName);
                appInfo.setApkPath(app.sourceDir);
                appInfo.setSysApp(isSysApp);
                appInfo.setStarred(Environment.isAppStarred(context, app.packageName));
                appInfos.add(appInfo);
            }
        }
        //常用应用置顶：非系统/系统这个大分组不变，组内先按用户手动星标置顶
        //（星标是用户主动选的"我在意"，跟"用得多不多"是两回事），再按控制端
        //启动次数从高到低排，最后按名称排——这样点得越多、或者手动标星的应用
        //格子越靠前，不用每次都往下翻找。
        Collections.sort(appInfos, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo o1, AppInfo o2) {
                int i1 = (o1.isSysApp ? 2 : 1);
                int i2 = (o2.isSysApp ? 2 : 1);
                if(i1 != i2){
                    return (i1 < i2) ? -1 : 1;
                }
                if(o1.isStarred() != o2.isStarred()){
                    return o1.isStarred() ? -1 : 1;
                }
                int c1 = Environment.getAppLaunchCount(context, o1.getPackageName());
                int c2 = Environment.getAppLaunchCount(context, o2.getPackageName());
                if(c1 != c2){
                    return c2 - c1;
                }
                return o1.getLable().compareTo(o2.getLable());
            }
        });
        return  appInfos;
    }
    public static String getQueryAppInfoJsonString(Context context, boolean containSysApp){
        List<AppInfo> appInfos = queryAppInfo(context, containSysApp);
        JSONArray array = new JSONArray();
        for(AppInfo app : appInfos){
            array.put(app.toJSONObject());
        }
        return  array.toString();
    }

    private static ApplicationInfo getApplicationInfo(String packageName, Context context){
        ApplicationInfo applicationInfo = null;
        if(!packageName.isEmpty()) {
            PackageManager pm = context.getPackageManager();
            try {
                applicationInfo = pm.getApplicationInfo(packageName, 0);
            }catch (PackageManager.NameNotFoundException ex){
                applicationInfo = null;
            }
        }
        return  applicationInfo;
    }

    public static void installPackage(final File apkFile, final Context context){
        try {
            Uri uri = Uri.fromFile(apkFile);
            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            context.startActivity(intent);
            Log.i(IMEService.TAG, String.format("已安装应用包[%s]", apkFile.getName()));
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("安装应用包[%s]出错", apkFile.getName()), ex);
        }
    }

    public static void uninstallPackage(final String packageName, final Context context){
        if(getApplicationInfo(packageName, context) == null)return;;
        try {
            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(Intent.ACTION_DELETE);
            intent.setData(Uri.parse("package:" + packageName));
            context.startActivity(intent);
            Log.i(IMEService.TAG, String.format("已删除应用包[%s]", packageName));
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("删除应用包[%s]出错", packageName), ex);
        }
    }

    public static void runPackage(final String packageName, final Context context){
        if(getApplicationInfo(packageName, context) == null)return;;
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if(intent != null){
                context.startActivity(intent);
                Environment.recordAppLaunch(context, packageName);
            }
            Log.i(IMEService.TAG, String.format("已运行应用包[%s]", packageName));
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("运行应用包[%s]出错", packageName), ex);
        }
    }
    public static void runSystemPackage(final String packageName, final Context context){
        try {
            Intent intent = new Intent(packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(IMEService.TAG, String.format("已运行系统应用包[%s]", packageName));
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("运行系统应用包[%s]出错", packageName), ex);
        }
    }
    public static byte[] getAppIcon(String packageName, Context context){
        ApplicationInfo applicationInfo = getApplicationInfo(packageName, context);
        if(applicationInfo == null) return null;
        try {
            Drawable drawable = applicationInfo.loadIcon(context.getPackageManager());
            Bitmap bitmap = drawableToBitmap(drawable);
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, data);
            return data.toByteArray();
        } catch (Exception e) {
            Log.e("AppPackagesHelper", "getAppIcon " + packageName, e);
            return null;
        }
    }

    //以前这里直接强转成BitmapDrawable，但loadIcon()拿到的不一定是BitmapDrawable——
    //几乎所有targetSdk 26+的App用的都是自适应图标(AdaptiveIconDrawable，前景+
    //背景两层合成，不是一张位图)，矢量图标(VectorDrawable)同理，强转会直接抛
    //ClassCastException，导致/icon/请求失败，控制端网页上看到的就是图片加载失败的
    //占位图标，而不是真实的App图标。改成不管拿到什么Drawable，都统一画到一张新建
    //的Bitmap上，兼容位图/自适应图标/矢量图标等所有情况。
    private static Bitmap drawableToBitmap(Drawable drawable){
        if(drawable instanceof BitmapDrawable){
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if(bitmap != null) return bitmap;
        }
        int width = Math.max(drawable.getIntrinsicWidth(), 1);
        int height = Math.max(drawable.getIntrinsicHeight(), 1);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }
}
