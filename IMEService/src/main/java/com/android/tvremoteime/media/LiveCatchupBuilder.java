package com.android.tvremoteime.media;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LiveCatchupBuilder {
    private static final Pattern FORMAT_TOKEN = Pattern.compile("\\$\\{\\((b|e)\\)([^}]+)\\}");

    private LiveCatchupBuilder() {
    }

    public static String build(String baseUrl, String type, String source,
                               Date begin, Date end, TimeZone zone) throws Exception {
        if (begin == null || end == null || !end.after(begin)) {
            throw new Exception("节目时间无效，无法生成回看地址");
        }
        String normalized = trim(type).toLowerCase();
        String rendered = render(source, begin, end, zone);
        if ("append".equals(normalized)) {
            if (isEmpty(rendered)) throw new Exception("回看规则缺少 catchup source");
            return baseUrl + rendered;
        }
        if ("default".equals(normalized)) {
            if (isEmpty(rendered)) {
                String separator = baseUrl.indexOf('?') >= 0 ? "&" : "?";
                rendered = separator + "utc=" + (begin.getTime() / 1000L)
                        + "&duration=" + Math.max(1L, (end.getTime() - begin.getTime()) / 1000L);
            }
            return baseUrl + rendered;
        }
        if ("shift".equals(normalized) || "replace".equals(normalized)) {
            if (isEmpty(rendered)) throw new Exception("回看规则缺少 catchup source");
            return rendered;
        }
        throw new Exception("暂不支持这种回看类型：" + type);
    }

    public static String render(String source, Date begin, Date end, TimeZone zone) throws Exception {
        String result = source == null ? "" : source;
        TimeZone safeZone = zone == null ? TimeZone.getDefault() : zone;
        Matcher matcher = FORMAT_TOKEN.matcher(result);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            Date date = "e".equalsIgnoreCase(matcher.group(1)) ? end : begin;
            String formatted;
            try {
                SimpleDateFormat formatter = new SimpleDateFormat(matcher.group(2));
                formatter.setTimeZone(safeZone);
                formatted = formatter.format(date);
            } catch (IllegalArgumentException e) {
                throw new Exception("回看时间格式无效：" + matcher.group(2));
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(formatted));
        }
        matcher.appendTail(buffer);
        result = buffer.toString();

        long beginSec = begin.getTime() / 1000L;
        long endSec = end.getTime() / 1000L;
        long nowSec = System.currentTimeMillis() / 1000L;
        long duration = Math.max(1L, endSec - beginSec);
        long offset = Math.max(0L, nowSec - beginSec);
        result = replace(result, "{utc}", beginSec);
        result = replace(result, "${start}", beginSec);
        result = replace(result, "{start}", beginSec);
        result = replace(result, "${end}", endSec);
        result = replace(result, "{end}", endSec);
        result = replace(result, "{duration}", duration);
        result = replace(result, "${duration}", duration);
        result = replace(result, "{lutc}", nowSec);
        result = replace(result, "${timestamp}", nowSec);
        result = replace(result, "{current_utc}", nowSec);
        result = replace(result, "{offset}", offset);
        result = replace(result, "${offset}", offset);
        result = result.replace("{start_iso}", iso(begin)).replace("{now_iso}", iso(new Date()));
        return result;
    }

    private static String replace(String value, String token, long replacement) {
        return value.replace(token, String.valueOf(replacement));
    }

    private static String iso(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }
}
