package com.android.tvremoteime;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Point;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.tvremoteime.server.RemoteServer;
import com.android.tvremoteime.server.RemoteServerFileManager;
import com.android.tvremoteime.adb.AdbHelper;

import java.io.IOException;


public class IMEService extends InputMethodService implements View.OnClickListener{
	public static String TAG = "TVRemoteIME";
	public static String ACTION = "com.android.tvremoteime";

	private boolean capsOn = false;
	private ImageButton btnCaps = null;
	private View focusedView = null;
	private RelativeLayout mInputView = null;
	private boolean hideWindowByKey = false;

	private View helpDialog = null;
	private ImageView qrCodeImage = null;
	private TextView  addressView = null;

	private RemoteServer mServer = null;
	private LinearLayout qweLine = null;
	private LinearLayout asdLine = null;
	private LinearLayout zxcLine = null;

	private static final int SERVER_START_ERROR = 901;
	private static final int ERROR = 999;
	private static final int TOAST_MESSAGE = 1000;

	public static final int KEY_ACTION_PRESSED = 0;
	public static final int KEY_ACTION_DOWN = 1;
	public static final int KEY_ACTION_UP = 2;

	//触控板模拟鼠标用：屏幕分辨率（首次用到时才查询、缓存）与当前虚拟光标位置
	private int screenWidth = 0;
	private int screenHeight = 0;
	private int virtualCursorX = 0;
	private int virtualCursorY = 0;

	final Handler handler = new Handler();

	@Override
	public void onCreate() {
		super.onCreate();

		//android.os.Debug.waitForDebugger();
		Environment.initToastHandler();

		RemoteServerFileManager.resetBaseDir(this);
		startRemoteServer();
		DLNAUtils.startDLNAService(this.getApplicationContext());
		MDnsHelper.start(this.getApplicationContext());
		new AutoUpdateManager(this, this.handler);
		//xllib.DownloadManager.instance().init(this);

	}

	@Override
    public View onCreateInputView()  {
		if(Environment.needDebug){
			Environment.debug(TAG, "onCreateInputView.");
		}
    	mInputView = (RelativeLayout)getLayoutInflater().inflate(R.layout.keyboard, null);

		capsOn = true;
		btnCaps = mInputView.findViewById(R.id.btnCaps);
		qweLine = mInputView.findViewById(R.id.qweLine);
		asdLine = mInputView.findViewById(R.id.asdLine);
		zxcLine = mInputView.findViewById(R.id.zxcLine);

		helpDialog = mInputView.findViewById(R.id.helpDialog);
		qrCodeImage = helpDialog.findViewById(R.id.ivQRCode);
		addressView = helpDialog.findViewById(R.id.tvAddress);

		toggleCapsState(true);

        return mInputView; 
    }

	@Override
	public View onCreateCandidatesView() {
		if(Environment.needDebug){
			Environment.debug(TAG, "onCreateCandidatesView.");
		}
		return null;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if(Environment.needDebug){
			Environment.debug(TAG, "onStartCommand.");
		}

		onStart(intent, startId);
		return START_STICKY;
	}

	@Override
	public void onStartInput(EditorInfo attribute, boolean restarting) {
		if(Environment.needDebug){
			Environment.debug(TAG, "onStartInput " + " inputType: "
					+ String.valueOf(attribute.inputType) + " Restarting:"
					+ String.valueOf(restarting));
		}
		super.onStartInput(attribute, restarting);
	}

	@Override
	public void onFinishInputView(boolean finishingInput) {
		if (Environment.needDebug) {
			Environment.debug(TAG, "onFinishInputView." + " finishingInput: "
					+ String.valueOf(finishingInput));
		}
		super.onFinishInputView(finishingInput);
	}

	@Override
	public void onFinishCandidatesView(boolean finishingInput) {
		if (Environment.needDebug) {
			Environment.debug(TAG, "onFinishCandidatesView." + " finishingInput: "
					+ String.valueOf(finishingInput));
		}
		super.onFinishCandidatesView(finishingInput);
	}

