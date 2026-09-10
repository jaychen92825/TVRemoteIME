var isSupportTouch = "ontouchend" in document ? true : false;
function vibrateShort(){
	//部分浏览器（如iOS Safari）不支持震动反馈API，做个特性检测，不支持就静默跳过
	if(navigator.vibrate) navigator.vibrate(15);
}
var processbar1=$("#processbar1");
var processbar2=$("#processbar2");
var tabs = $('div[data-tab]');
var keyActionTimer = null;
var curKeyState = 0;
var curKeyCode = "";
var curPath = "";
var selectedPaths = [];
var selectedPathId = 0;
var fileOperItems = $('.file-oper-items');

function escapeHtml(str){
	return String(str == null ? "" : str).replace(/[&<>"']/g, function(c){
		switch(c){
			case '&': return '&amp;';
			case '<': return '&lt;';
			case '>': return '&gt;';
			case '"': return '&quot;';
			case "'": return '&#39;';
		}
	});
}
function encodePath(path){
	return String(path).split('/').map(encodeURIComponent).join('/');
}

function formatSize(size){
	if(size < 1024){
		return size + " B";
	}else if(size < (1024 * 1024)){
		return (size / 1024).toFixed(1) + " KB";
	}else if(size < (1024 * 1024 * 1024)){
		return (size / (1024 * 1024)).toFixed(1) + " MB";
	}else{
		return (size / (1024 * 1024 * 1024)).toFixed(1) + " GB";
	}
}
function parseTVData(text){
	var tv = [];
	var lines = text.split('\n');
	var offset = 0;
	while(offset < lines.length){
		var line = lines[offset].trim();
		if(line.length > 2 && line.substr(0, 1) == "[" && line.substr(line.length - 1, 1) == "]"){
			var name = line.substring(1, line.length - 1);
			var urls = [];
			offset ++;
			while(offset < lines.length){
				line = lines[offset].trim();
				if(line.length > 1){
					var c = line.substr(0, 1);
					if(c == '[') break;
					if(c != '#'){
						var p = line.indexOf('=');
						if(p > 0){
							var item = {
								"name":line.substring(0, p).trim(),
								"url": line.substring(p + 1).trim()
							};
							if(item.name.length && item.url.length) urls.push(item);
						}
					}
				}
				offset ++;
			}
			if(urls.length)tv.push({"name":name, "urls":urls});
		}else{
			offset ++;
		}
	}
	return tv;
}
function postKeyCode(keyCode){
	$.post("/key",{code:keyCode},function(data){
		console.log(data);
	});
}
function postKeyActionCode(keyCode, keyAction){
	curKeyCode = keyCode;
	curKeyState = keyAction;
	var action = function(){
		var path = keyAction == 1 ? "/keydown" : "/keyup";
		$.post(path,{code:keyCode},function(data){
			console.log(data);
			if(curKeyState == 1 && curKeyCode == keyCode){
				keyActionTimer = setTimeout(action, 100);
			}else{
				keyActionTimer = null;
			}
		});
	}
	if(keyAction == 2){
		if(keyActionTimer){
			clearTimeout(keyActionTimer);
			keyActionTimer = null;
		}
	}
	action();
}
function clickApp(id,type){
	var app=$("#app-"+id);
	if(2!=type||confirm("是否确认要卸载应用["+app.text()+"]？")){
		$.post(1==type?"/run":"/uninstall",{packageName:app.attr("data-packageName")},function(data){
			if("ok"==data&&2==type){
				setTimeout(reloadAppList,15e3);
			}
		});
	}
}
function postFileAction(action){
	if(selectedPaths.length == 0) return;

	var title = action == "copy" ? "是否确认要将所有选择的目录或者文件复制到当前目录下？" :
		        action == "cut" ? "是否确认要将所有选择的目录或者文件剪切到当前目录下？" :
		                          "是否确认要删除所有选择的目录或者文件？不可恢复！";
	if(confirm(title)){
		if(action == "delete" && !confirm("请再次确认是否要删除所有选择的目录或者文件？不可恢复！"))return;
		$.post("/file/" + action,{targetPath : curPath, paths:selectedPaths.join('|')},function(data){
			if("ok"==data){
				selectedPaths = [];
				fileOperItems.empty();
				$('.file-oper').addClass('hide');
				selectedPathId = 0;
				setTimeout(function(){
					loadFileList(curPath);
				},1000);
			}
		});
	}
}
function getDiskSpace(){
	$.get("/sdcard_stat", null, function(data){
		$('#diskSpace').html('存储总容量：' + formatSize(data.totalBytes) + '，可用容量：' + formatSize(data.availableBytes));
	});
}
function reloadAppList(){
	$.post("/apps",{system:$("#cbListSystem")[0].checked},function(data){
		var appList=$(".app-list");
		appList.empty();
		var html=[];
		for(var i=0;i<data.length;i++){
			var app=data[i];
			html.push('<div class="app-item" data-index="'+i+'" data-sysapp="'+(app.isSysApp?1:0)+'" title="'+escapeHtml(app.lable)+'">');
			html.push('<img src="/icon/'+encodeURIComponent(app.packageName)+'" class="app-icon" />');
			html.push('<div class="app-name'+(app.isSysApp?" blue":"")+'" id="app-'+i+'" data-packageName="'+escapeHtml(app.packageName)+'">'+escapeHtml(app.lable)+"</div>");
			html.push("</div>");
		}
		appList.html(html.join("\r\n"));
	});
}
function loadFileList(path){
	curPath = path;
	$('#curPath').text(curPath == '' ? '/' : curPath);
	$.get("/file/dir/" + encodeURIComponent(path),null,function(data){
		var fileList=$(".file-list");
		fileList.empty();
		var html=[];
		var fileDeleteChecked = $("#cbFileSelect")[0].checked;
		if(data.parent != undefined){
			html.push('<div class="file-item"><div class="file-icon-panel">');
			html.push('<img src="/ic_dl_folder.png" class="file-icon go-path" data-path="'+escapeHtml(data.parent)+'" />');
			html.push('</div><div class="file-name">..</div>');
			html.push('</div>');
		}
		for(var i=0;i<data.dirs.length;i++){
			var file=data.dirs[i];
			html.push('<div class="file-item"><div class="file-icon-panel">');
			html.push('<img src="/ic_dl_folder.png" class="file-icon go-path" data-path="'+escapeHtml(file.path)+'" />');
			html.push('</div><div class="file-name">'+escapeHtml(file.name)+'</div>');
			html.push('<div class="app-btn">');
			html.push('<input type="button" value="选择" class="fbtn2 app-btn1 select-file' + (fileDeleteChecked ? '' : ' hide') + '" data-type="1" data-name="'+escapeHtml(file.name)+'" data-path="'+escapeHtml(file.path)+'" />');
			html.push("</div>");
			html.push('</div>');
		}
		for(var i=0;i<data.files.length;i++){
			var file=data.files[i];
			html.push('<div class="file-item"><div class="file-icon-panel">');
			if(file.isMedia){
				html.push('<img src="ic_dl_video.png" class="file-icon play-media" border="0" data-uri="' + escapeHtml(file.fullPath) + '" />');
			}else{
				html.push('<img src="ic_dl_other.png" class="file-icon" border="0" />');
			}
			html.push('<div class="' + (file.isMedia ? 'media-size' : 'file-size') + '">' + formatSize(file.size) + '</div>');
			html.push('</div><div class="file-name">'+escapeHtml(file.name)+'</div>');
			html.push('<div class="app-btn">');
			html.push('<a href="/file/download/' + encodePath(file.path) + '" target="_blank" class="' + (fileDeleteChecked ? ' hide' : '') + '">');
			html.push('<input type="button" value="下载" class="fbtn1 app-btn1" />');
			html.push("</a>");
			html.push('\t  <input type="button" value="选择" class="fbtn2 app-btn1 select-file' + (fileDeleteChecked ? '' : ' hide') + '" data-type="2" data-name="'+escapeHtml(file.name)+'" data-path="'+escapeHtml(file.path)+'" />');
			html.push("</div>");
			html.push('</div>');
		}
		for(i=0;i<4;i++){
			html.push('<div class="file-item item-empty"></div>');
		}
		fileList.html(html.join("\r\n"));
	});
}
function addFile(type, name, path){
	for(var i=0; i<selectedPaths.length; i++){
		if(selectedPaths[i] == path) return;
	}
	selectedPaths.push(path);
	selectedPathId ++;
	var html = [];
	html.push('<div class="file-oper-item remove-file-item" data-id="' + selectedPathId + '" data-path="' + escapeHtml(path) + '" id="fileOperItem' + selectedPathId + '">');
	html.push('<div class="file-oper-name">');
	html.push(type == 1 ? "目录" : "文件");
	html.push("：");
	html.push(escapeHtml(name));
	html.push('</div><div class="file-oper-del">X</div></div>');
	fileOperItems.append(html.join(''));
	$('.file-oper').removeClass('hide');
}
function removeFile(id, path){
	var rid = -1;
	for(var i=0; i<selectedPaths.length; i++){
		if(selectedPaths[i] == path){
			rid = i;
			break;
		}
	}
	if(rid != -1){
		selectedPaths.splice(rid, 1);
	}
	$('#fileOperItem' + id).remove();
	if(selectedPaths.length == 0){
		selectedPathId = 0;
		$('.file-oper').addClass('hide');
	}
}
function loadTVList(){
	$.get("/tv.txt",null,function(text){
		var tvItems=$(".tv-items");
		tvItems.empty();
		$('#tvData').val(text);
		var data = parseTVData(text);
		var html=[];
		for(var i=0;i<data.length;i++){
			var tv=data[i];
			html.push('<div class="tv-item">');
			html.push(escapeHtml(tv.name));
			html.push('<br />');
			for(var j=0; j<tv.urls.length; j++){
				html.push('<a class="tv-source" data-video="' + escapeHtml(tv.urls[j].url) + '" onclick="playTV(this)">' + escapeHtml(tv.urls[j].name) + '</a>');
			}
			html.push('</div>');
		}
		tvItems.html(html.join("\r\n"));
	}, "text");
}
$("#btnEnter").on("click", function(){
	vibrateShort();
	var $input = $("#inputarea");
	var text = $input.val();
	if(text != ""){
		$input.val("");
		$.post("/text", {text: text}, function(){
			postKeyCode("66");
		});
	}else{
		postKeyCode("66");
	}
})
//输入框内容实时同步到电视端（对应输入法的组字预览状态，还没真正提交），
//加个小延迟避免每敲一下都发一次请求
var composingTimer = null;
$("#inputarea").on("input", function(){
	var text = $(this).val();
	if(composingTimer) clearTimeout(composingTimer);
	composingTimer = setTimeout(function(){
		$.post("/textLive", {text: text}, function(data){
			console.log(data);
		});
	}, 150);
});
//触控板/滚动条：拖动模拟鼠标移动或单方向滚动（服务端换算成"adb shell input
//swipe"手势），轻触（没有明显拖动的按下+抬起）模拟点击（"adb shell input
//tap"）。这条路径依赖ADB连接，跟电源键是同一套机制。
//注意：前面这个语句结尾的分号不能省——上一句$(...).on(...)后面如果不加分号，
//JS会把下面这个IIFE的开头"("解析成"调用上一句返回值"，导致抛
//"$(...).on(...) is not a function"，把整个脚本执行中断在这里，后面所有的
//click绑定（包括主Tab/方向键触控板子Tab切换）都不会被注册——这正是实测中
//"点击Tab完全没反应"的根本原因，记录一下避免以后再踩。
;(function(){
	var SENSITIVITY = 2.5; //物理位移->电视屏幕像素位移的放大倍数
	var CLICK_MOVE_THRESHOLD = 8; //小于这个累计位移(px)才算"轻触"而不是"拖动"
	var SEND_INTERVAL_MS = 40;
	function sendMove(dx, dy){
		$.post("/mouseMove", {dx: Math.round(dx), dy: Math.round(dy)});
	}
	function sendClick(){
		vibrateShort();
		$.post("/mouseClick");
	}
	//el: 触控区域元素；lockX/lockY: 只取横向或纵向位移(滚动条用)；
	//allowClick: 抬手时如果全程没怎么移动是否当作一次点击(触控板主区域用，
	//滚动条不需要，纯粹用来滚动)。
	function bindDragArea(el, lockX, lockY, allowClick){
		if(!el) return;
		var active = false;
		var lastX = 0, lastY = 0;
		var totalMove = 0;
		var lastSendTime = 0;
		function start(x, y){
			active = true;
			lastX = x; lastY = y;
			totalMove = 0;
			pendingDx = 0;
			pendingDy = 0;
			el.classList.add("pressed");
		}
		//节流窗口内被跳过的位移不能直接扔掉——之前的写法不管这次要不要真的发送，
		//lastX/lastY都会更新成当前坐标，两次真正发送之间夹着的那些touchmove
		//增量就凭空消失了。安卓原生touchmove通常每十几毫秒触发一次，40ms的节流
		//窗口下大部分增量都会被吞掉，实际发到服务端的位移远小于手指真实划动的
		//距离，表现出来就是"划半天挪不动/一直在原地"。改成把跳过的增量累积进
		//pendingDx/pendingDy，到了发送时机再连着这次的一起发出去，不丢量。
		var pendingDx = 0, pendingDy = 0;
		function move(x, y){
			if(!active) return;
			var dx = x - lastX, dy = y - lastY;
			lastX = x; lastY = y;
			totalMove += Math.abs(dx) + Math.abs(dy);
			if(lockX) dy = 0;
			if(lockY) dx = 0;
			pendingDx += dx;
			pendingDy += dy;
			var now = Date.now();
			if(now - lastSendTime >= SEND_INTERVAL_MS && (pendingDx !== 0 || pendingDy !== 0)){
				sendMove(pendingDx * SENSITIVITY, pendingDy * SENSITIVITY);
				pendingDx = 0;
				pendingDy = 0;
				lastSendTime = now;
			}
		}
		function end(){
			if(!active) return;
			active = false;
			el.classList.remove("pressed");
			//松手前最后一小段还没到发送时机的位移也不能丢，抬手时一起补发出去
			if(pendingDx !== 0 || pendingDy !== 0){
				sendMove(pendingDx * SENSITIVITY, pendingDy * SENSITIVITY);
				pendingDx = 0;
				pendingDy = 0;
			}
			if(allowClick && totalMove < CLICK_MOVE_THRESHOLD){
				sendClick();
			}
		}
		if(isSupportTouch){
			el.addEventListener("touchstart", function(e){
				var t = e.touches[0];
				start(t.clientX, t.clientY);
			}, {passive:true});
			el.addEventListener("touchmove", function(e){
				var t = e.touches[0];
				move(t.clientX, t.clientY);
				e.preventDefault();
			}, {passive:false});
			el.addEventListener("touchend", function(){
				end();
			});
			el.addEventListener("touchcancel", function(){
				end();
			});
		}else{
			el.addEventListener("mousedown", function(e){
				start(e.clientX, e.clientY);
			});
			document.addEventListener("mousemove", function(e){
				move(e.clientX, e.clientY);
			});
			document.addEventListener("mouseup", function(){
				end();
			});
		}
	}
	bindDragArea(document.getElementById('touchpad'), false, false, true);
	bindDragArea(document.getElementById('scrollRailV'), true, false, false);
	bindDragArea(document.getElementById('scrollRailH'), false, true, false);
})();
$('.app-list').on('click', '.app-item', function(){
	var idx = $(this).attr('data-index');
	if(idx == null || idx == "") return; //占位的空白格子
	vibrateShort();
	var isSysApp = $(this).attr('data-sysapp') === '1';
	var uninstallMode = $("#cbUninstall")[0].checked;
	clickApp(idx, (!isSysApp && uninstallMode) ? 2 : 1);
})
$('#btnShowTVEdit,#btnTVEdit,#btnTVCancel').on('click',function(){
	switch(this.id){
		case 'btnShowTVEdit':
			$(".tv-list").addClass("hide");
			$(".tv-edit").removeClass("hide");
			break;
		case 'btnTVCancel':
			$(".tv-edit").addClass("hide");
			$(".tv-list").removeClass("hide");
			break;
		case 'btnTVEdit':
			$.post("/tv.txt",{text:$('#tvData').val()},function(data){
				console.log(data);
				if(data == "ok"){
					alert('电视直播源已修改成功！');
					loadTVList();
				}else{
					alert('电视直播源修改失败！');
				}
				$(".tv-edit").addClass("hide");
				$(".tv-list").removeClass("hide");
			});
			break;
	}
});
$("#cbFileSelect").on("click",function(){
	if(this.checked){
		$(".fbtn1").addClass("hide");
		$(".fbtn2").removeClass("hide");
	}
	else{
		$(".fbtn1").removeClass("hide");
		$(".fbtn2").addClass("hide");
	}
})
$("div.tab").on("click", function(){
	var o = $(this);
	$(".cur").removeClass("cur");
	tabs.addClass("hide");
	tabs.filter('[data-tab="' + o.attr('data-rel')+ '"]').removeClass("hide");
	o.addClass('cur');
})
//方向键/元素列表这两个子Tab除了点标签切换，也支持在内容区左右滑动切换——
//点两个小标签来回切总感觉要"精确瞄准"，直接在当前显示的面板上一划更顺手。
var MODE_ORDER = ["dpad", "elements"];
function switchMode(mode){
	$(".mode-tab").removeClass("active");
	$('.mode-tab[data-mode="' + mode + '"]').addClass("active");
	$(".nav-mode").addClass("hide");
	$('.nav-mode[data-mode="' + mode + '"]').removeClass("hide");
	if(mode === "elements") loadScreenElements();
}
$(".mode-tab").on("click", function(){
	switchMode($(this).attr("data-mode"));
})
;(function(){
	var SWIPE_THRESHOLD = 40; //横向位移小于这个不算滑动，避免点击时手指的轻微抖动被误判成切换
	function switchToAdjacentMode(direction){
		var curMode = $(".mode-tab.active").attr("data-mode");
		var idx = MODE_ORDER.indexOf(curMode);
		var nextIdx = idx + direction;
		if(nextIdx < 0 || nextIdx >= MODE_ORDER.length) return; //已经是第一个/最后一个，划过头了不循环
		switchMode(MODE_ORDER[nextIdx]);
	}
	//只在touchend/mouseup时算一次总位移，中途完全不preventDefault——这样元素
	//列表内容较多需要上下滚动时，原生滚动行为不受影响，只有明显以横向为主的
	//滑动才会触发切换。
	function bindSwipe(el){
		var startX = 0, startY = 0, tracking = false;
		function start(x, y){ startX = x; startY = y; tracking = true; }
		function end(x, y){
			if(!tracking) return;
			tracking = false;
			var dx = x - startX, dy = y - startY;
			if(Math.abs(dx) < SWIPE_THRESHOLD || Math.abs(dy) > Math.abs(dx)) return;
			switchToAdjacentMode(dx < 0 ? 1 : -1);
		}
		if(isSupportTouch){
			el.addEventListener("touchstart", function(e){
				if(e.touches.length !== 1) return;
				start(e.touches[0].clientX, e.touches[0].clientY);
			}, {passive:true});
			el.addEventListener("touchend", function(e){
				var t = e.changedTouches[0];
				if(t) end(t.clientX, t.clientY);
			});
		}else{
			el.addEventListener("mousedown", function(e){ start(e.clientX, e.clientY); });
			el.addEventListener("mouseup", function(e){ end(e.clientX, e.clientY); });
		}
	}
	$(".nav-mode").each(function(){ bindSwipe(this); });
})();
//元素列表：读取无障碍服务识别出的当前屏幕可点击元素，点名字直接让那个控件
//执行它自己的点击逻辑（不区分是靠触摸还是遥控器焦点响应的），完全不依赖ADB，
//标准Android TV系统也能用；没开启无障碍服务时给一个能直接跳转到电视端
//"设置-无障碍"页面的按钮（复用/runSystem，跟"应用管理"里的系统设置入口
//是同一套机制）。
function loadScreenElements(){
	$("#elementsStatus").text("加载中…");
	$.post("/screenElements", null, function(data){
		var list = $("#elementsList");
		list.empty();
		if(!data || !data.enabled){
			$("#elementsStatus").text("无障碍服务未启用");
			list.html('<div class="elements-hint">需要先在电视盒子上"设置-无障碍"里开启"' +
				escapeHtml("小盒精灵") + '"服务，才能识别屏幕上的元素。<div class="btn" id="btnOpenAccessibilitySettings">去电视上开启</div></div>');
			return;
		}
		var elements = data.elements || [];
		if(elements.length === 0){
			$("#elementsStatus").text("已启用 · 当前屏幕没有识别到可点击元素");
			list.html('<div class="elements-hint">当前屏幕没有识别到可点击元素，切换一下电视画面后点"刷新"再试试。</div>');
			return;
		}
		$("#elementsStatus").text("已启用 · 共" + elements.length + "个元素");
		var html = [];
		for(var i = 0; i < elements.length; i++){
			html.push('<div class="element-item" data-id="' + elements[i].id + '" title="' + escapeHtml(elements[i].label) + '">' + escapeHtml(elements[i].label) + '</div>');
		}
		list.html(html.join(""));
	}, "json").fail(function(){
		$("#elementsStatus").text("获取失败，请重试");
	});
}
$("#btnRefreshElements").on("click", function(){
	vibrateShort();
	loadScreenElements();
})
$("#elementsList").on("click", ".element-item", function(){
	vibrateShort();
	var id = $(this).attr("data-id");
	$.post("/clickElement", {id: id}, function(){
		//点完屏幕大概率已经变了，稍等一下再自动刷新一次列表
		setTimeout(loadScreenElements, 600);
	});
})
$("#elementsList").on("click", "#btnOpenAccessibilitySettings", function(){
	$.post("/runSystem", {packageName: "android.settings.ACCESSIBILITY_SETTINGS"});
})
$("#btnCls").on("click",function(){
	vibrateShort();
	postKeyCode($(this).attr("data-key"))
})
$(".direction, #btnDel").on(isSupportTouch ? "touchstart" : "mousedown",function(){
		var o=$(this);
		o.addClass("pressed");
		vibrateShort();
		postKeyActionCode(o.attr("data-key"), 1);
		console.log("onkeydown:" + o.attr("data-key"));
})
$(".direction, #btnDel").on(isSupportTouch ? "touchend" : "mouseup",function(){
		var o=$(this);
		postKeyActionCode(o.attr("data-key"), 2);
		console.log("onkeyup:" + o.attr("data-key"));
})
$(".otherbtn").on(isSupportTouch ? "touchstart" : "mousedown", function() {
	var o = $(this);
	o.addClass("pressed");
	vibrateShort();
	postKeyCode(o.attr("data-key"));
})
$(".direction,.otherbtn").on(isSupportTouch ? "touchend touchmove" : "mouseup mousemove", function() {
	$(".direction,.otherbtn").removeClass("pressed");
})
$("#cbListSystem").on("click", reloadAppList);
$("#showSettings").on("click", function() {
	$.post("/runSystem", {
		packageName: 'android.settings.SETTINGS'
	}, function(data) {
		console.log(data)
	})
})
$("#btnPlay").on("click", function() {
	var url = $('#playUrl').val();
	if (url.length > 0 && (url.indexOf('http://') == 0 
		|| url.indexOf('https://') == 0 
		|| url.indexOf('thunder://') == 0 
		|| url.indexOf('ed2k://') == 0 
		|| url.indexOf('ftp://') == 0 
		|| url.indexOf('rtmp://') == 0 
		|| url.indexOf('rtmps://') == 0
		|| url.indexOf('mms://') == 0)){
			$.post("/play", {playUrl: url, "useSystem":$('#playUseSystem')[0].checked}, function(data) {
				console.log(data)
			})
	}else{
		alert('请输入正确的网络视频地址，只支持http/ftp/thunder/ed2k/rtmp/mms。');
	}
})
function playMedia(obj){
	$.post("/play", {playUrl: $(obj).attr('data-uri'), "useSystem":$('#playUseSystem')[0].checked}, function(data) {
		console.log(data)
	})
}
$('.file-list').on('click', '.go-path', function(){
	loadFileList($(this).attr('data-path'));
});
$('.file-list').on('click', '.select-file', function(){
	addFile(parseInt($(this).attr('data-type'), 10), $(this).attr('data-name'), $(this).attr('data-path'));
});
$('.file-list').on('click', '.play-media', function(){
	playMedia(this);
});
fileOperItems.on('click', '.remove-file-item', function(){
	removeFile($(this).attr('data-id'), $(this).attr('data-path'));
});
$('#stopPlay').on("click", function(){
	$.post("/playStop",null, function(data) {
		console.log(data)
	})
});
$('#playUseSystem').on("click", function(){
	if(this.checked){
		alert('调用外部播放器不会自动结束边下边播任务，结束播放后请手动点击停止！');
	}
	var time = new Date(9998, 1,1);
	if(!this.checked)time = new Date(1900, 1, 1);
	document.cookie = "playUseSystem=" + (this.checked ? "1" : "0") + "; expires=" + time.toGMTString();
});
$(function(){
	$('#playUseSystem')[0].checked = document.cookie.indexOf('playUseSystem=1') != -1;
});

