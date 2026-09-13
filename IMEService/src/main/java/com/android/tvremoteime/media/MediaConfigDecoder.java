package com.android.tvremoteime.media;

import android.util.Base64;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MediaConfigDecoder {
    private static final Pattern BASE64_MARKER = Pattern.compile("[A-Za-z0-9]{8}\\*\\*");
    private static final Pattern JS_URI = Pattern.compile("\"(\\.|\\.\\.)/(.?|.+?)\\.js\\?(.?|.+?)\"");

    public static String decode(String url, String data) throws Exception {
        if (data == null) return "";
        String result = data.trim();
        if (result.length() == 0) return result;
        if (result.startsWith("{")) return fixRelative(url, result);
        if (result.contains("**")) result = decodeBase64(result);
        if (result.startsWith("2423")) result = decodeAesCbc(result.replaceAll("\\s+", ""));
        return fixRelative(url, result);
    }

    private static String decodeBase64(String data) throws Exception {
        Matcher matcher = BASE64_MARKER.matcher(data);
        if (!matcher.find()) return data;
        String encoded = data.substring(data.indexOf(matcher.group()) + 10);
        return new String(Base64.decode(encoded, Base64.DEFAULT), "UTF-8");
    }

    private static String decodeAesCbc(String data) throws Exception {
        String decoded = new String(hexToBytes(data), "UTF-8").toLowerCase();
        String key = padEnd(decoded.substring(decoded.indexOf("$#") + 2, decoded.indexOf("#$")));
        String iv = padEnd(decoded.substring(decoded.length() - 13));
        String payload = data.substring(data.indexOf("2324") + 4, data.length() - 26);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes("UTF-8"), "AES"), new IvParameterSpec(iv.getBytes("UTF-8")));
        return new String(cipher.doFinal(hexToBytes(payload)), "UTF-8");
    }

    private static String fixRelative(String url, String data) {
        Matcher matcher = JS_URI.matcher(data);
        while (matcher.find()) data = replaceRelativeScript(url, data, matcher.group());
        if (data.contains("../")) data = data.replace("../", resolve(url, "../"));
        if (data.contains("./")) data = data.replace("./", resolve(url, "./"));
        if (data.contains("__JS1__")) data = data.replace("__JS1__", "./");
        if (data.contains("__JS2__")) data = data.replace("__JS2__", "../");
        return data;
    }

    private static String replaceRelativeScript(String url, String data, String ext) {
        String value = ext.replace("\"./", "\"" + resolve(url, "./"));
        value = value.replace("\"../", "\"" + resolve(url, "../"));
        value = value.replace("./", "__JS1__").replace("../", "__JS2__");
        return data.replace(ext, value);
    }

    private static String resolve(String baseUrl, String relative) {
        try {
            return new java.net.URL(new java.net.URL(baseUrl), relative).toString();
        } catch (Exception e) {
            return relative;
        }
    }

    private static byte[] hexToBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = Integer.valueOf(value.substring(i * 2, i * 2 + 2), 16).byteValue();
        }
        return bytes;
    }

    private static String padEnd(String value) {
        return value + "0000000000000000".substring(value.length());
    }
}
