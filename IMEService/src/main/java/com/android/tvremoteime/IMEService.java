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
import com.android.tvremoteime.accessibility.ScreenAccessibilityService;

import java.io.IOException;


public class IMEService extends InputMethodService implements View.OnClickListener{
	public static String TAG = "TVRemoteIME";
	public static String ACTION = "com.android.tvremoteime";

	//控制端"显示/隐藏电视软键盘"按键用：切换设置之后需要马上让软键盘窗口跟着变化，
	//不能像折叠键盘按键那样等下一次输入框重新获得焦点/按返回键才生效。
	private static IMEService instance;

	//调用方(RemoteServer的HTTP请求线程、IMEService自己的低频轮询)可能不在
	//主线程上，这里统一post到主线程再操作窗口，跟handleKeyEventOnMainThread
	//是同一个道理。
	//
	//直接调用showWindow(true)去显示，而不是updateInputViewShown()：后者内部
	//是"mShowInputRequested && onEvaluateInputViewShown()"，其中
	//mShowInputRequested是系统在客户端App主动请求/收起输入法时才会置位的
	//内部标志——控制端点"键盘"按键这种跟输入框聚焦生命周期完全无关的外部
	//触发，不会经过那条路径去置位这个标志，所以哪怕onEvaluateInputViewShown
	//的返回值已经变了，updateInputViewShown()算出来的结果也可能还是原来
	//那个不变的值，要等用户重新点一下输入框(触发一次真正的显示请求)才会
	//生效。showWindow是更底层、直接改变窗口实际可见性的方法，不经过那个
	//内部标志的限制。
	//
	//隐藏这一侧不直接调hideWindow()——实机验证下来单独调用不会立即生效
	//(现象上跟直接调updateInputViewShown()一样，要等一次真正的输入生命周期
	//事件才会真的隐藏)。改成复用返回键那条已经验证过确实能立即隐藏的路径：
	//finishInput()内部就是"onFinishInput(); hideWindow();"，配合hideWindowByKey
	//这个标记(见onEvaluateInputViewShown里的检查)，是这个类里唯一被验证过
	//能立即让软键盘窗口消失的方式，这里直接复用，不再自己另找一条路。
	public static void refreshKeyboardViewVisibility(){
		if(instance == null) return;
		instance.handler.post(new Runnable() {
			@Override
			public void run() {
				if(instance == null) return;
				boolean shouldShow = Environment.isKeyboardViewVisible(instance, RemoteServer.hasActiveClient());
				try {
					if(shouldShow){
						instance.showWindow(true);
					}else{
						instance.hideWindowByKey = true;
						instance.finishInput();
					}
				} catch (Exception ignored) {
					//showWindow/hideWindow在完全没有任何输入连接绑定时(比如电视上
					//还没有任何输入框被聚焦过)调用，部分系统版本上可能会抛异常——
					//这种情况下本来也没有窗口可显示/隐藏，忽略掉即可，不需要让
					//整个IME服务崩溃。
				}
			}
		});
	}

	//"客户端从活跃变不活跃"(心跳停了/标签页关掉)这个方向的软键盘自动刷新，
	//没有新的HTTP请求能触发，只能靠本地低频轮询自己发现：每隔一小段时间
	//检查一次RemoteServer.hasActiveClient()有没有从true变成false，变了就
	//主动刷新一次；间隔比RemoteServer那边判活的超时阈值短很多，保证转变
	//能比较及时地反映到软键盘显示状态上，同时又不会频繁到有明显开销。
	private static final long ACTIVE_CLIENT_POLL_INTERVAL_MS = 3000;
	private boolean lastKnownClientActive = false;
	private final Runnable activeClientPoller = new Runnable() {
		@Override
		public void run() {
			boolean nowActive = RemoteServer.hasActiveClient();
			if(lastKnownClientActive && !nowActive){
				refreshKeyboardViewVisibility();
			}
			lastKnownClientActive = nowActive;
			handler.postDelayed(this, ACTIVE_CLIENT_POLL_INTERVAL_MS);
		}
	};

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
		instance = this;

		//android.os.Debug.waitForDebugger();
		Environment.initToastHandler();

