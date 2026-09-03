package com.easysys.api.assistant;

import com.easysys.agent.WorkflowDialoguePolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库分块：复用工作流助手的句子切分器（句末标点 + 逗号软切 ≤ 60 字），
 * 按目标字数把连续句子聚成块 —— 块边界天然落在句子上，检索引用可读。
 */
public final class TextChunker {

    public static final int TARGET_CHARS = 500;

    private TextChunker() {
    }

    public static List<String> chunk(String text) {
        List<String> sentences = WorkflowDialoguePolicy.sentences(text);
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (!buf.isEmpty() && buf.length() + s.length() > TARGET_CHARS) {
                out.add(buf.toString().trim());
                buf.setLength(0);
            }
            buf.append(s);
        }
        if (!buf.isEmpty()) {
            out.add(buf.toString().trim());
        }
        return out;
    }
}