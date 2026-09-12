package com.android.tvremoteime;

import android.graphics.Bitmap;

import java.util.HashMap;
import java.util.Map;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * Created by kingt on 2018/1/8.
 */

public class QRCodeGen {
    public static Bitmap generateBitmap(String content, int width, int height) {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        //不同hint要求的值类型不一样(CHARACTER_SET要String，MARGIN要Integer——
        //QRCodeWriter内部是直接强转成Integer读取的，塞个String进去会在
        //运行时抛ClassCastException)，这里用Object类型的Map能同时装下两种。
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "utf-8");
        //zxing默认留4个模块宽的静区(quiet zone)，在给定的width/height里会有一圈
        //很厚的白边、没有真正用满显示区域。QR码规范建议的静区是为了兼容各种
        //扫码设备/光线条件下的最大可靠性，但这里是手机摄像头近距离扫自家电视
        //屏幕这种可控场景，缩到1个模块宽依然能可靠扫描，同时把显示区域基本用满。
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            BitMatrix encode = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height, hints);
            int[] pixels = new int[width * height];
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    if (encode.get(j, i)) {
                        pixels[i * width + j] = 0x00000000;
                    } else {
                        pixels[i * width + j] = 0xffffffff;
                    }
                }
            }
            return Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.RGB_565);
        } catch (WriterException e) {
            e.printStackTrace();
        }
        return null;
    }
}
