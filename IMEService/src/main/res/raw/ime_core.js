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
//触控板：拖动模拟鼠标移动（服务端换算成"adb shell input swipe"手势），
//轻触（没有明显拖动的按下+抬起）模拟点击（"adb shell input tap"）。
//这条路径依赖ADB连接，跟电源键是同一套机制。
//注意：前面这个语句结尾的分号不能省——上一句$(...).on(...)后面如果不加分号，
//JS会把下面这个IIFE的开头"("解析成"调用上一句返回值"，导致抛
//"$(...).on(...) is not a function"，把整个脚本执行中断在这里，后面所有的
//click绑定（包括主Tab/方向键触控板子Tab切换）都不会被注册——这正是实测中
//"点击Tab完全没反应"的根本原因。
;(function(){
	var pad = document.getElementById('touchpad');
	if(!pad) return;
	var active = false;
	var lastX = 0, lastY = 0;
	var totalMove = 0;
	var lastSendTime = 0;
	var sensitivity = 2.5; //触控板物理位移->电视屏幕像素位移的放大倍数
	var CLICK_MOVE_THRESHOLD = 8; //小于这个累计位移(px)才算"轻触"而不是"拖动"
	var SEND_INTERVAL_MS = 40;
	function sendMove(dx, dy){
		$.post("/mouseMove", {dx: Math.round(dx), dy: Math.round(dy)});
	}
	function sendClick(){
		vibrateShort();
		$.post("/mouseClick");
	}
	function start(x, y){
		active = true;
		lastX = x; lastY = y;
		totalMove = 0;
		pad.classList.add("pressed");
	}
	function move(x, y){
		if(!active) return;
		var dx = x - lastX, dy = y - lastY;
		lastX = x; lastY = y;
		totalMove += Math.abs(dx) + Math.abs(dy);
		var now = Date.now();
		if(now - lastSendTime >= SEND_INTERVAL_MS && (dx !== 0 || dy !== 0)){
			sendMove(dx * sensitivity, dy * sensitivity);
			lastSendTime = now;
		}
	}
	function end(){
		if(!active) return;
		active = false;
		pad.classList.remove("pressed");
		if(totalMove < CLICK_MOVE_THRESHOLD){
			sendClick();
		}
	}
	if(isSupportTouch){
		pad.addEventListener("touchstart", function(e){
			var t = e.touches[0];
			start(t.clientX, t.clientY);
		}, {passive:true});
		pad.addEventListener("touchmove", function(e){
			var t = e.touches[0];
			move(t.clientX, t.clientY);
			e.preventDefault();
		}, {passive:false});
		pad.addEventListener("touchend", function(){
			end();
		});
		pad.addEventListener("touchcancel", function(){
			end();
		});
	}else{
		pad.addEventListener("mousedown", function(e){
			start(e.clientX, e.clientY);
		});
		document.addEventListener("mousemove", function(e){
			move(e.clientX, e.clientY);
		});
		document.addEventListener("mouseup", function(){
			end();
		});
	}
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
$(".mode-tab").on("click", function(){
	var mode = $(this).attr("data-mode");
	$(".mode-tab").removeClass("active");
	$(this).addClass("active");
	$(".nav-mode").addClass("hide");
	$('.nav-mode[data-mode="' + mode + '"]').removeClass("hide");
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
reloadAppList();
loadFileList("");
getDiskSpace();
loadTVList();
loadTorrentItems();
showCurrentVersion();