		RemoteServerFileManager.resetBaseDir(this);
		startRemoteServer();
		DLNAUtils.startDLNAService(this.getApplicationContext());
		MDnsHelper.start(this.getApplicationContext());
		handler.postDelayed(activeClientPoller, ACTIVE_CLIENT_POLL_INTERVAL_MS);
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
		//控制端"显示/隐藏电视软键盘"开关：默认值取决于"当前"有没有活跃的控制端
		//客户端(RemoteServer.hasActiveClient()，靠心跳判活，不是"历史上有没有
		//连过")，见Environment.isKeyboardViewVisible的说明。这个检查在mInputView
		//是否为null之前就直接短路返回，不影响下面依赖mInputView.isShown()的
		//方向键/回车/返回等D-pad导航逻辑——那些本来就是"没显示就不生效、直接
		//当成普通按键处理"，跟这里是同一个效果，不需要额外处理。
		if(!Environment.isKeyboardViewVisible(this, RemoteServer.hasActiveClient())) return false;
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

	//多任务(APP_SWITCH/Recents)键没有原生InputConnection替代方案
	//（PhoneWindowManager只在系统级输入分发时才特殊处理它，IME往聚焦控件
	//注入的合成按键走不到那一层——多任务键点了没反应就是这个原因，跟HOME键
	//需要专门用ACTION_MAIN+CATEGORY_HOME这个Intent兜底是同一类问题，只是
	//多任务键没有对应的公开Intent能直接兜底，只能靠ADB这种走系统级输入
	//管线的方式)，任何时候都应该优先尝试ADB(优先走无障碍、无障碍不行才落
	//到这里，见下面performRecents那段说明)；触控板的滑动/点击同理，走的是
	//SwipeCommand/TapCommand，不经过这个方法。但方向键/音量/主页/返回/菜单
	//这些键本来就有正常能用的原生注入路径——只有在本App不是默认输入法
	//（原生路径本来就用不了，MainActivity的"手动启动"按钮会显式走这条兜底
	//逻辑）时，才需要连它们也一起改道ADB。之前这里没做区分，只要AdbHelper的
	//实例对象存在（哪怕只是被控制页轮询ADB连接状态这种完全无关的操作顺手
	//创建出来的，实际根本没连上）就会把所有按键都吞进ADB队列，连不上ADB时
	//这些本来能正常工作的按键就全部失效了——这正是新增"ADB连接状态指示"
	//功能后按键突然全部失灵的根因。
	//（电源键之前也在这个列表里，用于实现开关屏——但那需要ADB才能同时支持
	//"睡眠"和"唤醒"两个方向，为此专门维护一整套ADB连接状态判断/展示逻辑，
	//只为了一个用得不算高频的功能不太划算。现在改成只保留"睡眠"这一半、
	//用无障碍的GLOBAL_ACTION_LOCK_SCREEN实现(见下面performSleep那段特判)，
	//完全不需要ADB，"唤醒"这个方向不再提供。）
	private boolean shouldRouteKeyThroughAdb(int keyCode){
		return keyCode == KeyEvent.KEYCODE_APP_SWITCH || !Environment.isDefaultIME(this);
	}