	@Override
	public boolean onEvaluateInputViewShown() {
		if (Environment.needDebug) {
			Environment.debug(TAG, "onEvaluateInputViewShown.");
		}
		super.onEvaluateInputViewShown();
		if(hideWindowByKey){
			hideWindowByKey = false;
			return hideWindowByKey;
		}
		EditorInfo editorInfo = getCurrentInputEditorInfo();
		return !(editorInfo == null || editorInfo.inputType == EditorInfo.TYPE_NULL);
	}

	@Override
	public boolean onEvaluateFullscreenMode() {
		if (Environment.needDebug) {
			Environment.debug(TAG, "onEvaluateFullscreenMode.");
		}
		return false;
	}

	private boolean isSendToAdbService(Object data){
		if(AdbHelper.initService(getApplicationContext())){
			AdbHelper.getInstance().sendData(data);
			return true;
		}
		return false;
	}

	//电源键、多任务(APP_SWITCH/Recents)键都没有原生InputConnection替代方案
	//（PhoneWindowManager只在系统级输入分发时才特殊处理它们，IME往聚焦控件
	//注入的合成按键走不到那一层——多任务键点了没反应就是这个原因，跟HOME键
	//需要专门用ACTION_MAIN+CATEGORY_HOME这个Intent兜底是同一类问题，只是
	//多任务键没有对应的公开Intent能直接兜底，只能靠ADB这种走系统级输入
	//管线的方式），任何时候都应该优先尝试ADB；触控板的滑动/点击同理，走的是
	//SwipeCommand/TapCommand，不经过这个方法。但方向键/音量/主页/返回/菜单
	//这些键本来就有正常能用的原生注入路径——只有在本App不是默认输入法
	//（原生路径本来就用不了，MainActivity的"手动启动"按钮会显式走这条兜底
	//逻辑）时，才需要连它们也一起改道ADB。之前这里没做区分，只要AdbHelper的
	//实例对象存在（哪怕只是被控制页轮询ADB连接状态这种完全无关的操作顺手
	//创建出来的，实际根本没连上）就会把所有按键都吞进ADB队列，连不上ADB时
	//这些本来能正常工作的按键就全部失效了——这正是新增"ADB连接状态指示"
	//功能后按键突然全部失灵的根因。
	private boolean shouldRouteKeyThroughAdb(int keyCode){
		return keyCode == KeyEvent.KEYCODE_POWER || keyCode == KeyEvent.KEYCODE_APP_SWITCH
				|| !Environment.isDefaultIME(this);
	}