$('#speedInterval').on("change", function(){
	$.post("/changePlayFFI", {speedInterval: this.value}, function(data) {
		console.log(data)
	})
});
function playTV(o){
	$.post("/play", {playUrl: $(o).attr('data-video'), "useSystem":$('#playUseSystem')[0].checked}, function(data) {
		console.log(data)
	})
}
$("#btnClear").on("click", function() {
	if(confirm("是否要删除所有传送的文件？")){
		$.post("/clearCache", null, function(data) {
			console.log(data);
			alert("传送的文件都已清除完毕！")
		})
	}
})
$("#upfile,#upfile2,#upfile3").change(function() {
	var id = this.id;
	var formData = new FormData;
	var file = this.files[0];
	var processbar = null;
	if(id == "upfile2"){
		formData.append("path", curPath);
		processbar = processbar2;
	}else if(id == "upfile"){
		formData.append("autoInstall", $('#cbAutoInstall')[0].checked);
		formData.append("useSystem", $('#playUseSystem')[0].checked);
		processbar = processbar1;
	}
	formData.append("file", file, encodeURI(file.uploadName || file.name));
	$.ajax({
		type: "POST",
		url: id == "upfile2" ? "/file/upload" : (id == "upfile3" ? "/torrent/upload" : "/upload"),
		dataType: "json",
		data: formData,
		processData: false,
		contentType: false,
		xhr: function() {
			var xhr = $.ajaxSettings.xhr();
			if(xhr.upload){
				xhr.upload.addEventListener("progress", function(e) {
					if(processbar){
						var p = Math.floor(100 * e.loaded / e.total) + "%";
						processbar.css({
							width: p
						}).text(p);
					}
					if(e.loaded == e.total) $(id).val("");
				}, false);
			}
			return xhr;
		},
		beforeSend: function() {
			if(processbar){
				processbar.css({
					width: "1%"
				}).text("")
			}
		},
		success: function(data) {
			if(data.success){
				if(id == "upfile2"){
					loadFileList(curPath);
					alert("文件已成功上传到当前目录。");
				}else if(id == "upfile3"){
					alert("种子文件已上传并解析，请选择要播放的视频文件。");
					addTorrentItems(data);
				}else{
					if($('#cbAutoInstall')[0].checked && file.name.indexOf(".apk") != -1){
						alert("APK包已传送到TV盒子并执行安装，请留意TV屏幕的安装请求。");
					}else{
						alert("文件已成功传送到TV盒子，存储于：" + data.filePath);
					}
				}
			}else{
				alert("抱歉，文件上传失败！");
			}
		}
	});
});
$('#btnPlayTorrent').on('click', function(){
	var torrentItems = $("#torrentItems");
	var videoIndex = torrentItems.val();
	if(videoIndex != ''){
		$.post('/torrent/play', {"videoIndex":videoIndex, "useSystem":$('#playUseSystem')[0].checked}, function(data){
			console.log(data);
		});
	}
});
function addTorrentItems(data){
	var torrentItems = $("#torrentItems");
	torrentItems.empty();
	if(data.files && data.files.length){
		for(var i=0; i<data.files.length; i++){
			var f = data.files[i];
			torrentItems.append("<option value='" + encodeURIComponent(f.index) + "'>" + escapeHtml(f.name) + "(" + formatSize(f.size) + ")</option>");
		}
		torrentItems.val(data.files[0].index);
	}
}
function loadTorrentItems(){
	$.post('/torrent/data', null, function(data){
		if(data.success)addTorrentItems(data);
	});
}

