package com.easysys.engine.dag;

import com.easysys.engine.model.NodeType;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 结构校验：节点唯一性 / 单触发器 / 边引用完整 / 无环 / 可达性 / 出边端口规则 / 条件分流约束。
 * 返回错误列表（空 = 通过），不抛异常。调用方（保存/发布前）用 {@link #valid()} 判定。
*/
@Component
public class DagValidator {

    /** 节点轻量结构（画布/表行统一转换为该形态后校验） */
    public record NodeDef(String key, String type, JsonNode config) {
    }

    /** 边轻量结构；condition == null 表示无条件边（CONDITION 的兜底 else） */
    public record EdgeDef(String sourceKey, String targetKey, JsonNode condition) {
    }

    public record ValidationResult(List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public ValidationResult validate(List<NodeDef> nodes, List<EdgeDef> edges) {
        List<String> errors = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) {
            errors.add("至少需要一个节点");
        }
        Map<String, NodeDef> byKey = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int triggerCount = 0;
        for (NodeDef n : nodes) {
            if (n == null || n.key() == null || n.key().isBlank()) {
                errors.add("存在缺少 key 的节点");
                continue;
            }
            if (!seen.add(n.key())) {
                errors.add("节点 key 重复: " + n.key());
                continue;
            }
            byKey.put(n.key(), n);
            NodeType t = typeOf(n.type());
            if (t == null) {
                errors.add("节点 " + n.key() + " 的 type 非法: " + n.type());
            } else if (t == NodeType.TRIGGER) {
                triggerCount++;
            }
        }
        if (triggerCount != 1) {
            errors.add("必须恰好 1 个 TRIGGER 节点，实际 " + triggerCount);
        }

        Map<String, List<EdgeDef>> outEdges = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String k : byKey.keySet()) {
            outEdges.put(k, new ArrayList<>());
            inDegree.put(k, 0);
        }
        Set<String> edgeKeys = new HashSet<>();
        for (EdgeDef e : edges) {
            if (e == null || e.sourceKey() == null || e.targetKey() == null) {
                errors.add("存在缺 source/target 的边");
                continue;
            }
            if (!byKey.containsKey(e.sourceKey())) {
                errors.add("边引用未知 source: " + e.sourceKey());
            }
            if (!byKey.containsKey(e.targetKey())) {
                errors.add("边引用未知 target: " + e.targetKey());
            }
            if (!byKey.containsKey(e.sourceKey()) || !byKey.containsKey(e.targetKey())) {
                continue;
            }
            if (e.sourceKey().equals(e.targetKey())) {
                errors.add("自环边: " + e.sourceKey());
                continue;
            }
            if (!edgeKeys.add(e.sourceKey() + "->" + e.targetKey())) {
                errors.add("重复边: " + e.sourceKey() + " -> " + e.targetKey());
                continue;
            }
            outEdges.get(e.sourceKey()).add(e);
            inDegree.merge(e.targetKey(), 1, Integer::sum);
        }

        // 入度：非 TRIGGER 必须有入边
        String triggerKey = byKey.entrySet().stream()
                .filter(x -> x.getValue().type() != null && typeOf(x.getValue().type()) == NodeType.TRIGGER)
                .map(Map.Entry::getKey).findFirst().orElse(null);
        for (Map.Entry<String, Integer> x : inDegree.entrySet()) {
            if (x.getKey().equals(triggerKey)) {
                if (x.getValue() != 0) {
                    errors.add("TRIGGER 节点不允许有入边: " + x.getKey());
                }
            } else if (x.getValue() == 0) {
                errors.add("孤立节点（无入边）: " + x.getKey());
            }
        }

        // 环检测（三色 DFS）
        String cycle = findCycle(byKey.keySet(), outEdges);
        if (cycle != null) {
            errors.add("存在环: " + cycle);
        }

        // TRIGGER 可达性（正向 BFS）
        if (triggerKey != null) {
            Set<String> reached = reachable(triggerKey, outEdges);
            for (String k : byKey.keySet()) {
                if (!reached.contains(k)) {
                    errors.add("节点不可达（TRIGGER 到不了）: " + k);
                }
            }
        }

        // 到 END 的可达性（反向 BFS，从 END 出发）
        Set<String> ends = new HashSet<>();
        for (Map.Entry<String, NodeDef> x : byKey.entrySet()) {
            if (typeOf(x.getValue().type()) == NodeType.END) {
                ends.add(x.getKey());
            }
        }
        if (ends.isEmpty()) {
            errors.add("至少需要一个 END 节点");
        } else {
            Map<String, List<String>> reverse = new HashMap<>();
            for (String k : byKey.keySet()) {
                reverse.put(k, new ArrayList<>());
            }
            for (EdgeDef e : edges) {
                if (byKey.containsKey(e.sourceKey()) && byKey.containsKey(e.targetKey())) {
                    reverse.get(e.targetKey()).add(e.sourceKey());
                }
            }
            Set<String> canReachEnd = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(ends);
            canReachEnd.addAll(ends);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                for (String prev : reverse.get(cur)) {
                    if (canReachEnd.add(prev)) {
                        queue.add(prev);
                    }
                }
            }
            for (String k : byKey.keySet()) {
                if (!canReachEnd.contains(k)) {
                    errors.add("节点无路径到达 END: " + k);
                }
            }
        }

        // 端口与条件边规则
        int condElseEdges = 0;
        for (Map.Entry<String, List<EdgeDef>> x : outEdges.entrySet()) {
            NodeType t = typeOf(byKey.get(x.getKey()).type());
            int out = x.getValue().size();
            int condEdges = 0;
            int elseEdges = 0;
            for (EdgeDef e : x.getValue()) {
                if (e.condition() == null || e.condition().isNull() || isEmptyObject(e.condition())) {
                    elseEdges++;
                } else {
                    condEdges++;
                }
            }
            switch (t == null ? NodeType.END : t) {
                case TRIGGER -> {
                    if (out == 0) {
                        errors.add("TRIGGER 节点必须有出边: " + x.getKey());
                    }
                }
                case CONDITION -> {
                    if (condEdges == 0) {
                        errors.add("CONDITION 节点必须至少一条带条件出边: " + x.getKey());
                    }
                    if (elseEdges > 1) {
                        errors.add("CONDITION 节点只允许至多一条无条件（兜底）出边: " + x.getKey());
                    }
                }
                case AGENT_SPLIT -> {
                    if (condEdges == 0) {
                        errors.add("AGENT_SPLIT 节点必须至少一条带条件出边（layer 分流）: " + x.getKey());
                    }
                    if (elseEdges > 1) {
                        errors.add("AGENT_SPLIT 节点只允许至多一条无条件（无通道兜底）出边: " + x.getKey());
                    }
                }
                case AUDIENCE, DELAY, ACTION, UPDATE -> {
                    if (out > 1) {
                        errors.add(t + " 节点 " + x.getKey() + " 只允许至多一条出边");
                    }
                    if (condEdges > 0) {
                        errors.add(t + " 节点不允许带条件出边: " + x.getKey());
                    }
                }
                case END -> {
                    if (out > 0) {
                        errors.add("END 节点不允许有出边: " + x.getKey());
                    }
                }
            }
        }

        return new ValidationResult(errors);
    }

    private static boolean isEmptyObject(JsonNode n) {
        return n.isObject() && n.isEmpty();
    }

    private static NodeType typeOf(String type) {
        if (type == null) {
            return null;
        }
        try {
            return NodeType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Set<String> reachable(String start, Map<String, List<EdgeDef>> outEdges) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (EdgeDef e : outEdges.getOrDefault(cur, List.of())) {
                if (seen.add(e.targetKey())) {
                    queue.add(e.targetKey());
                }
            }
        }
        return seen;
    }

    private static String findCycle(Set<String> nodes, Map<String, List<EdgeDef>> outEdges) {
        Map<String, Integer> color = new HashMap<>(); // 0 白 / 1 灰 / 2 黑
        Map<String, String> parent = new HashMap<>();  // 入栈时记录的父节点（灰栈链）
        Deque<String> stack = new ArrayDeque<>();
        for (String start : nodes) {
            if (color.getOrDefault(start, 0) != 0) {
                continue;
            }
            stack.push(start);
            while (!stack.isEmpty()) {
                String cur = stack.peek();
                int c = color.getOrDefault(cur, 0);
                if (c == 0) {
                    color.put(cur, 1);
                }
                boolean advanced = false;
                for (EdgeDef e : outEdges.getOrDefault(cur, List.of())) {
                    if (e == null) {
                        continue;
                    }
                    int tc = color.getOrDefault(e.targetKey(), 0);
                    if (tc == 1) {
                        return buildCyclePath(parent, e.targetKey(), cur) + " → " + e.targetKey();
                    }
                    if (tc == 0) {
                        parent.put(e.targetKey(), cur);
                        stack.push(e.targetKey());
                        advanced = true;
                        break;
                    }
                }
                if (!advanced) {
                    color.put(cur, 2);
                    stack.pop();
                }
            }
        }
        return null;
    }

    /** 沿 parent 链从环边起点回溯到环边终点，还原环的完整节点序列。 */
    private static String buildCyclePath(Map<String, String> parent, String from, String to) {
        List<String> rev = new ArrayList<>();
        String cur = to;
        int guard = 0;
        while (!cur.equals(from) && guard++ < 1000) {
            rev.add(cur);
            String p = parent.get(cur);
            if (p == null) {
                break;
            }
            cur = p;
        }
        rev.add(from);
        java.util.Collections.reverse(rev);
        return String.join(" → ", rev);
    }
}