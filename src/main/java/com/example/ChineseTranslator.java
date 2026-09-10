package com.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.Translator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChineseTranslator implements Translator {

    private final Key key = Key.key("containerinspector", "chinese_translations");
    private final Map<String, MessageFormat> translations = new HashMap<>();

    public void load(InputStream is) {
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject jsonObject = new Gson().fromJson(reader, JsonObject.class);

            jsonObject.entrySet().forEach(entry -> {
                String k = entry.getKey();
                String v = entry.getValue().getAsString();

                try {
                    // 1. 将 Minecraft 的 %s / %1$s 转换并格式化为 MessageFormat 格式
                    String pattern = preparePattern(v);
                    translations.put(k, new MessageFormat(pattern, Locale.CHINA));
                } catch (Exception e) {
                    // 2. 降级处理：若包含非法花括号，强行转义后作为纯文本加载
                    try {
                        String escaped = v.replace("'", "''")
                                .replace("{", "'{'")
                                .replace("}", "'}'");
                        translations.put(k, new MessageFormat(escaped, Locale.CHINA));
                    } catch (Exception ignored) {
                        // 极个别异常键直接忽略
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 将 Minecraft 风格的占位符 (%s, %1$s) 转换为 Java MessageFormat 的 ({0}, {1})，并转义单引号
     */
    private String preparePattern(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // 转义 MessageFormat 的特殊字符 '
        String result = text.replace("'", "''");

        // 处理位置占位符，如 %1$s -> {0}
        Pattern indexPattern = Pattern.compile("%([0-9]+)\\$[a-zA-Z]");
        Matcher indexMatcher = indexPattern.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (indexMatcher.find()) {
            int idx = Integer.parseInt(indexMatcher.group(1)) - 1;
            indexMatcher.appendReplacement(sb, "{" + Math.max(0, idx) + "}");
        }
        indexMatcher.appendTail(sb);
        result = sb.toString();

        // 处理普通占位符，如 %s -> {0}, {1}...
        Pattern seqPattern = Pattern.compile("%[a-zA-Z]");
        Matcher seqMatcher = seqPattern.matcher(result);
        StringBuilder seqSb = new StringBuilder();
        int seqCount = 0;
        while (seqMatcher.find()) {
            seqMatcher.appendReplacement(seqSb, "{" + (seqCount++) + "}");
        }
        seqMatcher.appendTail(seqSb);

        return seqSb.toString();
    }

    @Override
    public @NotNull Key name() {
        return key;
    }

    @Override
    public @Nullable MessageFormat translate(@NotNull String key, @NotNull Locale locale) {
        return translations.get(key);
    }
}