package com.android.tvremoteime.server;

/**
 * Created by kingt on 2018/1/10.
 */
import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.android.tvremoteime.IMEService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import fi.iki.elonen.*;
import xllib.DownloadManager;
import xllib.DownloadTask;

public class RemoteServerFileManager implements NanoHTTPD.TempFileManager {
    static File baseDir = new File(Environment.getExternalStorageDirectory(), "tvremoteime");
    private static File filesDir = new File(baseDir, "files");
    private static File tmpDataDir = new File(baseDir, "temp");
    private static File playerCacheDir = new File(baseDir, "xlplayer");

    static File getPlayTorrentFile(){
        return new File(RemoteServerFileManager.baseDir, "play.torrent");
    }

    /**
     * 将HTTP接口收到的相对路径解析为root目录下的文件，并校验解析结果没有借助"../"
     * 逃逸出root目录（路径穿越）。校验失败（含相对路径为空、解析出错）时返回null，
     * 调用方应视为非法请求处理，不再往下访问文件系统。
     */
    public static File resolveSafeFile(File root, String relativePath){
        if(relativePath == null) return null;
        try {
            String rootCanonical = root.getCanonicalPath();
            File target = new File(root, relativePath);
            String targetCanonical = target.getCanonicalPath();
            if(targetCanonical.equals(rootCanonical) || targetCanonical.startsWith(rootCanonical + File.separator)){
                return target;
            }
        } catch (IOException ignored) {
        }
        return null;
    }
    public static File getScreenShotFile(){
        return new File(RemoteServerFileManager.baseDir, "screenshot.png");
    }
    public static void resetBaseDir(Context context){
        baseDir = context.getExternalFilesDir(null);
        filesDir = new File(baseDir, "files");
        tmpDataDir = new File(baseDir, "temp");
        playerCacheDir = new File(baseDir, "xlplayer");
    }
    public static class SDCardTempFile implements NanoHTTPD.TempFile {
        private final File file;
        private OutputStream fstream;
        private boolean isTemp;
        public SDCardTempFile(String fileName) throws IOException {
            if(fileName == null || fileName.isEmpty()){
                this.file = File.createTempFile("tmp-", "", tmpDataDir);
                isTemp = true;
            }else {
                this.file = new File(filesDir, fileName);
                isTemp = false;
            }
            try {
                if (file.exists()) file.delete();
            }catch (Exception ignored){}
        }

        public void delete() throws Exception {
            if(this.fstream != null) this.fstream.close();
            //上传的文件不用删除
            if(this.isTemp && !this.file.delete()) {
                Log.e(IMEService.TAG, String.format("无法删除临时文件[%s]", this.getName()));
            }
        }

        public String getName() {
            return this.file.getAbsolutePath();
        }

        public OutputStream open() throws Exception {
            if(this.fstream == null) this.fstream = new FileOutputStream(this.file);
            return this.fstream;
        }
    }

    private final List<NanoHTTPD.TempFile> tempFiles;
    private RemoteServerFileManager() {
        this.tempFiles = new ArrayList<NanoHTTPD.TempFile>();
    }
    @Override
    public void clear() {
        if (!this.tempFiles.isEmpty()) {
            for (NanoHTTPD.TempFile file : this.tempFiles) {
                try {
                    file.delete();
                } catch (Exception ignored) {
                    Log.e(IMEService.TAG, String.format("删除临时文件[%s]时出错.", file.getName()), ignored);
                }
            }
            this.tempFiles.clear();
        }
    }

    @Override
    public NanoHTTPD.TempFile createTempFile(String fileName) throws Exception {
        if(!TextUtils.isEmpty(fileName)) {
            fileName = URLDecoder.decode(fileName, "utf-8").replaceAll("[\\\\|/]", "").replaceAll("\\.\\.", "");
        }
        NanoHTTPD.TempFile tmpFile = new SDCardTempFile(fileName);
        tempFiles.add(tmpFile);
        return tmpFile;
    }

    public static class Factory implements NanoHTTPD.TempFileManagerFactory {
        @Override
        public NanoHTTPD.TempFileManager create() {
            try{
                if(!filesDir.exists())filesDir.mkdirs();
                if(!tmpDataDir.exists())tmpDataDir.mkdirs();
                DownloadTask.setBaseDirGetter(new DownloadTask.DownloadTaskBaseDirGetter() {
                    @Override
                    public File getBaseDir() {
                        return playerCacheDir;
                    }
                });
            }catch (Exception ignored){}
            return new RemoteServerFileManager();
        }
    }

    public static void clearAllFiles(){
        try{
            if(filesDir.exists()) deleteDirFiles(filesDir);
            if(tmpDataDir.exists()) deleteDirFiles(filesDir);
            if(playerCacheDir != null && playerCacheDir.exists()) deleteDirFiles(playerCacheDir);
            File torrentFile = getPlayTorrentFile();
            if(torrentFile.exists()) torrentFile.delete();
        }catch (Exception ignored){

        }
    }
    public static void deleteDirFiles(File file){
        File[] files =  file.listFiles();
        for (File f : files) {
            try {
                if(f.isDirectory())deleteDirFiles(f);
                f.delete();
            } catch (SecurityException ignored) {
            }
        }
    }
    public static void deleteFile(File file){
        if(file.exists()) {
            if (file.isDirectory()) {
                deleteDirFiles(file);
            }
            try {
                file.delete();
            } catch (SecurityException ignored) {
            }
        }
    }
    public static void cutFile(File sourceFile, File targetPath){
        if(sourceFile.exists()) {
            File targetFile = new File(targetPath, sourceFile.getName());
            try{
                sourceFile.renameTo(targetFile);
            } catch (SecurityException ignored) {
            }
            /**
            if (sourceFile.isDirectory()) {
                File newDir = new File(targetPath, sourceFile.getName());
                if(newDir.mkdir()){
                    File[] files =  sourceFile.listFiles();
                    for (File f : files) {
                        cutFile(f, newDir);
                    }
                    deleteFile(sourceFile);
                }
            } else {
                File targetFile = new File(targetPath, sourceFile.getName());
                sourceFile.renameTo(targetFile);
            }
             **/
        }
    }
    public static void copyFile(File sourceFile, File targetPath){
        if(sourceFile.exists()) {
            if (sourceFile.isDirectory()) {
                File newDir = new File(targetPath, sourceFile.getName());
                if(newDir.mkdir()){
                    File[] files =  sourceFile.listFiles();
                    for (File f : files) {
                        copyFile(f, newDir);
                    }
                }
            } else {
                File targetFile = new File(targetPath, sourceFile.getName());
                copyFileImpl(sourceFile, targetFile);
            }
        }
    }
    private static void copyFileImpl(File sourceFile, File targetFile){
        if(targetFile.exists()) return;
        try {
            FileInputStream ins = new FileInputStream(sourceFile);
            FileOutputStream out = new FileOutputStream(targetFile);
            byte[] b = new byte[1024];
            int n = 0;
            while ((n = ins.read(b)) != -1) {
                out.write(b, 0, n);
            }
            ins.close();
            out.close();
        }catch (Exception ignored) {
        }
    }
}
