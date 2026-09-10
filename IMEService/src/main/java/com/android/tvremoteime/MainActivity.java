package com.android.tvremoteime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.tvremoteime.server.RemoteServer;
import com.android.tvremoteime.adb.AdbHelper;

public class MainActivity extends Activity implements View.OnClickListener {

    private ImageView qrCodeImage;
    private TextView addressView;
    private TextView imeEnabledStatusView;
    private TextView imeDefaultStatusView;
    private EditText dlnaNameText;
    private EditText accessCodeText;
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

        this.setTitle(this.getResources().getString( R.string.app_name) + "  V" + AppPackagesHelper.getCurrentPackageVersion(this));
        ((TextView)findViewById(R.id.tvVersion)).setText("V" + AppPackagesHelper.getCurrentPackageVersion(this));
        dlnaNameText.setText(DLNAUtils.getDLNANameSuffix(this.getApplicationContext()));
        accessCodeText.setText(Environment.getAccessCode(this));
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
        String loginUrl = address + "login.html#code=" + accessCode;
        qrCodeImage.setImageBitmap(QRCodeGen.generateBitmap(loginUrl, 130, 130));
    }
}
