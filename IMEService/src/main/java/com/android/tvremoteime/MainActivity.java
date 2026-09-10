package com.android.tvremoteime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.content.FileProvider;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.tvremoteime.server.RemoteServer;
import com.android.tvremoteime.adb.AdbHelper;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class MainActivity extends Activity implements View.OnClickListener {

    private ImageView qrCodeImage;
    private TextView addressView;
    private TextView imeEnabledStatusView;
    private TextView imeDefaultStatusView;
    private EditText dlnaNameText;
    private EditText accessCodeText;
    private TextView updateStatusView;
    private volatile boolean checkingUpdate = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        qrCodeImage = this.findViewById(R.id.ivQRCode);
        addressView = this.findViewById(R.id.tvAddress);
        imeEnabledStatusView = this.findViewById(R.id.tvIMEEnabledStatus);
        imeDefaultStatusView = this.findViewById(R.id.tvIMEDefaultStatus);
        dlnaNameText = this.findViewById(R.id.etDLNAName);
        accessCodeText = this.findViewById(R.id.etAccessCode);
        updateStatusView = this.findViewById(R.id.tvUpdateStatus);

        this.setTitle(this.getResources().getString( R.string.app_name) + "  V" + AppPackagesHelper.getCurrentPackageVersion(this));
        ((TextView)findViewById(R.id.tvVersion)).setText("V" + AppPackagesHelper.getCurrentPackageVersion(this));
        dlnaNameText.setText(DLNAUtils.getDLNANameSuffix(this.getApplicationContext()));
        accessCodeText.setText(Environment.getAccessCode(this));

        //打开App时静默检查一次，没有更新/查不到都不打扰用户；找到更新会自动下载，
        //下载完成后跳系统安装确认框（这一步谁都跳不过，参见installApk里的说明）。
        checkForUpdate(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        //启用输入法/设为默认输入法都是跳到系统设置或系统选择器里操作的，
        //用户实际点击生效是在离开这个Activity之后，onClick里那次刷新看到的
        //还是旧状态；真正应该刷新的时机是操作完、返回到这个页面的时候。
        refreshStatus();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.btnUseIME:
                openInputMethodSettings();
                if(Environment.isEnableIME(this)){
                    Environment.toast(getApplicationContext(), "太棒了，您已经激活启用了" + getString(R.string.keyboard_name) +"输入法！");
                }
                break;
            case R.id.btnSetIME:
                if(!Environment.isEnableIME(this)) {
                    Environment.toast(getApplicationContext(), "抱歉，请您先激活启用" + getString(R.string.keyboard_name) +"输入法！");
                    openInputMethodSettings();
                    if(!Environment.isEnableIME(this)) return;
                }
                try {
                    ((InputMethodManager) getApplicationContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showInputMethodPicker();
                }catch (Exception ignored) {
                    Environment.toast(getApplicationContext(), "抱歉，无法设置为系统默认输入法，请手动启动服务！");
                }
                if(Environment.isDefaultIME(this)){
                    Environment.toast(getApplicationContext(), "太棒了，" + getString(R.string.keyboard_name) +"已是系统默认输入法！");
                }
                break;
            case R.id.btnStartService:
                startService(new Intent(IMEService.ACTION));
                if(!Environment.isDefaultIME(this)) {
                    if (AdbHelper.getInstance() == null) AdbHelper.createInstance();
                }
                Environment.toast(getApplicationContext(), "服务已手动启动，稍后可尝试访问控制端页面");
                break;
            case R.id.btnSetDLNA:
                DLNAUtils.setDLNANameSuffix(this.getApplicationContext(), dlnaNameText.getText().toString());
                break;
            case R.id.btnSetAccessCode:
                String newCode = accessCodeText.getText().toString().trim();
                if(newCode.isEmpty()){
                    Environment.toast(getApplicationContext(), "口令不能为空，留空会导致控制端无鉴权，未做修改。");
                    accessCodeText.setText(Environment.getAccessCode(this));
                }else{
                    Environment.setAccessCode(this, newCode);
                    Environment.toast(getApplicationContext(), "访问口令已修改！");
                }
                break;
            case R.id.btnCheckUpdate:
                checkForUpdate(true);
                break;
        }
        refreshStatus();
    }
    private void openInputMethodSettings(){
        try {
            this.startActivityForResult(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), 0);
        }catch (Exception ignored){
            Environment.toast(getApplicationContext(), "抱歉，无法激活启用输入法，请手动启动服务！");
        }
    }
    private void refreshStatus(){
        boolean enabled = Environment.isEnableIME(this);
        boolean isDefault = Environment.isDefaultIME(this);
        imeEnabledStatusView.setText(enabled ? "已启用" : "未启用");
        imeEnabledStatusView.setTextColor(getResources().getColor(enabled ? R.color.status_ok : R.color.text_secondary));
        imeDefaultStatusView.setText(isDefault ? "已是默认" : "未设默认");
        imeDefaultStatusView.setTextColor(getResources().getColor(isDefault ? R.color.status_ok : R.color.text_secondary));

        String address = RemoteServer.getServerAddress(this);
        String accessCode = Environment.getAccessCode(this);
        addressView.setText(address
                + "\n固定地址：" + MDnsHelper.getAddress()
                + "\n访问口令：" + accessCode);
        String loginUrl = address + "login?code=" + encodeParam(accessCode);
        qrCodeImage.setImageBitmap(QRCodeGen.generateBitmap(loginUrl, 130, 130));
    }

    //访问口令现在支持自定义，可能包含&/=/空格等字符，拼进URL查询参数前必须编码
    private static String encodeParam(String value){
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    //manualTrigger=true时(用户手动点"检查更新")，查不到/已是最新都要给出文字反馈；
    //manualTrigger=false时(App打开时静默检查一次)，查不到/网络不通就悄悄不做声，
    //不能因为查询失败就弹提示打扰用户——最常见原因就是当前网络访问不了github.com。
    private void checkForUpdate(final boolean manualTrigger){
        if(checkingUpdate) return;
        checkingUpdate = true;
        if(manualTrigger) updateStatusView.setText("检查中…");
        final int currentVersionCode = AppPackagesHelper.getCurrentVersionCode(this);
        new Thread(new Runnable(){
            @Override
            public void run(){
                final UpdateChecker.UpdateInfo info = UpdateChecker.fetchLatest();
                if(info == null || info.versionCode <= currentVersionCode){
                    runOnUiThread(new Runnable(){
                        @Override
                        public void run(){
                            checkingUpdate = false;
                            if(manualTrigger){
                                updateStatusView.setText(info == null ? "检查更新失败，请稍后重试" : "当前已是最新版本");
                            }
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable(){
                    @Override
                    public void run(){
                        updateStatusView.setText("发现新版本 " + info.versionName + "，正在下载…");
                    }
                });
                File dir = getExternalFilesDir(null);
                if(dir == null) dir = getCacheDir();
                final File apkFile = new File(dir, "update.apk");
                final boolean ok = UpdateChecker.downloadApk(info.apkDownloadUrl, apkFile);
                runOnUiThread(new Runnable(){
                    @Override
                    public void run(){
                        checkingUpdate = false;
                        if(ok){
                            updateStatusView.setText("已下载新版本 " + info.versionName + "，请确认安装");
                            installApk(apkFile);
                        }else{
                            updateStatusView.setText("下载新版本失败，请稍后重试");
                        }
                    }
                });
            }
        }).start();
    }

    //不是应用商店/系统应用，没法做到完全静默安装——系统的安装确认框这一步绕不过去，
    //这里能做的只是尽量减少到这一步之前的手动操作。Android 8+把"安装未知来源应用"
    //的权限从全局开关改成了按调用方App单独授权，没开的话直接跳系统设置页让用户开一次，
    //开完之后重新点"检查更新"会用回已经下载好的apk文件，不需要重新下载。
    private void installApk(File apkFile){
        if(Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()){
            Environment.toast(getApplicationContext(), "请先允许本应用安装未知来源应用，然后重新点击检查更新完成安装");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        Uri apkUri = Build.VERSION.SDK_INT >= 24
                ? FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile)
                : Uri.fromFile(apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(installIntent);
        } catch (Exception e) {
            Environment.toast(getApplicationContext(), "无法启动安装程序：" + e.getMessage());
        }
    }
}