	private void startRemoteServer(){
		int basePort = RemoteServer.serverPort;
		do {
			mServer = new RemoteServer(RemoteServer.serverPort, this);
			mServer.setDataReceiver(new RemoteServer.DataReceiver() {
				@Override
				public void onKeyEventReceived(String keyCode, final int keyAction) {
					if(keyCode != null) {
						if("cls".equalsIgnoreCase(keyCode)){
							InputConnection ic = getCurrentInputConnection();
							if(ic != null) {
								//deleteSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE)在很多
								//InputConnection实现里会崩溃：BaseInputConnection内部用
								//"光标位置 + afterLength"计算删除终点，Integer.MAX_VALUE会导致int
								//溢出变成负数，最终变成"起点>终点"传入删除方法而抛异常——只有输入框
								//里已经有文本时才会真正触发这条路径，这正好对应"输完字再点清空才崩"
								//的现象。改为先取出光标前后的实际文本长度，再按真实长度删除，
								//彻底避免溢出。
								CharSequence before = ic.getTextBeforeCursor(5000, 0);
								CharSequence after = ic.getTextAfterCursor(5000, 0);
								ic.deleteSurroundingText(before != null ? before.length() : 0,
										after != null ? after.length() : 0);
							}
						}else {
							final int kc = KeyEvent.keyCodeFromString(keyCode);
							if(kc != KeyEvent.KEYCODE_UNKNOWN){
								if(mInputView != null && KeyEventUtils.isKeyboardFocusEvent(kc) && mInputView.isShown()){
									if(keyAction == KEY_ACTION_PRESSED || keyAction == KEY_ACTION_DOWN) {
										handler.post(new Runnable() {
											@Override
											public void run() {
												if (!handleKeyboardFocusEvent(kc)) {
													if(!(shouldRouteKeyThroughAdb(kc) && isSendToAdbService(kc))) sendKeyCode(kc);
												}
											}
										});
									}
								}
								else{
									long eventTime = SystemClock.uptimeMillis();
									InputConnection ic = getCurrentInputConnection();
									switch (keyAction) {
										case KEY_ACTION_PRESSED:
											if(!(shouldRouteKeyThroughAdb(kc) && isSendToAdbService(kc))) sendKeyCode(kc);
											break;
										case KEY_ACTION_DOWN:
											if(!(shouldRouteKeyThroughAdb(kc) && isSendToAdbService(kc)) && ic != null) {
												ic.sendKeyEvent(new KeyEvent(eventTime, eventTime,
														KeyEvent.ACTION_DOWN, kc, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
														KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
											}
											break;
										case KEY_ACTION_UP:
											if(ic != null) {
												ic.sendKeyEvent(new KeyEvent(eventTime, eventTime,
													KeyEvent.ACTION_UP, kc, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
													KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
											}
											break;
									}
								}
							}
						}
					}
				}

				@Override
				public void onTextReceived(String text) {
					if (text != null) {
						if(!isSendToAdbService(text))commitText(text);
					}
				}

				@Override
				public void onComposingTextReceived(String text) {
					//ADB遥控模式下没有"组字预览"这个概念（每次adb input text都是真敲
					//字符），实时同步只在本App就是当前激活输入法、有InputConnection
					//时才有意义，没有的话直接忽略，靠最终的onTextReceived提交兜底。
					InputConnection ic = getCurrentInputConnection();
					if(text != null && ic != null){
						ic.setComposingText(text, 1);
					}
				}

				@Override
				public void onMouseMoveReceived(int dx, int dy) {
					//触控板模拟鼠标跟电源键一样，只能靠adb的"input swipe"注入触摸手势，
					//普通InputConnection没有触摸事件的概念，走不通。
					ensureScreenSize();
					if(screenWidth <= 0 || screenHeight <= 0) return;
					int oldX = virtualCursorX, oldY = virtualCursorY;
					virtualCursorX = clampInt(virtualCursorX + dx, 0, screenWidth - 1);
					virtualCursorY = clampInt(virtualCursorY + dy, 0, screenHeight - 1);
					if(AdbHelper.initService(getApplicationContext())){
						AdbHelper.getInstance().sendData(new AdbHelper.SwipeCommand(oldX, oldY, virtualCursorX, virtualCursorY, 40));
					}
				}

				@Override
				public void onMouseClickReceived() {
					ensureScreenSize();
					if(screenWidth <= 0 || screenHeight <= 0) return;
					if(AdbHelper.initService(getApplicationContext())){
						AdbHelper.getInstance().sendData(new AdbHelper.TapCommand(virtualCursorX, virtualCursorY));
					}
				}
			});
			try {
				mServer.start();
				Environment.toastInHandler(this, getString(R.string.app_name)  + "远程服务已启动");
				Log.i(TAG, "远程服务创建成功！port=" + RemoteServer.serverPort);
				break;
			}catch (IOException ex){
				Log.e(TAG, "建立输入HTTP服务时出错", ex);
				RemoteServer.serverPort ++;
				mServer.stop();
			}
		}while (RemoteServer.serverPort < basePort + 50);
	}

	private boolean commitText(String text){
		InputConnection ic = getCurrentInputConnection();
		boolean flag = false;
		if (ic != null){
			if(Environment.needDebug) {
				Environment.debug(TAG, "commitText:" + text);
			}
			if(text.length() > 1 && ic.beginBatchEdit()){
				flag = ic.commitText(text, 1);
				ic.endBatchEdit();
			}else{
				flag = ic.commitText(text, 1);
			}
		}
		return flag;
	}
	private void ensureScreenSize(){
		if(screenWidth > 0 && screenHeight > 0) return;
		try {
			WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
			Display display = wm.getDefaultDisplay();
			Point size = new Point();
			display.getSize(size);
			screenWidth = size.x;
			screenHeight = size.y;
			virtualCursorX = screenWidth / 2;
			virtualCursorY = screenHeight / 2;
		} catch (Exception e) {
			Log.e(TAG, "获取屏幕分辨率失败，触控板功能不可用", e);
		}
	}

	private static int clampInt(int value, int min, int max){
		return value < min ? min : (value > max ? max : value);
	}

	private void sendKeyCode(int keyCode){
		if(Environment.needDebug) {
			Environment.debug(TAG, "send-key-code:" + keyCode);
		}
		if(keyCode == KeyEvent.KEYCODE_HOME){
			//拦截HOME键
			Intent i = new Intent(Intent.ACTION_MAIN);
			i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			i.addCategory(Intent.CATEGORY_HOME);
			this.startActivity(i);
		}else {
			sendDownUpKeyEvents(keyCode);
		}
	}
    
    public void onDestroy() {
		if (mServer != null && mServer.isStarting()){
            Log.i(TAG, "远程输入服务已停止！");
			mServer.stop();
		}
		DLNAUtils.stopDLNAService();
		MDnsHelper.stop();
		AdbHelper.stopService();
		Environment.toastInHandler(this, getString(R.string.app_name)  + "服务已停止");
    	super.onDestroy();    	
    }

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if(handleKeyboardFocusEvent(keyCode)) return true;
		if (Environment.needDebug) {
			Environment.debug(TAG, "keydown-event:" + keyCode);
		}
		//同步软键盘状态处理代码：不处理以下按键事件则有可能物理键盘字符输入与软键盘的大小写状态不同步
		if(keyCode == KeyEvent.KEYCODE_CAPS_LOCK) capsOn = !capsOn;
		if ((keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)) {
			if(commitText(String.valueOf(keyCode - KeyEvent.KEYCODE_0))) return true;
		} else if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
			if (commitText(String.valueOf((char) ((capsOn ? 65 : 97) + keyCode - KeyEvent.KEYCODE_A))))
				return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	private boolean handleKeyboardFocusEvent(int keyCode){
		if(mInputView != null) {
			if (Environment.needDebug) {
				Environment.debug(TAG, "handleKeyboardFocusEvent:" + keyCode);
			}
			switch (keyCode) {
				case KeyEvent.KEYCODE_DPAD_UP:
				case KeyEvent.KEYCODE_DPAD_DOWN:
				case KeyEvent.KEYCODE_DPAD_LEFT:
				case KeyEvent.KEYCODE_DPAD_RIGHT:
					if(mInputView.isShown()) {
						requestNextButtonFocus(keyCode);
						return true;
					}
					break;
				case KeyEvent.KEYCODE_ENTER:
				case KeyEvent.KEYCODE_DPAD_CENTER:
					if (mInputView.isShown() && focusedView != null) {
						clickButtonByKey(focusedView);
						return true;
					}
					break;
				case KeyEvent.KEYCODE_CAPS_LOCK:
					toggleCapsState(true);
					return true;
				case KeyEvent.KEYCODE_ESCAPE:
				case KeyEvent.KEYCODE_BACK:
					if (mInputView.isShown()){
						if(helpDialog != null && helpDialog.isShown()){
							helpDialog.setVisibility(View.GONE);
						}else {
							this.finishInput();
						}
						return true;
					}
					break;
			}
		}
		return false;
	}

	private void requestNextButtonFocus(int keyCode){
		if(focusedView == null){
			focusedView =  ((LinearLayout)mInputView.getChildAt(0)).getChildAt(0);
		}else {
			LinearLayout container = (LinearLayout)focusedView.getParent();
			int rootInde = mInputView.indexOfChild(container);
			int index = container.indexOfChild(focusedView);
			boolean isLasted = container.getChildCount() == (index + 1);
			switch (keyCode) {
				case KeyEvent.KEYCODE_DPAD_UP:
					rootInde --;
					if(rootInde < 0) rootInde = mInputView.getChildCount() - 2;
					container = (LinearLayout)mInputView.getChildAt(rootInde);
					if(index >= container.getChildCount()) index = isLasted ? container.getChildCount() - 1 : 0;
					break;
				case KeyEvent.KEYCODE_DPAD_DOWN:
					rootInde ++;
					if(rootInde >= (mInputView.getChildCount() - 1)) rootInde = 0;
					container = (LinearLayout)mInputView.getChildAt(rootInde);
					if(index >= container.getChildCount()) index = isLasted ? container.getChildCount() - 1 :  0;
					break;
				case KeyEvent.KEYCODE_DPAD_LEFT:
					index --;
					if(index < 0){
						rootInde --;
						if(rootInde < 0) rootInde = mInputView.getChildCount() - 2;
						container = (LinearLayout)mInputView.getChildAt(rootInde);
						index = container.getChildCount() - 1;
					}
					break;
				case KeyEvent.KEYCODE_DPAD_RIGHT:
					index ++;
					if(index >= container.getChildCount()){
						rootInde ++;
						if(rootInde >= (mInputView.getChildCount() - 1)) rootInde = 0;
						container = (LinearLayout)mInputView.getChildAt(rootInde);
						index = 0;
					}
					break;
			}
			focusedView = container.getChildAt(index);
		}

		focusedView.requestFocus();
		focusedView.requestFocusFromTouch();
	}
	private void finishInput(){
		this.onFinishInput();
		this.hideWindow();
		//this.onFinishInputView(true);
		//this.onFinishCandidatesView(true);
	}
	private void clickButtonByKey(final View v){
		switch (v.getId()) {
			case R.id.btnCaps:
				v.setBackgroundResource(capsOn ? R.drawable.key_pressed_on : R.drawable.key_pressed_off);
				break;
			case R.id.btnClose:
				this.hideWindowByKey = true;
				this.finishInput();
				return;
			default:
				v.setBackgroundResource(R.drawable.key_pressed);
				break;
		}
		clickButton(v, false);
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if(v == btnCaps){
					v.setBackgroundResource(capsOn ? R.drawable.key_on : R.drawable.key_off);
				}else{
					v.setBackgroundResource(R.drawable.key);
				}
				v.requestFocus();
			}
		}, 200);
	}
	private void clickButton(View v, boolean resetCapsButtonState){
		if(v instanceof Button){
			if(v.getId() == R.id.btnClose){
				this.finishInput();
			}else {
				commitText(((Button) v).getText().toString());
			}
		}else if(v instanceof ImageButton){
			switch (v.getId()){
				case R.id.btnEnter:
					sendKeyCode(KeyEvent.KEYCODE_ENTER);
					break;
				case R.id.btnSpace:
					sendKeyCode(KeyEvent.KEYCODE_SPACE);
					break;
				case R.id.btnDelete:
					sendKeyCode(KeyEvent.KEYCODE_DEL);
					break;
				case R.id.btnCaps:
					toggleCapsState(resetCapsButtonState);
					break;
				case R.id.btnHelp:
					showHelpDialog();
					break;
			}
		}
	}
	@Override
	public void onClick(View v) {
		clickButton(v, true);
		if(v.getId() != R.id.btnClose) {
			v.requestFocusFromTouch();
			focusedView = v;
		}
	}

	private void toggleCapsState(boolean resetCapsButtonState){
		capsOn = !capsOn;
		if(resetCapsButtonState)
			btnCaps.setBackgroundResource(capsOn ? R.drawable.key_on : R.drawable.key_off);
		resetButtonChar(qweLine);
		resetButtonChar(asdLine);
		resetButtonChar(zxcLine);
	}
	private void resetButtonChar(LinearLayout layout){
		for(int i =0; i<layout.getChildCount(); i++){
			View v = layout.getChildAt(i);
			if(v instanceof Button){
				Button b = (Button)v;
				if(capsOn){
					b.setText(b.getText().toString().toUpperCase());
				}else{
					b.setText(b.getText().toString().toLowerCase());
				}
			}
		}
	}

	private void showHelpDialog(){
		if(mServer == null) return;

        if(addressView.getText().length() == 0) {
            String version = AppPackagesHelper.getCurrentPackageVersion(this);
            TextView title = helpDialog.findViewById(R.id.title);
            title.setText(title.getText() + " " + version);
            String address = mServer.getServerAddress();
            String accessCode = Environment.getAccessCode(this);
            addressView.setText(address
                    + "\n固定地址：" + MDnsHelper.getAddress()
                    + "\n访问口令：" + accessCode);
            String encodedCode;
            try {
                encodedCode = java.net.URLEncoder.encode(accessCode, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                encodedCode = accessCode;
            }
            qrCodeImage.setImageBitmap(QRCodeGen.generateBitmap(address + "login?code=" + encodedCode, 300, 300));
        }

		helpDialog.setVisibility(View.VISIBLE);
	}

}
