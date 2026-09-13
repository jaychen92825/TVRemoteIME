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
import android.widget.Button;
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
    private View manualStartRow;
    private Button btnSetIME;
    private EditText testInputText;
    private volatile boolean checkingUpdate = false;
    //下面三个字段配合refreshStatus()识别"状态真的发生了变化"，而不是在
    //onClick里跳系统设置/选择器之后立刻同步检查——那些跳转都是异步的，
    //点击的瞬间用户还什么都没做，同步检查到的必然还是旧状态。
    private boolean statusInitialized = false;
    private boolean lastEnabled = false;
    private boolean lastIsDefault = false;
    //"设为默认"按钮在输入法还没启用时，会先跳去启用、标记这个字段，等
    //用户设置完返回、真的变成已启用但还不是默认时，自动帮着弹一次选择
    //默认输入法的对话框，不用用户自己再点一次"设为默认"。
    private boolean pendingAutoShowPicker = false;
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
        manualStartRow = this.findViewById(R.id.manualStartRow);
        btnSetIME = this.findViewById(R.id.btnSetIME);
        testInputText = this.findViewById(R.id.etTestInput);

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
                break;
            case R.id.btnSetIME:
                if(!Environment.isEnableIME(this)) {
                    Environment.toast(getApplicationContext(), "抱歉，请您先激活启用" + getString(R.string.keyboard_name) +"输入法！");
                    //跳系统设置页是异步的，用户这会儿还没操作，不可能立刻就变成
                    //已启用——真正该弹选择默认输入法对话框的时机是用户设置完
                    //返回本页之后，标记一下交给onResume/refreshStatus里处理，
                    //不用再逼用户回来后自己重新点一次"设为默认"。
                    pendingAutoShowPicker = true;
                    openInputMethodSettings();
                    return;
                }
                showInputMethodPickerSafely();
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
    private void showInputMethodPickerSafely(){
        try {
            ((InputMethodManager) getApplicationContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showInputMethodPicker();
        }catch (Exception ignored) {
            Environment.toast(getApplicationContext(), "抱歉，无法设置为系统默认输入法，请手动启动服务！");
        }
    }
    private void refreshStatus(){
        boolean enabled = Environment.isEnableIME(this);
        boolean isDefault = Environment.isDefaultIME(this);

        //"太棒了"这两条提示只在状态真的从false变成true时弹一次——不是每次
        //刷新只要为true就弹，否则光是切到后台再切回来、什么都没做，也会
        //重新提示一遍。statusInitialized之前(App刚打开的第一次刷新)不弹，
        //避免把"之前就已经启用/已是默认"的历史状态当成刚刚达成的成就。
        if(statusInitialized){
            if(enabled && !lastEnabled){
                Environment.toast(getApplicationContext(), "太棒了，您已经激活启用了" + getString(R.string.keyboard_name) +"输入法！");
            }
            if(isDefault && !lastIsDefault){
                Environment.toast(getApplicationContext(), "太棒了，" + getString(R.string.keyboard_name) +"已是系统默认输入法！");
            }
        }
        if(pendingAutoShowPicker && enabled && !isDefault){
            pendingAutoShowPicker = false;
            showInputMethodPickerSafely();
        }
        statusInitialized = true;
        lastEnabled = enabled;
        lastIsDefault = isDefault;

        imeEnabledStatusView.setText(enabled ? "已启用" : "未启用");
        imeEnabledStatusView.setTextColor(getResources().getColor(enabled ? R.color.status_ok : R.color.text_secondary));
        imeDefaultStatusView.setText(isDefault ? "已是默认" : "未设默认");
        imeDefaultStatusView.setTextColor(getResources().getColor(isDefault ? R.color.status_ok : R.color.text_secondary));

        //已经是默认输入法时"手动启动"这个兜底按钮就真用不上了（它自己的说明
        //文字也写着是"设置失败时"才用得到），一直显示只是让页面看起来还有一步
        //没做完。隐藏的同时要把原本指向它的D-pad焦点链路(nextFocusUp/Down)
        //也一起改到隐藏后的前后控件上——遥控器场景下没有触屏/鼠标，指向一个
        //GONE掉的View会导致这个方向直接焦点搜索失败，而不是自动跳过它。
        boolean showManualStart = !isDefault;
        manualStartRow.setVisibility(showManualStart ? View.VISIBLE : View.GONE);
        btnSetIME.setNextFocusDownId(showManualStart ? R.id.btnStartService : R.id.etTestInput);
        testInputText.setNextFocusUpId(showManualStart ? R.id.btnStartService : R.id.btnSetIME);

        String address = RemoteServer.getServerAddress(this);
        String accessCode = Environment.getAccessCode(this);
        //访问口令不再在这里重复展示一遍纯文字——下面就是可以直接编辑的
        //访问口令输入框，两个地方各显示一遍同一个值没有必要。
        addressView.setText(address + "\n固定地址：" + MDnsHelper.getAddress());
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

    //不是应用商店/系统应用，没法做到完全静默安装——系统的安装确认框这一步绕不过去。
    //这里不自己预判"允许安装未知来源"这个权限有没有开、开了就直接跳系统安装确认框，
    //没开就跳设置页——之前这么做过，但部分设备/ROM上canRequestPackageInstalls()
    //返回的状态跟设置里实际的开关状态不同步，导致哪怕用户已经在设置里开了权限，
    //这里判断出来还是"没开"，于是每次点检查更新都被重新赶去设置页，死循环出不来。
    //现在改成不管权限有没有开，都直接把安装Intent发出去：系统自带的安装器
    //(PackageInstaller)本身就会在权限没开时自动弹出"允许来自此来源"的确认页，
    //用户在那一页同意后安装器会自动接着往下走到正常的安装确认框，不需要跳回本App
    //重新点一次——这条路径由系统安装器自己判断权限状态，不会跟本App这边判断不一致。
    private void installApk(File apkFile){
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
