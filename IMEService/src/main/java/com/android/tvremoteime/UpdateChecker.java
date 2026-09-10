package com.android.tvremoteime;

import android.util.Log;

import com.android.tvremoteime.http.HTTPGet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * "检查更新"：读取GitHub Release，跟当前安装的versionCode比较，有更新就下载APK。
 *
 * 版本号来源约定：CI(.github/workflows/android-build.yml)在push到master且配置了
 * release签名密钥时，会把release APK发布成一个GitHub Release，tag固定用
 * "v<versionCode>"这个格式（不是"latest"这种滚动tag）——这样直接从tag名就能解析出
 * 版本号做数值比较，不需要额外接口/字段，也顺带保留了每个历史版本各自的Release记录。
 * GitHub的"/releases/latest" API返回的永远是最新发布的那一个（按发布时间，不含
 * draft/prerelease），因此这里固定读这一个接口即可，不需要遍历全部Release列表。
 */
public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String RELEASES_LATEST_API =
            "https://api.github.com/repos/jaychen92825/TVRemoteIME/releases/latest";

    public static class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final String apkDownloadUrl;

        public UpdateInfo(int versionCode, String versionName, String apkDownloadUrl){
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkDownloadUrl = apkDownloadUrl;
        }
    }

    /**
     * 同步网络请求，调用方自己负责放到后台线程里跑。返回null表示请求失败/解析失败/
     * 没有找到可用的apk资源——调用方应当把这些情况都当成"暂时没法判断，不打扰用户"处理，
     * 而不是报错，因为最常见的原因就是当前网络环境访问不了github.com。
     */
    public static UpdateInfo fetchLatest(){
        String json = HTTPGet.readString(RELEASES_LATEST_API);
        if(json == null) return null;
        try {
            JSONObject obj = new JSONObject(json);
            String tag = obj.optString("tag_name", "");
            if(!tag.startsWith("v")) return null;
            int versionCode;
            try {
                versionCode = Integer.parseInt(tag.substring(1));
            } catch (NumberFormatException e) {
                return null;
            }
            String versionName = obj.optString("name", tag);
            String apkUrl = null;
            JSONArray assets = obj.optJSONArray("assets");
            if(assets != null){
                for(int i = 0; i < assets.length(); i++){
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if(name.toLowerCase().endsWith(".apk")){
                        apkUrl = asset.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            if(apkUrl == null) return null;
            return new UpdateInfo(versionCode, versionName, apkUrl);
        } catch (Exception e) {
            Log.e(TAG, "解析releases/latest失败", e);
            return null;
        }
    }

    public static boolean downloadApk(String url, File targetFile){
        return HTTPGet.downloadFile(url, targetFile);
    }
}