	//onKeyEventReceived的实际处理逻辑，统一在主线程Handler上执行(见
	//onKeyEventReceived里handler.post的说明)，不用再各自单独post。
	private void handleKeyEventOnMainThread(String keyCode, int keyAction){
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
		}else if("sleep".equalsIgnoreCase(keyCode)){
			//睡眠键：只走无障碍服务的GLOBAL_ACTION_LOCK_SCREEN这一条路径，
			//不需要ADB、不需要判断连接状态，无障碍服务没开启或触发失败就
			//什么都不做——不为了兜底再引入一整套ADB相关的逻辑。
			if(ScreenAccessibilityService.isServiceEnabled()){
				ScreenAccessibilityService.getInstance().performLockScreen();
			}
		}else if(String.valueOf(KeyEvent.KEYCODE_APP_SWITCH).equals(keyCode)
				&& keyAction == KEY_ACTION_PRESSED
				&& ScreenAccessibilityService.isServiceEnabled()
				&& ScreenAccessibilityService.getInstance().performRecents()){
			//多任务键优先走无障碍服务的GLOBAL_ACTION_RECENTS(不需要ADB，
			//跟"元素列表"用的是同一个ScreenAccessibilityService)；这里直接
			//比较原始字符串而不是走下面KeyEvent.keyCodeFromString(keyCode)
			//转换，是因为keyCodeFromString对纯数字字符串的解析行为不确定，
			//没必要为了这一个特判去依赖它。无障碍服务没开启、或触发失败时，
			//直接落到下面的通用分支，走已有的ADB/原生注入兜底逻辑。
		}else {
			final int kc = KeyEvent.keyCodeFromString(keyCode);
			if(kc != KeyEvent.KEYCODE_UNKNOWN){
				if(mInputView != null && KeyEventUtils.isKeyboardFocusEvent(kc) && mInputView.isShown()){
					if((keyAction == KEY_ACTION_PRESSED || keyAction == KEY_ACTION_DOWN)
							&& !handleKeyboardFocusEvent(kc)){
						if(!(shouldRouteKeyThroughAdb(kc) && isSendToAdbService(kc))) sendKeyCode(kc);
					}
				}
				else{
					long eventTime = SystemClock.uptimeMillis();
					InputConnection ic = getCurrentInputConnection();
					//FLAG_KEEP_TOUCH_MODE的文档原文是"set if we don't want the key
					//event to cause us to leave touch mode"——之前一直带着这个
					//标记，导致注入的方向键永远无法让系统退出"触摸模式"，而安卓
					//原生的D-pad焦点导航(比如系统设置这类PreferenceScreen界面)
					//通常只有在"非触摸模式"下才会真正移动焦点。这正是"方向键
					//上下有时候选不中设置界面的栏目，但用红外遥控选中一次之后
					//(触发了一次不带这个标记的真实按键、让系统退出触摸模式)
					//再用方向键就正常了"的根因。去掉这个标记，让注入的方向键
					//行为跟真实遥控器按键一致，第一次按也能正常触发焦点移动。
					switch (keyAction) {
						case KEY_ACTION_PRESSED:
							if(!(shouldRouteKeyThroughAdb(kc) && isSendToAdbService(kc))) sendKeyCode(kc);
							break;
						case KEY_ACTION_DOWN:
							if(!(shouldRouteKeyThroughAdb(kc) && isSendToAdbService(kc)) && ic != null) {
								ic.sendKeyEvent(new KeyEvent(eventTime, eventTime,
										KeyEvent.ACTION_DOWN, kc, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
										KeyEvent.FLAG_SOFT_KEYBOARD));
							}
							break;
						case KEY_ACTION_UP:
							if(ic != null) {
								ic.sendKeyEvent(new KeyEvent(eventTime, eventTime,
									KeyEvent.ACTION_UP, kc, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
									KeyEvent.FLAG_SOFT_KEYBOARD));
							}
							break;
					}
				}
			}
		}
	}

	private void startRemoteServer(){
		int basePort = RemoteServer.serverPort;
		do {
			mServer = new RemoteServer(RemoteServer.serverPort, this);
			mServer.setDataReceiver(new RemoteServer.DataReceiver() {
				@Override
				public void onKeyEventReceived(final String keyCode, final int keyAction) {
					//NanoHTTPD默认一个连接一条线程，控制端网页几乎总会有多个请求
					//前后脚过来(比如清空的/key请求和输入框防抖到期的/textLive请求)，
					//如果直接在各自的请求线程上操作InputConnection，多个线程同时
					//改同一个InputConnection是没有顺序保证的——这正是"点了清空，
					//要等下一次打字同步才看到效果、而且看起来像是清空和新文字一起
					//生效"这个现象的根因(两个请求线程的调用互相插队/覆盖了)。统一
					//post到这个Service自己的主线程Handler上串行执行，跟下面
					//isKeyboardFocusEvent那个分支本来就有的handler.post是同一个
					//道理，保证所有涉及InputConnection的操作严格按收到请求的
					//顺序、在同一个线程上依次执行。
					if(keyCode == null) return;
					handler.post(new Runnable() {
						@Override
						public void run() {
							handleKeyEventOnMainThread(keyCode, keyAction);
						}
					});
				}

				@Override
				public void onTextReceived(final String text) {
					if(text == null) return;
					handler.post(new Runnable() {
						@Override
						public void run() {
							if(!isSendToAdbService(text)) commitText(text);
						}
					});
				}

				@Override
				public void onComposingTextReceived(final String text) {
					if(text == null) return;
					handler.post(new Runnable() {
						@Override
						public void run() {
							//ADB遥控模式下没有"组字预览"这个概念（每次adb input text都是真敲
							//字符），实时同步只在本App就是当前激活输入法、有InputConnection
							//时才有意义，没有的话直接忽略，靠最终的onTextReceived提交兜底。
							InputConnection ic = getCurrentInputConnection();
							if(ic != null){
								ic.setComposingText(text, 1);
							}
						}
					});
				}

				@Override
				public void onMouseMoveReceived(int dx, int dy) {
					//触控板模拟鼠标只能靠adb的"input swipe"注入触摸手势，普通
					//InputConnection没有触摸事件的概念，走不通。
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
		if(instance == this) instance = null;
		handler.removeCallbacks(activeClientPoller);
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
