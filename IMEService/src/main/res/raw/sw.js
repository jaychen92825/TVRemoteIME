// 只给页面外壳（HTML/CSS/JS/图标）做离线缓存兜底，让PWA能安装、下次打开快一点。
// 所有控制指令都是POST请求或者动态GET（文件列表/应用列表/直播源等），一律不缓存、
// 直接放过给网络请求处理，避免任何遥控指令被缓存干扰。
var CACHE_NAME = "tvremoteime-shell-v1";
var SHELL_FILES = ["/", "/index.html", "/style.css", "/jquery_min.js", "/ime_core.js", "/icon.png"];

self.addEventListener("install", function(event){
	event.waitUntil(
		caches.open(CACHE_NAME).then(function(cache){
			return cache.addAll(SHELL_FILES);
		})
	);
	self.skipWaiting();
});

self.addEventListener("activate", function(event){
	event.waitUntil(
		caches.keys().then(function(names){
			return Promise.all(names.filter(function(n){
				return n !== CACHE_NAME;
			}).map(function(n){
				return caches.delete(n);
			}));
		})
	);
	self.clients.claim();
});

self.addEventListener("fetch", function(event){
	var req = event.request;
	if(req.method !== "GET") return;
	var path = new URL(req.url).pathname;
	if(SHELL_FILES.indexOf(path) === -1) return;

	event.respondWith(
		fetch(req).then(function(res){
			var resClone = res.clone();
			caches.open(CACHE_NAME).then(function(cache){
				cache.put(req, resClone);
			});
			return res;
		}).catch(function(){
			return caches.match(req);
		})
	);
});
