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
var mediaSources = [];
var currentMediaSourceKey = '';
var mediaView = 'browse';
var mediaSection = 'browse';
var mediaBrowseScrollTop = 0;
var currentMediaDetail = null;
var renderedMediaItems = [];
var mediaFolderStack = [];
var currentMediaCategoryId = '';
var mediaDisplayMode = 'grid';
var mediaPlaybackPollTimer = null;
var mediaPlaybackDragging = false;
var mediaPlaybackActive = false;
var mediaPlaybackHasSession = false;
var mediaPlaybackPlaying = false;
var mediaPlaybackPlayingPendingUntil = 0;
var mediaSearchAllItems = [];
var mediaSearchFiltersAvailable = false;
var mediaSearchStatusBase = '';
var mediaPage = 1;
var mediaPageId = '';
var mediaPageMode = '';
var mediaPageName = '';
var mediaLoadingMore = false;
var mediaHasMore = false;
var mediaPaginationObserver = null;
try {
	mediaDisplayMode = localStorage.getItem('mediaDisplayMode') === 'list' ? 'list' : 'grid';
} catch(e) {}

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
	var fallback = function(){
		$.post("/key",{code:keyCode},function(data){
			console.log(data);
		});
	};
	if(keyCode === "21" || keyCode === "22" || keyCode === "23"){
		$.post("/player/control", {code:keyCode, action:"press"}, function(data){
			if($.trim(data) !== "handled") fallback();
		}).fail(fallback);
	}else{
		fallback();
	}
}
function postKeyActionCode(keyCode, keyAction){
	curKeyCode = keyCode;
	curKeyState = keyAction;
	var shouldRepeat = keyCode === "19" || keyCode === "20" || keyCode === "21" || keyCode === "22" || keyCode === "67";
	var action = function(){
		var path = keyAction == 1 ? "/keydown" : "/keyup";
		var onComplete = function(data){
			console.log(data);
			if(shouldRepeat && curKeyState == 1 && curKeyCode == keyCode){
				var delay = $.trim(data) === 'handled' && (keyCode === "21" || keyCode === "22") ? 350 : 100;
				keyActionTimer = setTimeout(action, delay);
			}else{
				keyActionTimer = null;
			}
		};
		var fallback = function(){
			$.post(path,{code:keyCode},onComplete);
		};
		if(keyCode === "21" || keyCode === "22" || keyCode === "23"){
			$.post("/player/control", {code:keyCode, action:keyAction == 1 ? "down" : "up"}, function(data){
				if($.trim(data) === "handled"){
					onComplete(data);
				}else{
					fallback();
				}
			}).fail(fallback);
		}else{
			fallback();
		}
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
			if("ok"==data){
				if(2==type){
					setTimeout(reloadAppList,15e3);
				}else{
					//打开电视上的App后，下一步基本都是要在这个App界面里操作
					//（方向键选内容、返回等），直接跳回"输入遥控"页，不用
					//用户自己再点一次Tab切换。
					$('div.tab[data-rel="controls"]').trigger('click');
				}
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
			html.push('<div class="app-star'+(app.starred?' active':'')+'" data-packagename="'+escapeHtml(app.packageName)+'" data-starred="'+(app.starred?1:0)+'">★</div>');
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
			html.push('</div><div class="file-name go-path" data-path="'+escapeHtml(data.parent)+'">..</div>');
			html.push('</div>');
		}
		for(var i=0;i<data.dirs.length;i++){
			var file=data.dirs[i];
			html.push('<div class="file-item"><div class="file-icon-panel">');
			html.push('<img src="/ic_dl_folder.png" class="file-icon go-path" data-path="'+escapeHtml(file.path)+'" />');
			html.push('</div><div class="file-name go-path" data-path="'+escapeHtml(file.path)+'">'+escapeHtml(file.name)+'</div>');
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
function mediaMessage(text){
	$('#mediaStatus,#mediaConfigStatus').text(text);
}
function mediaStateHtml(title, message, action, label, loading){
	var html = ['<div class="media-empty media-state'+(loading ? ' loading' : '')+'">'];
	if(loading) html.push('<span class="media-state-spinner" aria-hidden="true"></span>');
	if(title) html.push('<div class="media-state-title">'+escapeHtml(title)+'</div>');
	if(message) html.push('<div class="media-state-copy">'+escapeHtml(message)+'</div>');
	if(action && label) html.push('<button type="button" class="media-state-action" data-media-action="'+escapeHtml(action)+'">'+escapeHtml(label)+'</button>');
	html.push('</div>');
	return html.join('');
}
function rememberMediaBrowsePosition(){
	if(mediaView === 'browse') mediaBrowseScrollTop = $('.container').scrollTop() || 0;
}
function showMediaBrowse(restorePosition){
	mediaView = 'browse';
	$('.media-panel').removeClass('media-detail-active media-settings-active');
	$('#mediaToolbar,#mediaLibraryNav,#mediaStatus,#mediaCategories,#mediaGrid').removeClass('hide');
	$('#mediaPagination').toggleClass('hide', !mediaHasMore);
	$('#mediaCategories').toggleClass('hide', mediaSection !== 'browse');
	$('#mediaFilters').toggleClass('hide', !(mediaSection === 'browse' && mediaSearchFiltersAvailable));
	$('#mediaDetail,#mediaSettingsView').addClass('hide');
	updateContainerWidth();
	setTimeout(function(){
		$('.container').scrollTop(restorePosition ? mediaBrowseScrollTop : 0);
	}, 0);
}
function showMediaDetailView(){
	rememberMediaBrowsePosition();
	mediaView = 'detail';
	$('.media-panel').removeClass('media-settings-active').addClass('media-detail-active');
	$('#mediaToolbar,#mediaLibraryNav,#mediaCategories,#mediaFilters,#mediaGrid,#mediaSettingsView,#mediaPagination').addClass('hide');
	$('#mediaStatus,#mediaDetail').removeClass('hide');
	updateContainerWidth();
	$('.container').scrollTop(0);
}
function showMediaSettingsView(){
	rememberMediaBrowsePosition();
	mediaView = 'settings';
	$('.media-panel').removeClass('media-detail-active').addClass('media-settings-active');
	$('#mediaToolbar,#mediaLibraryNav,#mediaStatus,#mediaCategories,#mediaFilters,#mediaGrid,#mediaDetail,#mediaPagination').addClass('hide');
	$('#mediaSettingsView').removeClass('hide');
	updateContainerWidth();
	$('.container').scrollTop(0);
}

function mediaScore(item){
	var score = String(item.score || '').trim();
	if(!score){
		var remark = String(item.remark || '').trim();
		var match = remark.match(/(?:^|\s)(10(?:\.0)?|[0-9](?:\.[0-9])?)(?:分|$)/);
		if(match) score = match[1];
	}
	return score;
}
function mediaPoster(item, overlay){
	var poster;
	if(item.pic){
		poster = '<img src="/media/image?url='+escapeHtml(encodeURIComponent(item.pic))+'&sourceKey='+escapeHtml(encodeURIComponent(item.sourceKey||''))+'" class="media-poster" loading="lazy" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" />' +
			'<div class="media-poster media-poster-empty" style="display:none">▶</div>';
	}else{
		poster = '<div class="media-poster media-poster-empty">▶</div>';
	}
	if(!overlay) return poster;
	var score = mediaScore(item);
	var duration = Math.max(0, Number(item.duration) || 0);
	var position = Math.max(0, Number(item.position) || 0);
	var progress = duration > 0 ? Math.max(0, Math.min(100, position * 100 / duration)) : 0;
	var progressHtml = progress > 0 ? '<span class="media-progress-track"><span class="media-progress-fill" style="width:'+progress.toFixed(1)+'%"></span></span>' : '';
	return '<div class="media-poster-wrap">'+poster+(score ? '<span class="media-score">'+escapeHtml(score)+'</span>' : '')+progressHtml+'</div>';
}
function mediaHistoryProgress(history){
	if(!history) return 0;
	var duration = Math.max(0, Number(history.duration) || 0);
	var position = Math.max(0, Number(history.position) || 0);
	return duration > 0 ? Math.max(0, Math.min(100, position * 100 / duration)) : 0;
}
function mediaFindEpisode(item, playId, flag){
	var episodes = item && item.episodes ? item.episodes : [];
	for(var i=0;i<episodes.length;i++){
		var ep = episodes[i];
		if(String(ep.playId || '') === String(playId || '') && (!flag || String(ep.flag || '') === String(flag))) return ep;
	}
	return null;
}
function renderMediaSources(data){
	mediaSources = data.sources || [];
	$('#mediaConfigUrl').val(data.url || '');
	var previous = currentMediaSourceKey;
	var html = [];
	for(var i=0;i<mediaSources.length;i++){
		var source = mediaSources[i];
		if(!source.supported) continue;
		html.push('<option value="'+escapeHtml(source.key)+'">'+escapeHtml(source.name || source.key)+'</option>');
	}
	$('#mediaSourceSelect').html(html.join(''));
	currentMediaSourceKey = sourceByKey(previous) ? previous : (data.defaultSourceKey || firstSupportedSourceKey());
	$('#mediaSourceSelect').val(currentMediaSourceKey);
	if(!data.url){
		mediaMessage('输入配置 URL 后连接。支持 type-0 API 和 type-3 csp jar 源。');
	}else{
		mediaMessage('已加载 '+(data.totalSources || 0)+' 个源，支持 '+(data.supportedSources || 0)+' 个，暂未支持 '+(data.unsupportedSources || 0)+' 个。');
	}
}
function sourceByKey(key){
	for(var i=0;i<mediaSources.length;i++){
		if(mediaSources[i].key === key && mediaSources[i].supported) return mediaSources[i];
	}
	return null;
}
function firstSupportedSourceKey(){
	for(var i=0;i<mediaSources.length;i++){
		if(mediaSources[i].supported) return mediaSources[i].key;
	}
	return '';
}
function hideMediaSearchFilters(){
	mediaSearchAllItems = [];
	mediaSearchFiltersAvailable = false;
	mediaSearchStatusBase = '';
	$('#mediaFilterType,#mediaFilterYear').val('');
	$('#mediaFilters').addClass('hide');
}
function mediaUniqueValues(items, key){
	var seen = {};
	var values = [];
	for(var i=0;i<items.length;i++){
		var value = String(items[i] && items[i][key] || '').trim();
		if(!value || seen[value]) continue;
		seen[value] = true;
		values.push(value);
	}
	return values;
}
function renderMediaSearchFilters(items){
	mediaSearchAllItems = items || [];
	var types = mediaUniqueValues(mediaSearchAllItems, 'type').sort();
	var years = mediaUniqueValues(mediaSearchAllItems, 'year').sort(function(a, b){
		var an = parseInt(a, 10);
		var bn = parseInt(b, 10);
		if(!isNaN(an) && !isNaN(bn)) return bn - an;
		return b.localeCompare(a);
	});
	var typeUseful = types.length > 1;
	var yearUseful = years.length > 1;
	var typeHtml = ['<option value="">全部类型</option>'];
	var yearHtml = ['<option value="">全部年份</option>'];
	for(var i=0;i<types.length;i++) typeHtml.push('<option value="'+escapeHtml(types[i])+'">'+escapeHtml(types[i])+'</option>');
	for(var j=0;j<years.length;j++) yearHtml.push('<option value="'+escapeHtml(years[j])+'">'+escapeHtml(years[j])+'</option>');
	$('#mediaFilterType').html(typeHtml.join('')).toggleClass('hide', !typeUseful);
	$('#mediaFilterYear').html(yearHtml.join('')).toggleClass('hide', !yearUseful);
	mediaSearchFiltersAvailable = typeUseful || yearUseful;
	$('#mediaFilters').toggleClass('hide', !mediaSearchFiltersAvailable || mediaView !== 'browse' || mediaSection !== 'browse');
}
function applyMediaSearchFilters(){
	var type = $('#mediaFilterType').val() || '';
	var year = $('#mediaFilterYear').val() || '';
	var filtered = [];
	for(var i=0;i<mediaSearchAllItems.length;i++){
		var item = mediaSearchAllItems[i];
		if(type && String(item.type || '') !== type) continue;
		if(year && String(item.year || '') !== year) continue;
		filtered.push(item);
	}
	renderMediaGrid(filtered);
	mediaMessage((type || year) ? mediaSearchStatusBase+' · 筛选后 '+filtered.length+' 条' : mediaSearchStatusBase);
}
function loadMediaConfig(){
	$.get('/media/config', null, function(data){
		renderMediaSources(data);
		if(data.supportedSources > 0) loadMediaHome();
		else showMediaReady();
	}, 'json').fail(function(){
		mediaMessage('媒体配置读取失败，请稍后重试。');
		selectMediaSection('browse');
		showMediaBrowse(false);
		$('#mediaGrid').html(mediaStateHtml('媒体配置读取失败', '请检查电视与手机连接后重试。', 'config', '重新读取'));
	});
}
function hasHomeSource(){
	return !!sourceByKey(currentMediaSourceKey || firstSupportedSourceKey());
}
function showMediaReady(){
	selectMediaSection('browse');
	showMediaBrowse(false);
	$('#mediaDetail').empty();
	if(hasHomeSource()){
		loadMediaHome();
	}else if(mediaSources.length){
		$('#mediaGrid').html(mediaStateHtml('没有可用点播源', '可以在媒体配置中切换源或更换配置。', 'settings', '打开媒体配置'));
	}else{
		$('#mediaGrid').html(mediaStateHtml('还没有媒体源', '先添加一个 TVBox-compatible 配置地址。', 'settings', '打开媒体配置'));
	}
}
function loadMediaHome(){
	resetMediaPagination();
	mediaFolderStack = [];
	currentMediaCategoryId = '';
	selectMediaSection('browse');
	showMediaBrowse(false);
	hideMediaSearchFilters();
	$('#mediaSearchInput').val('');
	$('#mediaDetail').empty();
	$('#mediaCategories').empty();
	if(!hasHomeSource()){
		$('#mediaGrid').html(mediaStateHtml('没有可用点播源', '可以在媒体配置中切换源或更换配置。', 'settings', '打开媒体配置'));
		return;
	}
	currentMediaSourceKey = $('#mediaSourceSelect').val() || currentMediaSourceKey || firstSupportedSourceKey();
	$('#mediaGrid').html(mediaStateHtml('正在加载首页', '', '', '', true));
	$.ajax({url:'/media/home', data:{sourceKey:currentMediaSourceKey}, dataType:'json', timeout:65000, success:function(data){
		if(data && data.success === false){
			mediaMessage('源加载失败：'+(data.message || '未知错误'));
			$('#mediaGrid').html(mediaStateHtml('这个源加载失败', data.message || '可以重试，或在设置中切换其他源。', 'home', '重试'));
			return;
		}
		currentMediaSourceKey = data.sourceKey || currentMediaSourceKey;
		$('#mediaSourceSelect').val(currentMediaSourceKey);
		renderMediaCategories(data.categories || [], '');
		mediaMessage((data.sourceName || '当前源')+' · '+(data.categories || []).length+' 个分类 · '+(data.items || []).length+' 部内容');
		renderMediaGrid(data.items || []);
	}, error:function(){
		mediaMessage('源加载超时，请切换其他源重试。');
		$('#mediaGrid').html(mediaStateHtml('这个源暂时没有响应', '网络或源站可能较慢，可以直接重试。', 'home', '重试'));
	}});
}
function renderMediaCategories(categories, activeId){
	var html = ['<div class="media-category'+(!activeId ? ' active' : '')+'" data-id="">首页</div>'];
	for(var i=0;i<categories.length;i++){
		var category = categories[i];
		html.push('<div class="media-category'+(category.id === activeId ? ' active' : '')+'" data-id="'+escapeHtml(category.id)+'">'+escapeHtml(category.name)+'</div>');
	}
	$('#mediaCategories').html(html.join(''));
}
function loadMediaCategory(id){
	if(!id){
		loadMediaHome();
		return;
	}
	mediaFolderStack = [];
	resetMediaPagination('category', id, '');
	currentMediaCategoryId = id;
	showMediaBrowse(false);
	hideMediaSearchFilters();
	$('#mediaDetail').empty();
	$('#mediaCategories .media-category').removeClass('active');
	$('#mediaCategories .media-category[data-id="'+cssAttributeValue(id)+'"]').addClass('active');
	$('#mediaGrid').html(mediaStateHtml('正在加载分类', '', '', '', true));
	$.ajax({url:'/media/category', data:{sourceKey:currentMediaSourceKey,id:id,page:'1'}, dataType:'json', timeout:65000, success:function(data){
		if(data && data.success === false){
			mediaMessage('分类加载失败：'+(data.message || '未知错误'));
			$('#mediaGrid').html(mediaStateHtml('分类加载失败', data.message || '可以稍后重试。', 'category', '重试'));
			return;
		}
		var items = data.items || [];
		mediaPage = 1;
		mediaHasMore = items.length > 0;
		mediaMessage((data.sourceName || '当前源')+' · '+items.length+' 部内容');
		renderMediaGrid(items);
		updateMediaPagination();
	}, error:function(){
		mediaMessage('分类加载超时，请稍后重试。');
		$('#mediaGrid').html(mediaStateHtml('分类暂时没有响应', '可以稍后重试。', 'category', '重试'));
	}});
}
function loadMediaFolder(sourceKey, id, name, push){
	if(!id) return;
	selectMediaSection('browse');
	showMediaBrowse(false);
	hideMediaSearchFilters();
	currentMediaSourceKey = sourceKey || currentMediaSourceKey;
	resetMediaPagination('folder', id, name || '文件夹');
	$('#mediaSourceSelect').val(currentMediaSourceKey);
	if(push !== false) mediaFolderStack.push({sourceKey:currentMediaSourceKey, id:id, name:name || '文件夹'});
	$('#mediaCategories').html('<div class="media-category media-folder-back">‹ 返回</div><div class="media-category active">'+escapeHtml(name || '文件夹')+'</div>');
	$('#mediaGrid').html(mediaStateHtml('正在打开目录', '', '', '', true));
	$.ajax({url:'/media/category', data:{sourceKey:currentMediaSourceKey,id:id,page:'1'}, dataType:'json', timeout:65000, success:function(data){
		if(data && data.success === false){
			mediaMessage('目录加载失败：'+(data.message || '未知错误'));
			$('#mediaGrid').html(mediaStateHtml('目录加载失败', data.message || '可以稍后重试。', 'folder', '重试'));
			return;
		}
		var items = data.items || [];
		mediaPage = 1;
		mediaHasMore = items.length > 0;
		mediaMessage((data.sourceName || '当前源')+' · '+escapeHtml(name || '目录')+' · '+items.length+' 项');
		renderMediaGrid(items);
		updateMediaPagination();
	}, error:function(){
		mediaMessage('目录加载超时，请稍后重试。');
		$('#mediaGrid').html(mediaStateHtml('目录暂时没有响应', '可以稍后重试。', 'folder', '重试'));
	}});
}
function closeMediaFolder(){
	if(mediaFolderStack.length) mediaFolderStack.pop();
	if(!mediaFolderStack.length){
		if(currentMediaCategoryId) loadMediaCategory(currentMediaCategoryId);
		else loadMediaHome();
		return;
	}
	var parent = mediaFolderStack[mediaFolderStack.length - 1];
	loadMediaFolder(parent.sourceKey, parent.id, parent.name, false);
}
function cssAttributeValue(value){
	return String(value == null ? '' : value).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}
function resetMediaPagination(mode, id, name){
	mediaPage = 1;
	mediaPageMode = mode || '';
	mediaPageId = id || '';
	mediaPageName = name || '';
	mediaLoadingMore = false;
	mediaHasMore = false;
	$('#mediaPagination').addClass('hide');
	$('#btnMediaLoadMore').prop('disabled', false).text('加载更多');
}
function mediaItemKey(item){
	return [item && item.sourceKey || '', item && item.id || '', item && item.folder ? '1' : '0'].join('|');
}
function appendUniqueMediaItems(items){
	var existing = {};
	for(var i=0;i<renderedMediaItems.length;i++) existing[mediaItemKey(renderedMediaItems[i])] = true;
	var added = 0;
	for(var j=0;j<items.length;j++){
		var key = mediaItemKey(items[j]);
		if(existing[key]) continue;
		existing[key] = true;
		renderedMediaItems.push(items[j]);
		added++;
	}
	return added;
}
function updateMediaPagination(){
	var visible = mediaView === 'browse' && mediaSection === 'browse' && mediaHasMore && !!mediaPageMode;
	$('#mediaPagination').toggleClass('hide', !visible);
	$('#btnMediaLoadMore').prop('disabled', mediaLoadingMore).text(mediaLoadingMore ? '正在加载…' : '加载更多');
}
function loadMoreMedia(){
	if(mediaLoadingMore || !mediaHasMore || !mediaPageMode || !mediaPageId) return;
	mediaLoadingMore = true;
	updateMediaPagination();
	var nextPage = mediaPage + 1;
	$.ajax({url:'/media/category', data:{sourceKey:currentMediaSourceKey,id:mediaPageId,page:String(nextPage)}, dataType:'json', timeout:65000, success:function(data){
		if(data && data.success === false){
			mediaLoadingMore = false;
			updateMediaPagination();
			mediaMessage('加载更多失败：'+(data.message || '未知错误'));
			return;
		}
		var items = data.items || [];
		var added = appendUniqueMediaItems(items);
		if(items.length === 0 || added === 0){
			mediaHasMore = false;
		}else{
			mediaPage = nextPage;
		}
		mediaLoadingMore = false;
		renderMediaGrid(renderedMediaItems);
		updateMediaPagination();
		var label = mediaPageMode === 'folder' ? (mediaPageName || '目录') : '当前分类';
		mediaMessage(label+' · 已加载 '+renderedMediaItems.length+' 项'+(mediaHasMore ? '' : ' · 已到底'));
	}, error:function(){
		mediaLoadingMore = false;
		updateMediaPagination();
		mediaMessage('加载更多超时，请重试。');
	}});
}
function initMediaPaginationObserver(){
	if(!window.IntersectionObserver || mediaPaginationObserver) return;
	mediaPaginationObserver = new IntersectionObserver(function(entries){
		if(entries.length && entries[0].isIntersecting) loadMoreMedia();
	}, {root:document.querySelector('.container'), rootMargin:'0px 0px 280px 0px'});
	var target = document.getElementById('mediaPagination');
	if(target) mediaPaginationObserver.observe(target);
}
function renderMediaGrid(items){
	renderedMediaItems = items || [];
	var html = [];
	if(!items.length){
		if(mediaSection === 'favorites') html.push(mediaStateHtml('还没有收藏', '收藏的影片会集中显示在这里。', 'browse', '去浏览'));
		else if(mediaSection === 'history') html.push(mediaStateHtml('还没有播放记录', '从任意影片开始播放后，会自动出现在这里。', 'browse', '去浏览'));
		else if(mediaSearchStatusBase) html.push(mediaStateHtml('没有找到结果', '换个关键词试试，或返回首页继续浏览。', 'browse', '返回首页'));
		else html.push(mediaStateHtml('这里暂时没有内容', '可以换个分类，或直接搜索片名。'));
	}else{
		for(var i=0;i<items.length;i++){
			var item = items[i];
			html.push('<div class="media-card" data-index="'+i+'" data-source="'+escapeHtml(item.sourceKey)+'" data-id="'+escapeHtml(item.id)+'" data-name="'+escapeHtml(item.name)+'" data-folder="'+(item.folder ? '1' : '0')+'">');
			html.push(mediaPoster(item, true));
			if(mediaSection === 'favorites') html.push('<button type="button" class="media-card-unfavorite" aria-label="取消收藏" title="取消收藏">★</button>');
			html.push('<div class="media-card-copy"><div class="media-card-title" title="'+escapeHtml(item.name)+'">'+escapeHtml(item.name)+'</div>');
			var meta = [];
			if(item.episode) meta.push(item.episode);
			if(item.year) meta.push(item.year);
			if(item.type && meta.indexOf(item.type) < 0) meta.push(item.type);
			var cardScore = mediaScore(item);
			if(meta.length < 2 && item.remark && (!cardScore || String(item.remark).indexOf(cardScore) < 0) && meta.indexOf(item.remark) < 0) meta.push(item.remark);
			if(!meta.length && item.sourceName) meta.push(item.sourceName);
			html.push('<div class="media-card-meta">'+escapeHtml(meta.join(' · '))+'</div></div>');
			html.push('</div>');
		}
	}
	$('#mediaGrid').toggleClass('media-list', mediaDisplayMode === 'list').html(html.join(''));
}
function updateMediaDisplayMode(mode){
	mediaDisplayMode = mode === 'list' ? 'list' : 'grid';
	try { localStorage.setItem('mediaDisplayMode', mediaDisplayMode); } catch(e) {}
	var listMode = mediaDisplayMode === 'list';
	$('#mediaGrid').toggleClass('media-list', listMode);
	$('#btnMediaViewMode').attr('aria-label', listMode ? '切换为九宫格' : '切换为列表').attr('title', listMode ? '切换为九宫格' : '切换为列表');
	$('#btnMediaViewMode .media-view-grid-icon').toggleClass('hide', listMode);
	$('#btnMediaViewMode .media-view-list-icon').toggleClass('hide', !listMode);
}
function selectMediaSection(section){
	mediaSection = section || 'browse';
	$('#mediaLibraryNav .media-library-tab').removeClass('active');
	$('#mediaLibraryNav .media-library-tab[data-section="'+cssAttributeValue(mediaSection)+'"]').addClass('active');
}
function loadMediaLibrary(section){
	resetMediaPagination();
	mediaFolderStack = [];
	currentMediaCategoryId = '';
	selectMediaSection(section);
	showMediaBrowse(false);
	hideMediaSearchFilters();
	$('#mediaSearchInput').val('');
	$('#mediaDetail').empty();
	$('#mediaCategories').addClass('hide');
	var isHistory = section === 'history';
	var label = isHistory ? '播放历史' : '收藏';
	$('#mediaGrid').html(mediaStateHtml('正在加载'+label, '', '', '', true));
	$.get(isHistory ? '/media/history' : '/media/favorites', null, function(data){
		var items = data && data.items ? data.items : [];
		mediaMessage(label+' · '+items.length+' 部内容');
		renderMediaGrid(items);
	}, 'json').fail(function(){
		mediaMessage(label+'加载失败，请稍后重试。');
		$('#mediaGrid').html(mediaStateHtml(label+'加载失败', '请稍后重试。', 'library', '重试'));
	});
}
function searchMedia(){
	var q = $('#mediaSearchInput').val();
	if(!q){
		showMediaReady();
		return;
	}
	resetMediaPagination();
	selectMediaSection('browse');
	mediaFolderStack = [];
	currentMediaCategoryId = '';
	showMediaBrowse(false);
	$('#mediaDetail').empty();
	$('#mediaGrid').html(mediaStateHtml('正在搜索', q, '', '', true));
	$.ajax({url:'/media/search', data:{q:q,sourceKey:''}, dataType:'json', timeout:45000, success:function(data){
		if(data && data.success === false){
			hideMediaSearchFilters();
			mediaMessage('搜索失败：'+(data.message || '未知错误'));
			$('#mediaGrid').html(mediaStateHtml('搜索失败', data.message || '可以直接重试。', 'search', '重试'));
			return;
		}
		var items = data.items || [];
		mediaSearchStatusBase = data.message || ('已搜索 '+(data.searchedSources || 0)+' 个源，合并为 '+items.length+' 条结果。');
		renderMediaSearchFilters(items);
		mediaMessage(mediaSearchStatusBase);
		renderMediaGrid(items);
	}, error:function(){
		hideMediaSearchFilters();
		mediaMessage('搜索超时，可以换个关键词或稍后重试。');
		$('#mediaGrid').html(mediaStateHtml('搜索超时', '可以换个关键词，或直接重试。', 'search', '重试'));
	}});
}
function mediaEpisodeGroups(episodes){
	var groups = [];
	var indexes = {};
	for(var i=0;i<episodes.length;i++){
		var episode = episodes[i];
		var flag = episode.flag || '默认线路';
		var flagKey = '$' + flag;
		if(indexes[flagKey] == null){
			indexes[flagKey] = groups.length;
			groups.push({name:flag, episodes:[]});
		}
		groups[indexes[flagKey]].episodes.push(episode);
	}
	return groups;
}
function mediaDetailHeader(){
	return '<div class="media-subview-head"><button type="button" class="media-back-btn" id="btnMediaDetailBack" aria-label="返回浏览" title="返回浏览"><svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="m15 5-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></button></div>';
}
function loadMediaDetail(sourceKey, id){
	currentMediaDetail = null;
	showMediaDetailView();
	mediaMessage('正在加载详情…');
	$('#mediaDetail').html(mediaDetailHeader()+mediaStateHtml('正在加载详情', '', '', '', true));
	$.ajax({url:'/media/detail', data:{sourceKey:sourceKey, id:id}, dataType:'json', timeout:65000, success:function(data){
		if(data && data.success === false){
			var message = data.message || '详情加载失败';
			mediaMessage('详情加载失败：'+message);
			$('#mediaDetail').html(mediaDetailHeader()+mediaStateHtml('详情加载失败', message, 'detail|'+encodeURIComponent(sourceKey)+'|'+encodeURIComponent(id), '重试'));
			return;
		}
		var item = data.item;
		if(!item){
			mediaMessage('详情加载失败：未找到详情');
			$('#mediaDetail').html(mediaDetailHeader()+mediaStateHtml('没有找到详情', '这个源没有返回详情数据，可以重试或返回浏览。', 'detail|'+encodeURIComponent(sourceKey)+'|'+encodeURIComponent(id), '重试'));
			return;
		}
		currentMediaDetail = item;
		mediaMessage((item.sourceName || '当前源')+' · 选择剧集播放');
		var history = item.history || null;
		var historyEpisode = history ? mediaFindEpisode(item, history.playId, history.flag) : null;
		var historyProgress = mediaHistoryProgress(history);
		var groups = mediaEpisodeGroups(item.episodes || []);
		var firstEpisode = groups.length && groups[0].episodes.length ? groups[0].episodes[0] : null;
		var canResume = !!(history && historyEpisode && historyProgress > 0 && historyProgress < 95);
		var episodeChunkSize = 30;
		var initialRoute = 0;
		var initialRanges = {};
		for(var routeIndex=0;routeIndex<groups.length;routeIndex++){
			initialRanges[routeIndex] = 0;
			if(!historyEpisode) continue;
			for(var historyIndex=0;historyIndex<groups[routeIndex].episodes.length;historyIndex++){
				var historyCandidate = groups[routeIndex].episodes[historyIndex];
				if(String(historyCandidate.playId || '') === String(historyEpisode.playId || '') && String(historyCandidate.flag || '') === String(historyEpisode.flag || '')){
					initialRoute = routeIndex;
					initialRanges[routeIndex] = Math.floor(historyIndex / episodeChunkSize);
				}
			}
		}
		var html = [];
		html.push(mediaDetailHeader());
		html.push('<div class="media-detail-layout">');
		html.push(mediaPoster(item));
		html.push('<div class="media-detail-main">');
		html.push('<div class="media-detail-title">'+escapeHtml(item.name)+'</div>');
		html.push('<div class="media-card-meta">'+escapeHtml(item.sourceName || '')+(item.year ? ' · '+escapeHtml(item.year) : '')+(item.remark ? ' · '+escapeHtml(item.remark) : '')+'</div>');
		html.push('<div class="media-detail-actions">');
		if(canResume){
			html.push('<button type="button" class="media-resume-btn" id="btnMediaContinue" data-source="'+escapeHtml(item.sourceKey)+'" data-playid="'+escapeHtml(historyEpisode.playId || '')+'" data-flag="'+escapeHtml(historyEpisode.flag || '')+'" data-title="'+escapeHtml(item.name || '')+'" data-episode="'+escapeHtml(historyEpisode.name || history.episode || '')+'" title="继续观看 '+escapeHtml(historyEpisode.name || history.episode || '上次位置')+'"><svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path d="m8 5 11 7-11 7V5Z" fill="currentColor"/></svg><span class="media-action-label">继续观看 · '+escapeHtml(historyEpisode.name || history.episode || '上次位置')+' · '+Math.round(historyProgress)+'%</span></button>');
		}else if(!historyEpisode && firstEpisode){
			html.push('<button type="button" class="media-resume-btn" id="btnMediaStart" data-source="'+escapeHtml(item.sourceKey)+'" data-playid="'+escapeHtml(firstEpisode.playId || '')+'" data-flag="'+escapeHtml(firstEpisode.flag || '')+'" data-title="'+escapeHtml(item.name || '')+'" data-episode="'+escapeHtml(firstEpisode.name || '')+'" title="开始播放"><svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path d="m8 5 11 7-11 7V5Z" fill="currentColor"/></svg><span class="media-action-label">开始播放</span></button>');
		}
		html.push('<button type="button" class="media-favorite-btn'+(item.favorite ? ' active' : '')+'" id="btnMediaFavorite" aria-label="'+(item.favorite ? '取消收藏' : '收藏')+'"><span class="media-favorite-icon">'+(item.favorite ? '★' : '☆')+'</span><span>'+(item.favorite ? '已收藏' : '收藏')+'</span></button>');
		html.push('</div>');
		html.push('</div></div>');
		if(item.desc) html.push('<div class="media-desc-wrap"><div class="media-desc">'+escapeHtml(item.desc)+'</div><button type="button" class="media-desc-toggle" aria-expanded="false">展开简介</button></div>');
		html.push('<div class="media-play-section"><div class="media-section-head"><div class="media-section-title">播放</div><div class="media-section-meta">'+(item.episodes || []).length+' 集</div></div>');
		if(groups.length > 1){
			html.push('<div class="media-routes">');
			for(var g=0;g<groups.length;g++) html.push('<button type="button" class="media-route'+(g === initialRoute ? ' active' : '')+'" data-route="'+g+'">'+escapeHtml(groups[g].name)+'</button>');
			html.push('</div>');
		}
		for(var rangeGroup=0;rangeGroup<groups.length;rangeGroup++){
			var rangeCount = Math.ceil(groups[rangeGroup].episodes.length / episodeChunkSize);
			if(rangeCount <= 1) continue;
			html.push('<div class="media-episode-ranges'+(rangeGroup === initialRoute ? '' : ' hide')+'" data-route="'+rangeGroup+'">');
			for(var rangeIndex=0;rangeIndex<rangeCount;rangeIndex++){
				var rangeStart = rangeIndex * episodeChunkSize + 1;
				var rangeEnd = Math.min((rangeIndex + 1) * episodeChunkSize, groups[rangeGroup].episodes.length);
				html.push('<button type="button" class="media-episode-range'+(rangeIndex === initialRanges[rangeGroup] ? ' active' : '')+'" data-route="'+rangeGroup+'" data-range="'+rangeIndex+'">'+rangeStart+'-'+rangeEnd+'</button>');
			}
			html.push('</div>');
		}
		html.push('<div class="media-episodes">');
		for(var groupIndex=0;groupIndex<groups.length;groupIndex++){
			for(var i=0;i<groups[groupIndex].episodes.length;i++){
				var ep = groups[groupIndex].episodes[i];
				var episodeRange = Math.floor(i / episodeChunkSize);
				var isResumeEpisode = !!(historyEpisode && String(ep.playId || '') === String(historyEpisode.playId || '') && String(ep.flag || '') === String(historyEpisode.flag || ''));
				var progressText = isResumeEpisode && historyProgress > 0 && historyProgress < 95 ? '<span class="media-episode-progress">'+Math.round(historyProgress)+'%</span>' : '';
				var visibleEpisode = groupIndex === initialRoute && episodeRange === initialRanges[groupIndex];
				html.push('<button type="button" class="media-episode'+(visibleEpisode ? '' : ' hide')+(isResumeEpisode ? ' resume' : '')+'" data-route="'+groupIndex+'" data-range="'+episodeRange+'" data-source="'+escapeHtml(item.sourceKey)+'" data-playid="'+escapeHtml(ep.playId)+'" data-flag="'+escapeHtml(ep.flag || '')+'" data-title="'+escapeHtml(item.name || '')+'" data-episode="'+escapeHtml(ep.name || '')+'"><span>'+escapeHtml(ep.name || '播放')+'</span>'+progressText+'</button>');
			}
		}
		if(!groups.length) html.push('<div class="media-empty">这个 source 没有返回可播放剧集。</div>');
		html.push('</div></div>');
		$('#mediaDetail').html(html.join(''));
	}, error:function(xhr, status){
		var message = status === 'timeout' ? '详情加载超时，请稍后重试。' : '详情请求失败，请稍后重试。';
		mediaMessage(message);
		$('#mediaDetail').html(mediaDetailHeader()+mediaStateHtml('详情加载失败', message, 'detail|'+encodeURIComponent(sourceKey)+'|'+encodeURIComponent(id), '重试'));
	}});
}
function playMediaEpisode(sourceKey, flag, playId, title, episode){
	mediaMessage('正在解析并发送到电视播放…');
	var displayTitle = title || '';
	if(episode) displayTitle += (displayTitle ? ' · ' : '') + episode;
	var item = currentMediaDetail || {};
	$.ajax({url:'/media/play', type:'POST', data:{sourceKey:sourceKey, sourceName:item.sourceName || '', mediaId:item.id || '', mediaName:item.name || title || '', pic:item.pic || '', score:item.score || '', remark:item.remark || '', flag:flag, playId:playId, episode:episode || '', title:displayTitle}, dataType:'json', timeout:45000, success:function(data){
		if(data && data.success === false){
			mediaMessage(data.message || '播放失败');
		}else{
			mediaMessage('已发送到电视播放。');
			refreshMediaPlaybackStatus();
			updateMediaPlaybackPolling();
		}
	}, error:function(){
		mediaMessage('播放解析超时，请换一条线路或换一个源。');
	}});
}

function formatPlaybackTime(ms){
	var total = Math.max(0, Math.floor((Number(ms) || 0) / 1000));
	var hours = Math.floor(total / 3600);
	var minutes = Math.floor((total % 3600) / 60);
	var seconds = total % 60;
	var mm = (hours > 0 && minutes < 10 ? '0' : '') + minutes;
	var ss = (seconds < 10 ? '0' : '') + seconds;
	return hours > 0 ? hours + ':' + mm + ':' + ss : minutes + ':' + ss;
}

function isMediaTabVisible(){
	return $('.tab.cur').attr('data-rel') === 'media';
}

function setMediaPlaybackPlayingUi(active, playing){
	var isPlaying = !!(active && playing);
	mediaPlaybackPlaying = isPlaying;
	$('#mediaPlaybackCompactIcon .media-compact-play-icon, .media-playback-fab-icon .media-mini-play-icon').toggleClass('hide', isPlaying);
	$('#mediaPlaybackCompactIcon .media-compact-pause-icon, .media-playback-fab-icon .media-mini-pause-icon').toggleClass('hide', !isPlaying);
	$('#btnMediaCompactPrimary').attr('aria-label', active ? (isPlaying ? '暂停' : '播放') : '继续播放');
	var $playPause = $('#btnMediaPlayPause').toggleClass('hide', !active);
	$playPause.attr('aria-label', isPlaying ? '暂停' : '播放').attr('title', isPlaying ? '暂停' : '播放');
	$playPause.find('.media-control-label').text(isPlaying ? '暂停' : '播放');
	$playPause.find('.media-control-play-icon').toggleClass('hide', isPlaying);
	$playPause.find('.media-control-pause-icon').toggleClass('hide', !isPlaying);
}

function renderMediaPlaybackStatus(data){
	var active = !!(data && data.active);
	var hasSession = !!(data && data.hasSession);
	var reportedPlaying = !!(data && data.playing);
	var playing = reportedPlaying;
	if(active && Date.now() < mediaPlaybackPlayingPendingUntil && reportedPlaying !== mediaPlaybackPlaying){
		playing = mediaPlaybackPlaying;
	}else{
		mediaPlaybackPlayingPendingUntil = 0;
	}
	mediaPlaybackActive = active;
	mediaPlaybackHasSession = hasSession;
	$('#mediaPlaybackControls').toggleClass('hide', !(active || hasSession));
	$('.media-panel').toggleClass('media-has-playback', active || hasSession);
	if(!(active || hasSession)) return;

	var duration = Math.max(0, Number(data.duration) || 0);
	var position = Math.max(0, Number(data.position) || 0);
	if(duration > 0) position = Math.min(duration, position);
	var $seek = $('#mediaPlaybackSeek');
	$seek.attr('max', duration).prop('disabled', !active || duration <= 0);
	if(!mediaPlaybackDragging) $seek.val(position);
	$('#mediaPlaybackCurrent').text(formatPlaybackTime(mediaPlaybackDragging ? $seek.val() : position));
	$('#mediaPlaybackDuration').text(duration > 0 ? formatPlaybackTime(duration) : '--:--');
	$('#mediaPlaybackCompactLabel').text(active ? (playing ? '电视播放中' : '电视已暂停') : '继续观看');
	$('#mediaPlaybackCompactTitle').text(data.mediaName || '');
	$('#mediaPlaybackCompactEpisode').text(data.episode || '');
	$('#mediaPlaybackCompactProgress').css('width', duration > 0 ? Math.max(0, Math.min(100, position * 100 / duration))+'%' : '0%');
	setMediaPlaybackPlayingUi(active, playing);

	var speed = Number(data.speed) || 1;
	var $speed = $('#mediaPlaybackSpeed');
	$speed.prop('disabled', !active || !data.speedSupported);
	if(!mediaPlaybackDragging) $speed.val(String(speed));
	$('#btnMediaResume').toggleClass('hide', active);
	$('#btnMediaPrevEpisode').prop('disabled', !data.canPrev);
	$('#btnMediaNextEpisode').prop('disabled', !data.canNext);
	var queue = data.queue || [];
	var queueHtml = [];
	for(var i=0;i<queue.length;i++){
		queueHtml.push('<button type="button" class="media-queue-item'+(queue[i].current ? ' current' : '')+'" data-index="'+Number(queue[i].index)+'"'+(queue[i].current ? ' disabled' : '')+'>'+escapeHtml(queue[i].name || ('第 '+(Number(queue[i].index)+1)+' 集'))+'</button>');
	}
	$('#mediaQueueItems').html(queueHtml.join(''));
	$('#mediaPlaybackQueue').toggleClass('hide', queue.length <= 1);
	$('#btnMediaNextEpisode').attr('title', data.nextEpisode ? '下一集：'+data.nextEpisode : '下一集');
	$('#btnMediaMarkOpening,#btnMediaMarkEnding,.media-skip-adjust').prop('disabled', !active || duration <= 0);
	var skip = [];
	if(Number(data.opening) > 0) skip.push('片头跳过 '+formatPlaybackTime(data.opening));
	if(Number(data.ending) > 0) skip.push('片尾提前 '+formatPlaybackTime(data.ending));
	$('#mediaSkipSummary').text(skip.length ? skip.join(' · ') : '未设置跳过');
}

function mediaEpisodeAction(direction){
	mediaMessage(direction === 'prev' ? '正在切换上一集…' : '正在切换下一集…');
	$.ajax({url:'/media/episode', type:'POST', data:{direction:direction}, dataType:'json', timeout:45000, success:function(data){
		if(data && data.success === false) mediaMessage(data.message || '切换剧集失败');
		else mediaMessage('已切换到 '+(data.episode || (direction === 'prev' ? '上一集' : '下一集'))+'。');
		refreshMediaPlaybackStatus();
	}, error:function(){ mediaMessage('切换剧集超时，请稍后重试。'); }});
}

function mediaQueueEpisode(index){
	mediaMessage('正在切换剧集…');
	$.ajax({url:'/media/episode', type:'POST', data:{index:index}, dataType:'json', timeout:45000, success:function(data){
		if(data && data.success === false) mediaMessage(data.message || '切换剧集失败');
		else mediaMessage('已切换到 '+(data.episode || '所选剧集')+'。');
		refreshMediaPlaybackStatus();
	}, error:function(){ mediaMessage('切换剧集超时，请稍后重试。'); }});
}

function mediaMarkerAction(action, delta){
	$.post('/media/marker', {action:action, delta:delta || 0}, function(data){
		if(data && data.success === false){
			mediaMessage(data.message || '跳过设置失败');
			return;
		}
		if(action === 'clear') mediaMessage('已清除片头片尾跳过。');
		else if(action === 'opening') mediaMessage('已将当前位置设为片头结束。');
		else if(action === 'ending') mediaMessage('已将当前位置设为片尾开始。');
		else mediaMessage('已微调跳过时间。');
		refreshMediaPlaybackStatus();
	}, 'json').fail(function(){ mediaMessage('跳过设置失败，请稍后重试。'); });
}

function refreshMediaPlaybackStatus(){
	if(!isMediaTabVisible()) return;
	$.ajax({url:'/player/status', type:'POST', dataType:'json', timeout:2500, success:renderMediaPlaybackStatus});
}

function updateMediaPlaybackPolling(){
	if(mediaPlaybackPollTimer){
		clearInterval(mediaPlaybackPollTimer);
		mediaPlaybackPollTimer = null;
	}
	if(isMediaTabVisible()){
		refreshMediaPlaybackStatus();
		mediaPlaybackPollTimer = setInterval(refreshMediaPlaybackStatus, 1000);
	}else{
		$('#mediaPlaybackControls').addClass('hide');
		$('.media-panel').removeClass('media-has-playback');
	}
}

updateMediaDisplayMode(mediaDisplayMode);
$('#btnMediaSettings').on('click', function(){
	showMediaSettingsView();
});
$('#btnMediaViewMode').on('click', function(){
	updateMediaDisplayMode(mediaDisplayMode === 'grid' ? 'list' : 'grid');
	renderMediaGrid(renderedMediaItems);
});
$('#btnMediaSettingsBack').on('click', function(){
	showMediaBrowse(true);
});
$('#btnMediaConnect').on('click', function(){
	var url = $('#mediaConfigUrl').val();
	mediaMessage('正在连接配置…');
	$.ajax({url:'/media/config', type:'POST', data:{url:url}, dataType:'json', timeout:30000, success:function(data){
		if(data && data.success === false){
			mediaMessage(data.message || '配置加载失败');
		}else{
			renderMediaSources(data);
			if(data.supportedSources > 0) loadMediaHome();
			else showMediaReady();
		}
	}, error:function(){
		mediaMessage('配置连接超时，请检查地址或稍后重试。');
	}});
});
$('#btnMediaSearch').on('click', searchMedia);
$('#mediaFilterType,#mediaFilterYear').on('change', applyMediaSearchFilters);
$('#mediaPlaybackSeek').on('input', function(){
	mediaPlaybackDragging = true;
	$('#mediaPlaybackCurrent').text(formatPlaybackTime($(this).val()));
}).on('change', function(){
	var position = Math.max(0, Number($(this).val()) || 0);
	mediaPlaybackDragging = false;
	$.post('/player/seek', {position:Math.round(position)}, function(data){
		if(!data || data.handled === false) mediaMessage('当前视频暂时不能调整进度。');
		refreshMediaPlaybackStatus();
	}, 'json').fail(function(){
		mediaMessage('进度调整失败，请稍后重试。');
	});
});
$('#mediaPlaybackSpeed').on('change', function(){
	var speed = Number($(this).val()) || 1;
	$.post('/player/speed', {speed:speed}, function(data){
		if(!data || data.handled === false) mediaMessage('当前播放器暂不支持倍速。');
		refreshMediaPlaybackStatus();
	}, 'json').fail(function(){
		mediaMessage('倍速切换失败，请稍后重试。');
	});
});
$('#btnMediaPrevEpisode').on('click', function(){ mediaEpisodeAction('prev'); });
$('#btnMediaNextEpisode').on('click', function(){ mediaEpisodeAction('next'); });
$('#btnMediaResume').on('click', function(){
	mediaMessage('正在继续播放…');
	$.ajax({url:'/media/resume', type:'POST', dataType:'json', timeout:45000, success:function(data){
		if(data && data.success === false) mediaMessage(data.message || '继续播放失败');
		else mediaMessage('已继续在电视播放。');
		refreshMediaPlaybackStatus();
	}, error:function(){ mediaMessage('继续播放超时，请稍后重试。'); }});
});
$('#btnMediaPlayPause').on('click', function(){
	mediaPlaybackPlayingPendingUntil = Date.now() + 900;
	setMediaPlaybackPlayingUi(true, !mediaPlaybackPlaying);
	$.post('/player/control', {code:'85',action:'press'}, function(){
		setTimeout(refreshMediaPlaybackStatus, 300);
	}).fail(function(){
		mediaPlaybackPlayingPendingUntil = 0;
		refreshMediaPlaybackStatus();
	});
});
$('#btnMediaPlaybackToggle').on('click', function(){
	var expanded = !$('#mediaPlaybackControls').hasClass('expanded');
	$('#mediaPlaybackControls').toggleClass('expanded', expanded);
	$(this).attr('aria-expanded', expanded ? 'true' : 'false').attr('aria-label', expanded ? '收起播放控制' : '展开播放控制');
});
$('#btnMediaCompactPrimary').on('click', function(){
	if(mediaPlaybackActive){
		$('#btnMediaPlayPause').trigger('click');
	}else if(mediaPlaybackHasSession){
		$('#btnMediaResume').trigger('click');
	}
});
$('#btnMediaMarkOpening').on('click', function(){ mediaMarkerAction('opening'); });
$('#btnMediaMarkEnding').on('click', function(){ mediaMarkerAction('ending'); });
$('#btnMediaClearMarkers').on('click', function(){ mediaMarkerAction('clear'); });
$('.media-skip-adjust').on('click', function(){
	mediaMarkerAction($(this).attr('data-marker')+'-adjust', $(this).attr('data-delta'));
});
$('#mediaQueueItems').on('click', '.media-queue-item:not(.current)', function(){
	mediaQueueEpisode(Number($(this).attr('data-index')));
});
$('#mediaLibraryNav').on('click', '.media-library-tab', function(){
	var section = $(this).attr('data-section') || 'browse';
	if(section === 'browse') loadMediaHome();
	else loadMediaLibrary(section);
});
$('.media-panel').on('click', '.media-state-action', function(){
	var action = $(this).attr('data-media-action') || '';
	if(action === 'settings') showMediaSettingsView();
	else if(action === 'config') loadMediaConfig();
	else if(action === 'home' || action === 'browse') loadMediaHome();
	else if(action === 'category') loadMediaCategory(currentMediaCategoryId);
	else if(action === 'folder'){
		var folder = mediaFolderStack.length ? mediaFolderStack[mediaFolderStack.length - 1] : null;
		if(folder) loadMediaFolder(folder.sourceKey, folder.id, folder.name, false);
		else loadMediaHome();
	}else if(action === 'library') loadMediaLibrary(mediaSection);
	else if(action === 'search') searchMedia();
	else if(action.indexOf('detail|') === 0){
		var detail = action.split('|');
		if(detail.length === 3) loadMediaDetail(decodeURIComponent(detail[1]), decodeURIComponent(detail[2]));
	}
});
$('#mediaSourceSelect').on('change', function(){
	currentMediaSourceKey = $(this).val() || '';
	loadMediaHome();
});
$('#btnMediaLoadMore').on('click', loadMoreMedia);
initMediaPaginationObserver();
$('#mediaCategories').on('click', '.media-category', function(){
	if($(this).hasClass('media-folder-back')){
		closeMediaFolder();
		return;
	}
	loadMediaCategory($(this).attr('data-id') || '');
});
$('#mediaSearchInput').on('keydown', function(e){
	if(e.key === 'Enter' || e.keyCode === 13) searchMedia();
});
$('#mediaGrid').on('click', '.media-card', function(e){
	if($(e.target).closest('.media-card-unfavorite').length) return;
	var sourceKey = $(this).attr('data-source') || '';
	var id = $(this).attr('data-id') || '';
	var name = $(this).attr('data-name') || '';
	var source = sourceByKey(sourceKey);
	if($(this).attr('data-folder') === '1' || /@folder$/.test(id)){
		loadMediaFolder(sourceKey, id, name, true);
		return;
	}
	if((source && Number(source.indexs) === 1) || !id || id.indexOf('msearch:') === 0){
		$('#mediaSearchInput').val(name);
		searchMedia();
		return;
	}
	loadMediaDetail(sourceKey, id);
});
$('#mediaGrid').on('click', '.media-card-unfavorite', function(e){
	e.preventDefault();
	e.stopPropagation();
	var $card = $(this).closest('.media-card');
	var index = Number($card.attr('data-index'));
	var item = renderedMediaItems[index];
	if(!item) return;
	var $button = $(this).prop('disabled', true);
	$.post('/media/favorite', {sourceKey:item.sourceKey || '', sourceName:item.sourceName || '', mediaId:item.id || '', mediaName:item.name || '', pic:item.pic || '', score:item.score || '', remark:item.remark || ''}, function(data){
		if(!data || data.success === false || data.favorite){
			$button.prop('disabled', false);
			mediaMessage(data && data.message ? data.message : '取消收藏失败');
			return;
		}
		renderedMediaItems.splice(index, 1);
		mediaMessage('已取消收藏 · '+renderedMediaItems.length+' 部内容');
		renderMediaGrid(renderedMediaItems);
	}, 'json').fail(function(){
		$button.prop('disabled', false);
		mediaMessage('取消收藏失败，请稍后重试。');
	});
});
$('#mediaDetail').on('click', '.media-episode', function(){
	$('#mediaDetail .media-episode').removeClass('playing');
	$(this).addClass('playing');
	playMediaEpisode($(this).attr('data-source'), $(this).attr('data-flag'), $(this).attr('data-playid'), $(this).attr('data-title'), $(this).attr('data-episode'));
});
$('#mediaDetail').on('click', '#btnMediaContinue,#btnMediaStart', function(){
	var playId = String($(this).attr('data-playid') || '');
	var flag = String($(this).attr('data-flag') || '');
	$('#mediaDetail .media-episode').removeClass('playing').filter(function(){
		return String($(this).attr('data-playid') || '') === playId && String($(this).attr('data-flag') || '') === flag;
	}).addClass('playing');
	playMediaEpisode($(this).attr('data-source'), $(this).attr('data-flag'), $(this).attr('data-playid'), $(this).attr('data-title'), $(this).attr('data-episode'));
});
$('#mediaDetail').on('click', '#btnMediaFavorite', function(){
	if(!currentMediaDetail) return;
	var item = currentMediaDetail;
	$.post('/media/favorite', {sourceKey:item.sourceKey || '', sourceName:item.sourceName || '', mediaId:item.id || '', mediaName:item.name || '', pic:item.pic || '', score:item.score || '', remark:item.remark || ''}, function(data){
		if(!data || data.success === false){
			mediaMessage(data && data.message ? data.message : '收藏操作失败');
			return;
		}
		item.favorite = !!data.favorite;
		var $button = $('#btnMediaFavorite');
		$button.toggleClass('active', item.favorite).attr('aria-label', item.favorite ? '取消收藏' : '收藏');
		$button.find('.media-favorite-icon').text(item.favorite ? '★' : '☆');
		$button.find('span:last').text(item.favorite ? '已收藏' : '收藏');
		mediaMessage(item.favorite ? '已加入收藏。' : '已取消收藏。');
	}, 'json');
});
$('#mediaDetail').on('click', '#btnMediaDetailBack', function(){
	if(mediaSection === 'favorites' && currentMediaDetail && !currentMediaDetail.favorite) loadMediaLibrary(mediaSection);
	else showMediaBrowse(true);
});
$('#mediaDetail').on('click', '.media-desc-toggle', function(){
	var $wrap = $(this).closest('.media-desc-wrap');
	var expanded = !$wrap.hasClass('expanded');
	$wrap.toggleClass('expanded', expanded);
	$(this).attr('aria-expanded', expanded ? 'true' : 'false').text(expanded ? '收起简介' : '展开简介');
});
$('#mediaDetail').on('click', '.media-route', function(){
	var route = String($(this).attr('data-route') || '0');
	$('#mediaDetail .media-route').removeClass('active');
	$(this).addClass('active');
	$('#mediaDetail .media-episode-ranges').addClass('hide');
	var $ranges = $('#mediaDetail .media-episode-ranges[data-route="'+cssAttributeValue(route)+'"]').removeClass('hide');
	var range = String($ranges.find('.media-episode-range.active').attr('data-range') || '0');
	$('#mediaDetail .media-episode').addClass('hide');
	$('#mediaDetail .media-episode[data-route="'+cssAttributeValue(route)+'"][data-range="'+cssAttributeValue(range)+'"]').removeClass('hide');
});
$('#mediaDetail').on('click', '.media-episode-range', function(){
	var route = String($(this).attr('data-route') || '0');
	var range = String($(this).attr('data-range') || '0');
	var $nav = $(this).closest('.media-episode-ranges');
	$nav.find('.media-episode-range').removeClass('active');
	$(this).addClass('active');
	$('#mediaDetail .media-episode[data-route="'+cssAttributeValue(route)+'"]').addClass('hide');
	$('#mediaDetail .media-episode[data-route="'+cssAttributeValue(route)+'"][data-range="'+cssAttributeValue(range)+'"]').removeClass('hide');
});
$("#btnEnter").on("click", function(){
	vibrateShort();
	//同"清空"按键：取消排队中的/textLive防抖请求，避免它带着提交前的旧文字
	//在/text提交之后才发出去，把已经提交完的输入框内容又重新填一遍。
	if(composingTimer){ clearTimeout(composingTimer); composingTimer = null; }
	var $input = $("#inputarea");
	var text = $input.val();
	if(text != ""){
		$input.val("");
		autoResizeInputArea();
		$.post("/text", {text: text}, function(){
			postKeyCode("66");
		});
	}else{
		postKeyCode("66");
	}
})
//输入框高度跟着实际内容走：先把height设回auto让scrollHeight量出"刚好
//装下当前文字"需要多高，再把height设成这个值——不这样先重置成auto的话，
//文字变少时scrollHeight会一直保持之前撑开过的高度，只会越长越高、
//缩不回去。CSS里的max-height+overflow-y兜底，超过封顶高度后变成内部
//滚动，不会无限撑高把下面的按键区顶出屏幕。
function autoResizeInputArea(){
	var el = document.getElementById("inputarea");
	if(!el) return;
	el.style.height = "auto";
	el.style.height = el.scrollHeight + "px";
}
autoResizeInputArea();
//输入框内容实时同步到电视端（对应输入法的组字预览状态，还没真正提交），
//加个小延迟避免每敲一下都发一次请求
var composingTimer = null;
$("#inputarea").on("input", function(){
	autoResizeInputArea();
	var text = $(this).val();
	if(composingTimer) clearTimeout(composingTimer);
	composingTimer = setTimeout(function(){
		$.post("/textLive", {text: text}, function(data){
			console.log(data);
		});
	}, 150);
});
//手机键盘的退格键在输入框已经是空的时候，浏览器自己没有字符可删、不会有
//任何反应；这时候顺手把这次退格转发成电视端自己的退格键(效果跟点一下
//下面专门的"退格"按键一样)，可以继续删掉电视端已经提交的文字，不用先把
//手切换到下面那个按键上，用起来更连贯。
$("#inputarea").on("keydown", function(e){
	var isBackspace = e.key === "Backspace" || e.keyCode === 8;
	if(isBackspace && $(this).val() === ""){
		e.preventDefault();
		vibrateShort();
		postKeyCode("67");
	}
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
//星标图标叠在app-item上面，点星标不能连带触发外层app-item的启动/卸载逻辑——
//stopPropagation不够，因为jQuery对同一个容器上委托的多个选择器是在同一次
//事件分发里依次判断触发的，必须用stopImmediatePropagation才能真正拦下
//后面排队的.app-item处理器。
$('.app-list').on('click', '.app-star', function(e){
	e.stopPropagation();
	e.stopImmediatePropagation();
	vibrateShort();
	var packageName = $(this).attr('data-packagename');
	var willStar = $(this).attr('data-starred') !== '1';
	$.post('/star', {packageName: packageName, starred: willStar}, function(){
		reloadAppList();
	});
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
	updateContainerWidth();
	updateElementsAutoRefresh();
	updateMediaPlaybackPolling();
})
//方向键/操作列表这两个子Tab除了点标签切换，也支持在内容区左右滑动切换——
//点两个小标签来回切总感觉要"精确瞄准"，直接在当前显示的面板上一划更顺手。
var MODE_ORDER = ["dpad", "elements"];
//"操作列表"子Tab用的是按屏幕坐标百分比换算的空间地图，容器越宽单个
//条目能分到的像素越多，是全站唯一一个"越宽越有用"的视图——其它Tab
//(按钮宫格/文件列表/dpad摇杆)都是照手机单手操作设计的，屏幕再宽也用不上，
//没必要跟着一起解除.container的480px宽度上限。只在"输入遥控"Tab当前
//可见、且子Tab正好是"操作列表"时才加宽，两个条件缺一都要还原。
function isElementsViewVisible(){
	return $('.tab.cur').attr('data-rel') === 'controls' && $('.mode-tab.active').attr('data-mode') === 'elements';
}
function updateContainerWidth(){
	var activeTab = $('.tab.cur').attr('data-rel');
	$('.container')
		.toggleClass('wide-mode', isElementsViewVisible())
		.toggleClass('media-wide-mode', activeTab === 'media');
}
//操作列表之前只在"进入这个子Tab/点刷新/点了某个操作之后"这几个时机才会
//重新拉取一次，电视画面如果是被别的方式(比如遥控器/自动播放/弹窗)改变的，
//控制页不会知道，只能一直显示着旧内容直到用户想起来点刷新。这里改成只要
//这个视图还看得见，就按固定间隔自动刷新，不需要用户自己惦记着点刷新——
//离开这个视图(切到别的子Tab或别的顶层Tab)时停掉，不在后台空转。有了这个
//自动刷新，原来那个手动"刷新"按钮就没有存在的必要了，已经去掉。
var ELEMENTS_REFRESH_INTERVAL_MS = 3000;
var elementsRefreshTimer = null;
function updateElementsAutoRefresh(){
	if(isElementsViewVisible()){
		if(!elementsRefreshTimer) elementsRefreshTimer = setInterval(loadScreenElements, ELEMENTS_REFRESH_INTERVAL_MS);
	}else if(elementsRefreshTimer){
		clearInterval(elementsRefreshTimer);
		elementsRefreshTimer = null;
	}
}
function switchMode(mode){
	$(".mode-tab").removeClass("active");
	$('.mode-tab[data-mode="' + mode + '"]').addClass("active");
	$(".nav-mode").addClass("hide");
	$('.nav-mode[data-mode="' + mode + '"]').removeClass("hide");
	updateContainerWidth();
	updateElementsAutoRefresh();
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
//操作列表：读取无障碍服务识别出的当前屏幕可点击元素，点名字直接让那个控件
//执行它自己的点击逻辑（不区分是靠触摸还是遥控器焦点响应的），完全不依赖ADB，
//标准Android TV系统也能用；没开启无障碍服务时给一个能直接跳转到电视端
//"设置-无障碍"页面的按钮（复用/runSystem，跟"应用管理"里的系统设置入口
//是同一套机制）。
//
//列表按元素在电视屏幕上的真实坐标(left/top/right/bottom)分组成"行"：纵坐标
//有重叠的算同一行，行内再按横坐标从左到右排——同一行/第几行、行内从左到右
//第几个，这些相对位置关系还在，配合物理遥控器的方向键依然有参考意义。
//(之前试过完全按真实像素坐标等比例摆成一张"文字版截图"，但真实UI里元素间
//的间距往往比手机屏幕能塞下的密度稀疏得多，等比例复原会把列表拉得很长、
//每个格子周围显得空空荡荡；改成只保留行列的相对顺序、每个格子按文字内容
//天然大小紧凑排列，不追求像素级还原，整个列表能尽量矮。)
//
//输入框/开关/勾选框/单选这几类跟"点了直接触发动作"的普通按钮交互方式不
//一样(点输入框大概率会弹出电视端软键盘，点开关/勾选框/单选是切换状态)，
//用对应控件本身长相的小图标(而不是纯文字标签)标出来，跟这几种控件在
//系统设置里的实际样子一致，比文字前缀更直观；普通按钮和不好细分的可
//点击容器不加图标。title属性里保留文字版类型说明，方便长按/悬停查看。
//
//开关/勾选框/单选这三种本身带"开/关"状态——无障碍API的isChecked()能
//直接读到，不用靠猜；图标按这个状态分别画成"选中/未选中"两种样子(勾选框
//打勾/空框，单选实心点/空心圈，开关圆点在右/在左)，不用点开或者跳到电视
//画面上看才知道当前是开着还是关着的。
var ELEMENT_TYPE_ICONS = {
	input: {
		"false": '<svg viewBox="0 0 24 24" width="11" height="11" class="element-type-icon"><rect x="2" y="7" width="20" height="10" rx="2" stroke="currentColor" stroke-width="1.8" fill="none"/><line x1="7" y1="9.5" x2="7" y2="14.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>'
	},
	checkbox: {
		"true": '<svg viewBox="0 0 24 24" width="11" height="11" class="element-type-icon"><rect x="3" y="3" width="18" height="18" rx="3" stroke="currentColor" stroke-width="1.8" fill="none"/><path d="M7 12l3 3 7-7" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>',
		"false": '<svg viewBox="0 0 24 24" width="11" height="11" class="element-type-icon"><rect x="3" y="3" width="18" height="18" rx="3" stroke="currentColor" stroke-width="1.8" fill="none"/></svg>'
	},
	switch: {
		"true": '<svg viewBox="0 0 24 14" width="14" height="9" class="element-type-icon"><rect x="1" y="1" width="22" height="12" rx="6" stroke="currentColor" stroke-width="1.8" fill="none"/><circle cx="17" cy="7" r="4" fill="currentColor"/></svg>',
		"false": '<svg viewBox="0 0 24 14" width="14" height="9" class="element-type-icon"><rect x="1" y="1" width="22" height="12" rx="6" stroke="currentColor" stroke-width="1.8" fill="none"/><circle cx="7" cy="7" r="4" fill="currentColor"/></svg>'
	},
	radio: {
		"true": '<svg viewBox="0 0 24 24" width="11" height="11" class="element-type-icon"><circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" fill="none"/><circle cx="12" cy="12" r="4" fill="currentColor"/></svg>',
		"false": '<svg viewBox="0 0 24 24" width="11" height="11" class="element-type-icon"><circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" fill="none"/></svg>'
	}
};
var ELEMENT_TYPE_TITLE_TAGS = {
	input: "[输入] ",
	checkbox: "[勾选] ",
	switch: "[开关] ",
	radio: "[单选] "
};
function getElementTypeIcon(type, checked){
	var byState = ELEMENT_TYPE_ICONS[type];
	if(!byState) return "";
	return byState[checked ? "true" : "false"] || "";
}
//按纵坐标是否有重叠把元素分到同一行：排序后逐个扫描，只要当前元素的顶部
//还落在"已经在这一行的元素们目前最靠下的底边"之上，就算同一行；否则另起
//一行。行内按横坐标从左到右排。
function groupElementsIntoRows(elements){
	var sorted = elements.slice().sort(function(a, b){ return a.top - b.top; });
	var rows = [];
	var currentRow = [];
	var currentRowBottom = -Infinity;
	for(var i = 0; i < sorted.length; i++){
		var el = sorted[i];
		if(currentRow.length === 0 || el.top < currentRowBottom){
			currentRow.push(el);
			currentRowBottom = Math.max(currentRowBottom, el.bottom);
		}else{
			rows.push(currentRow);
			currentRow = [el];
			currentRowBottom = el.bottom;
		}
	}
	if(currentRow.length) rows.push(currentRow);
	for(var r = 0; r < rows.length; r++){
		rows[r].sort(function(a, b){ return a.left - b.left; });
	}
	return rows;
}
function loadScreenElements(){
	$("#elementsStatus").text("加载中…");
	$.post("/screenElements", null, function(data){
		var list = $("#elementsList");
		list.empty();
		if(!data || !data.enabled){
			$("#elementsStatus").text("无障碍服务未启用");
			list.html('<div class="elements-hint">需要先在电视盒子上"设置-无障碍"里开启"' +
				escapeHtml("TapTV") + '"服务，才能识别屏幕上的元素。<div class="btn" id="btnOpenAccessibilitySettings">去电视上开启</div></div>');
			return;
		}
		var elements = data.elements || [];
		if(elements.length === 0){
			$("#elementsStatus").text("已启用 · 当前屏幕没有识别到可点击元素");
			list.html('<div class="elements-hint">当前屏幕没有识别到可点击元素，切换一下电视画面后点"刷新"再试试。</div>');
			return;
		}
		$("#elementsStatus").text("已启用 · 共" + elements.length + "项操作");
		var rows = groupElementsIntoRows(elements);
		var html = [];
		for(var r = 0; r < rows.length; r++){
			html.push('<div class="elements-row">');
			for(var c = 0; c < rows[r].length; c++){
				var el = rows[r][c];
				var icon = getElementTypeIcon(el.type, el.checked);
				var titleText = (ELEMENT_TYPE_TITLE_TAGS[el.type] || "") + el.label;
				html.push('<div class="element-item type-' + (el.type || 'item') + (el.checked ? ' checked' : '') + '" data-id="' + el.id +
					'" title="' + escapeHtml(titleText) + '">' + icon + '<span>' + escapeHtml(el.label) + '</span></div>');
			}
			html.push('</div>');
		}
		list.html(html.join(""));
	}, "json").fail(function(){
		$("#elementsStatus").text("获取失败，请重试");
	});
}
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
	//输入框里如果还有没到150ms防抖延迟的/textLive请求排队(见下面"input"事件
	//绑定处)，得先取消掉，不然这个清空动作会被那个延迟请求"追上"：cls先把
	//电视端清空，紧接着排队的/textLive请求带着清空前的旧文字发出去，又把
	//电视端的输入内容重新填回去，导致清空看起来像是没生效。
	if(composingTimer){ clearTimeout(composingTimer); composingTimer = null; }
	postKeyCode($(this).attr("data-key"));
	//清空电视端输入内容的同时，web端输入框如果还残留着没提交的文字（组字预览
	//阶段），也要一并清空，否则两边会不一致：电视端已经清空了，网页上却还
	//显示着旧文字，这时候要是再点一下回车，又会把这段"已经清空过"的旧文字
	//重新发送一遍。
	$("#inputarea").val("");
	autoResizeInputArea();
})
$(".direction, #btnDel").on(isSupportTouch ? "touchstart" : "mousedown",function(){
		var o=$(this);
		o.addClass("pressed");
		vibrateShort();
		postKeyActionCode(o.attr("data-key"), 1);
		console.log("onkeydown:" + o.attr("data-key"));
})
//touchcancel(手指被系统手势/来电等打断，不会触发touchend)之前没处理——
//不止是视觉上的蓝色高亮卡住不消失，更麻烦的是postKeyActionCode(key,1)开的
//那个100ms一次的keydown重复定时器也永远不会被(key,2)那次调用清掉，等于
//电视端会一直收到这个方向键"按住不放"的信号。跟touchend一样处理即可。
$(".direction, #btnDel").on(isSupportTouch ? "touchend touchcancel" : "mouseup",function(){
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
$(".direction,.otherbtn").on(isSupportTouch ? "touchend touchcancel touchmove" : "mouseup mousemove", function() {
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
var PLAY_URL_PROTOCOLS = ['http://', 'https://', 'thunder://', 'ed2k://', 'ftp://', 'rtmp://', 'rtmps://', 'mms://'];
function isSupportedPlayUrl(url){
	url = url || '';
	for (var i = 0; i < PLAY_URL_PROTOCOLS.length; i++) {
		if (url.indexOf(PLAY_URL_PROTOCOLS[i]) == 0) return true;
	}
	return false;
}
function submitPlayUrl(url){
	$.post("/play", {playUrl: url, "useSystem":$('#playUseSystem')[0].checked}, function(data) {
		console.log(data)
	})
}
$("#btnPlay").on("click", function() {
	var url = $('#playUrl').val();
	if (url.length > 0 && isSupportedPlayUrl(url)){
		submitPlayUrl(url);
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
//设备名水印：复用MainActivity"⑤DLNA投屏名称"里用户自己起的名字("如：客厅、
//卧室")，同时开着多台设备的控制页时，光看网页内容本身长得一样，区分不出来
//是哪一台——水印文字直接显示在角标里，同时把它也写进页面标题，这样连浏览器
//标签栏本身都能看出区别，不用先点进某个标签页才知道是哪台。没设置过名字时
//什么都不显示，跟以前一样只有版本号。
function loadDeviceName(){
	$.get('/deviceName', function(name){
		name = (name || '').trim();
		if(name){
			$('#deviceNameWatermark').text(name + ' · ');
			document.title = name + ' - TapTV';
		}
	});
}
//"睡眠"键(#sleep-btn)不需要这里单独写点击逻辑——它跟其它遥控键一样带
//class="otherbtn"，上面通用的".otherbtn"点击处理器(postKeyCode(o.attr(
//"data-key")))已经会把data-key="sleep"发出去，落到IMEService里"sleep"
//这个特判分支，走无障碍服务的GLOBAL_ACTION_LOCK_SCREEN。不依赖ADB，
//所以不需要像之前的电源键那样常驻轮询连接状态、按状态显示/隐藏按钮。
//
//但GLOBAL_ACTION_LOCK_SCREEN这个无障碍API本身是Android 9才加入系统的，
//电视盒子系统版本低于这个的话，点了这个键无障碍服务会直接返回false、
//什么都不会发生——与其让按键摆在那儿点了没反应，不如干脆不显示。这是
//设备系统版本决定的固定能力，只要页面不刷新就不会变，跟ADB/软键盘状态
//那种会随时变化的情况不一样，所以只在打开页面时查一次，不用定期轮询。
$.get("/accessibilityStatus", function(data){
	if(!data || !data.lockScreenSupported){
		$("#sleep-btn").addClass("hide");
	}
}, "json").fail(function(){
	$("#sleep-btn").addClass("hide");
});
var KEEPALIVE_INTERVAL_MS = 4000;
//电视端软键盘(带二维码，没有活跃控制端连着时靠它扫码)显示/隐藏：这里只
//负责读取/切换状态并同步按键的高亮外观，具体"什么时候默认显示/隐藏"的
//判断逻辑在Environment.isKeyboardViewVisible里(当前有没有活跃客户端来定，
//不是"历史上连没连过")，网页这边不用关心、只管显示当前的真实状态。
//
//这个/keyboardViewStatus请求同时还兼职当"心跳"用：电视端靠这类鉴权通过的
//请求判断控制端是否还"活跃"(RemoteServer.hasActiveClient())，如果只在
//打开页面时查一次，用户只是打开着页面看、不点任何按键的话，过一会儿电视端
//会误判成"没有活跃客户端"而把软键盘弹出来挡住画面。所以只要这个控制页
//还开着，就定期发一次(间隔比服务端判活的超时阈值短很多，复用上面
//KEEPALIVE_INTERVAL_MS这个间隔)，不需要用户有任何操作。
function refreshKeyboardViewBtn(){
	$.get("/keyboardViewStatus", function(data){
		$("#btnToggleKeyboard").toggleClass("active", !!(data && data.visible));
	}, "json");
}
$("#btnToggleKeyboard").on("click", function(){
	vibrateShort();
	var willShow = !$(this).hasClass("active");
	$.post("/setKeyboardViewVisible", {visible: willShow}, function(){
		refreshKeyboardViewBtn();
	});
})
refreshKeyboardViewBtn();
setInterval(refreshKeyboardViewBtn, KEEPALIVE_INTERVAL_MS);
reloadAppList();
loadFileList("");
getDiskSpace();
loadTVList();
loadMediaConfig();
loadTorrentItems();

//PWA分享目标(manifest.json里的share_target)：手机其它App"分享"一个视频链接过来时，
//系统会带着title/text/url这几个查询参数打开这个页面——不同App放链接的字段不统一
//(纯分享链接一般用url，转发一段带评论的文字时链接常常混在text里)，所以url/text/
//title都尝试提取一遍。提取成功后自动切到"视频直播"Tab、填入地址并直接触发播放，
//不需要用户再手动复制粘贴；处理完立刻清掉地址栏参数，避免刷新页面时重复触发。
function extractPlayUrlFromShare(){
	var params = new URLSearchParams(location.search);
	var candidates = [params.get('url'), params.get('text'), params.get('title')];
	for (var i = 0; i < candidates.length; i++) {
		var value = candidates[i];
		if (!value) continue;
		value = value.trim();
		if (isSupportedPlayUrl(value)) return value;
		var match = value.match(/(https?:\/\/\S+)/i);
		if (match) return match[1];
	}
	return null;
}
var sharedPlayUrl = extractPlayUrlFromShare();
if (sharedPlayUrl) {
	history.replaceState(null, '', location.pathname);
	$('div.tab[data-rel="video"]').trigger('click');
	$('#playUrl').val(sharedPlayUrl);
	submitPlayUrl(sharedPlayUrl);
}
showCurrentVersion();
loadDeviceName();
updateContainerWidth();
updateElementsAutoRefresh();
