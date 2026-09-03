package com.easysys.api.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库词元化（确定性，无向量模型）：
 * 拉丁/数字连续串整体小写为一词元，汉字串按二元组切分（奇数末尾退化为单字）。
 * 标点、空白与全角/半角符号均不产出词元；输出按首次出现顺序聚合词频。
 */
public final class CjkTokenizer {

    private CjkTokenizer() {
    }

    /** 词元 → 词频（分块/文档解析用）。 */
    public static Map<String, Integer> counts(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<Integer> latin = new ArrayList<>();
        List<Integer> han = new ArrayList<>();

        int cp;
        for (int i = 0; i < text.length(); i += Character.charCount(cp)) {
            cp = text.codePointAt(i);
            if (isLatinDigit(cp)) {
                flushHan(han, out);
                latin.add(cp);
            } else if (isHan(cp)) {
                flushLatin(latin, out);
                han.add(cp);
            } else {
                flushLatin(latin, out);
                flushHan(han, out);
            }
        }
        flushLatin(latin, out);
        flushHan(han, out);
        return out;
    }

    /** 查询词元（去重，顺序稳定）。 */
    public static List<String> keys(String text) {
        return new ArrayList<>(counts(text).keySet());
    }

    private static void flushLatin(List<Integer> run, Map<String, Integer> out) {
        if (run.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(run.size());
        for (int c : run) {
            sb.append((char) Character.toLowerCase(c));
        }
        out.merge(sb.toString(), 1, Integer::sum);
        run.clear();
    }

    private static void flushHan(List<Integer> run, Map<String, Integer> out) {
        int n = run.size();
        if (n == 0) {
            return;
        }
        if (n == 1) {
            out.merge(new String(Character.toChars(run.get(0))), 1, Integer::sum);
        } else {
            for (int i = 0; i + 1 < n; i += 2) {
                String bigram = new String(Character.toChars(run.get(i)))
                        + new String(Character.toChars(run.get(i + 1)));
                out.merge(bigram, 1, Integer::sum);
            }
            if (n % 2 == 1) {
                out.merge(new String(Character.toChars(run.get(n - 1))), 1, Integer::sum);
            }
        }
        run.clear();
    }

    private static boolean isLatinDigit(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9');
    }

    private static boolean isHan(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }
}