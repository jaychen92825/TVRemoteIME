package com.android.tvremoteime.media;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.android.tvremoteime.IMEService;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import dalvik.system.DexClassLoader;

public class MediaSpiderManager {
    private static MediaSpiderManager instance;

    private final Context context;
    private final Map<String, DexClassLoader> loaders = new HashMap<String, DexClassLoader>();
    private final Map<String, Spider> spiders = new HashMap<String, Spider>();

    public static synchronized MediaSpiderManager get(Context context) {
        if (instance == null) instance = new MediaSpiderManager(context.getApplicationContext());
        return instance;
    }

    private MediaSpiderManager(Context context) {
        this.context = context;
    }

    public synchronized Spider getSpider(MediaSource source) throws Exception {
        if (source == null || !source.isType3Csp()) return new SpiderNull();
        String loaderKey = md5(source.spider);
        String spiderKey = loaderKey + ":" + source.key;
        Spider cached = spiders.get(spiderKey);
        if (cached != null) return cached;

        DexClassLoader loader = getLoader(loaderKey, source.spider);
        String className = "com.github.catvod.spider." + source.api.substring("csp_".length());
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Spider spider = (Spider) loader.loadClass(className).newInstance();
            spider.siteKey = source.key;
            spider.init(context, MediaItem.safe(source.ext));
            spiders.put(spiderKey, spider);
            return spider;
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private DexClassLoader getLoader(String key, String jarSpec) throws Exception {
        DexClassLoader loader = loaders.get(key);
        if (loader != null) return loader;
        File jar = prepareJar(key, jarSpec);
        File dexDir = context.getDir("media_spider_dex", Context.MODE_PRIVATE);
        File libDir = context.getDir("media_spider_lib", Context.MODE_PRIVATE);
        ClassLoader parent = dependencyLoader(jarSpec, dexDir, libDir);
        loader = new DexClassLoader(jar.getAbsolutePath(), dexDir.getAbsolutePath(), libDir.getAbsolutePath(), parent);
        invokeInit(loader);
        loaders.put(key, loader);
        return loader;
    }

    private File prepareJar(String key, String jarSpec) throws Exception {
        String[] parts = jarSpec.split(";md5;", 2);
        String jarUrl = parts[0].trim();
        String expectedMd5 = parts.length > 1 ? parts[1].trim() : "";
        if (expectedMd5.startsWith("http://") || expectedMd5.startsWith("https://")) {
            expectedMd5 = MediaHttp.getRequired(expectedMd5).trim();
        }
        if (jarUrl.startsWith("file:")) {
            File local = new File(new URI(jarUrl));
            if (!local.exists() || local.length() == 0) throw new Exception("spider jar 文件不存在");
            return local;
        }
        if (!jarUrl.startsWith("http://") && !jarUrl.startsWith("https://")) throw new Exception("不支持的 spider jar 地址");

        File dir = context.getDir("media_spider_jar", Context.MODE_PRIVATE);
        File file = new File(dir, key + ".jar");
        if (isValid(file, expectedMd5)) return file;

        MediaHttp.downloadRequired(jarUrl, file);
        if (!isValid(file, expectedMd5)) {
            file.delete();
            throw new Exception("spider jar 校验失败");
        }
        file.setReadOnly();
        return file;
    }

    private ClassLoader dependencyLoader(String jarSpec, File dexDir, File libDir) {
        String jarUrl = jarSpec.split(";md5;", 2)[0].trim();
        ClassLoader parent = context.getClassLoader();
        if (!needsMergeDependency(jarUrl)) return parent;
        try {
            String dependencyUrl = new URL(new URL(jarUrl), "fty.jar").toString();
            File file = new File(context.getDir("media_spider_dep", Context.MODE_PRIVATE), md5(dependencyUrl) + ".jar");
            if (!isValid(file, "")) MediaHttp.downloadRequired(dependencyUrl, file);
            file.setReadOnly();
            return new DexClassLoader(file.getAbsolutePath(), dexDir.getAbsolutePath(), libDir.getAbsolutePath(), parent);
        } catch (Throwable e) {
            Log.w(IMEService.TAG, "media spider dependency skipped", e);
            return parent;
        }
    }

    private boolean needsMergeDependency(String jarUrl) {
        String lower = jarUrl == null ? "" : jarUrl.toLowerCase();
        return lower.endsWith("/fan.txt") || lower.endsWith("/fan.jar") || lower.contains("/fan.");
    }

    private boolean isValid(File file, String expectedMd5) throws Exception {
        if (file == null || !file.exists() || file.length() == 0) return false;
        return TextUtils.isEmpty(expectedMd5) || expectedMd5.equalsIgnoreCase(md5(file));
    }

    private void invokeInit(DexClassLoader loader) {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            Method method = clz.getMethod("init", Context.class);
            method.invoke(clz, context);
        } catch (Throwable e) {
            Log.w(IMEService.TAG, "media spider init skipped", e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private String md5(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        } finally {
            input.close();
        }
        return hex(digest.digest());
    }

    private String md5(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(value.getBytes("UTF-8"));
        return hex(digest.digest());
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            String value = Integer.toHexString(b & 0xff);
            if (value.length() == 1) builder.append('0');
            builder.append(value);
        }
        return builder.toString();
    }
}