function showCurrentVersion() {
	$.get('/version', function(version){
		$('#curVer').text(version);
	});
}
//轻量提示条：短暂显示一行文字然后自动消失，用来在具体操作失败时给个理由，
//不用像之前那样常驻一个状态栏一直占地方。
var miniToastTimer = null;
function showMiniToast(text){
	var el = $("#miniToast");
	if(el.length === 0){
		el = $('<div id="miniToast" class="mini-toast"></div>').appendTo("body");
	}
	el.text(text).addClass("show");
	clearTimeout(miniToastTimer);
	miniToastTimer = setTimeout(function(){ el.removeClass("show"); }, 2200);
}
//电源键是唯一一个平时就依赖ADB才能生效的按键（见IMEService里
//shouldRouteKeyThroughAdb的说明），没必要为了这一个键常驻显示/轮询一个
//ADB连接状态栏——只在真的按了电源键、且这时候ADB确实没连上时，才提示一下
//"为什么电源键没反应"，其它时候什么都不显示，不占地方也不用一直请求接口。
$("#power-btn").on(isSupportTouch ? "touchstart" : "mousedown", function(){
	$.get("/adbStatus", function(data){
		if(!data || !data.connected){
			showMiniToast("ADB未连接，电源键暂不可用");
		}
	}, "json");
})
reloadAppList();
loadFileList("");
getDiskSpace();
loadTVList();
loadTorrentItems();
showCurrentVersion();