package com.easysys.engine.dag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAG 结构校验（无 Spring）：单触发器 / 引用完整 / 环 / 可达性 / 端口与条件边规则。
 */
class DagValidatorTest {

    private final ObjectMapper m = new ObjectMapper();
    private final DagValidator validator = new DagValidator();

    private DagValidator.NodeDef node(String key, String type) {
        return new DagValidator.NodeDef(key, type, m.createObjectNode());
    }

    private DagValidator.EdgeDef edge(String from, String to) {
        return new DagValidator.EdgeDef(from, to, null);
    }

    private DagValidator.EdgeDef condEdge(String from, String to) {
        JsonNode cond = m.createObjectNode()
                .put("op", "AND")
                .putArray("items").addObject()
                .put("field", "contact.x").put("op", "equals").put("value", "1");
        return new DagValidator.EdgeDef(from, to, cond);
    }

    // ---- 合法流 ----

    @Test
    void validLinearChainPasses() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("action", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(edge("trigger", "action"), edge("action", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.valid(), () -> "errors: " + r.errors());
    }

    @Test
    void validConditionalBranchWithFallbackPasses() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("cond", "CONDITION"),
                node("a1", "ACTION"),
                node("a2", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "cond"),
                condEdge("cond", "a1"),
                edge("cond", "a2"), // 无条件兜底
                edge("a1", "end"),
                edge("a2", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.valid(), () -> "errors: " + r.errors());
    }

    @Test
    void updateAndDelayNodesPass() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("delay", "DELAY"),
                node("update", "UPDATE"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "delay"), edge("delay", "update"), edge("update", "end"));
        assertTrue(validator.validate(nodes, edges).valid());
    }

    // ---- 环 ----

    @Test
    void cycleDetected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("b", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "a"), edge("a", "b"), edge("b", "a"), edge("b", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("环")),
                () -> "errors: " + r.errors());
    }

    @Test
    void selfLoopDetected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "a"), edge("a", "a"), edge("a", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("自环")),
                () -> "errors: " + r.errors());
    }

    @Test
    void cyclePathReportsFullNodeSequence() {
        // 三节点环：完整路径还原为 a → b → c → a，而非仅首尾两节点
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("b", "ACTION"),
                node("c", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "a"),
                edge("a", "b"), edge("b", "c"), edge("c", "a"),
                edge("c", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        String cycle = r.errors().stream().filter(e -> e.contains("环")).findFirst().orElse("");
        assertTrue(cycle.contains("a") && cycle.contains("b") && cycle.contains("c"),
                () -> "errors: " + r.errors());
        assertEquals(4, cycle.split("→").length); // a → b → c → a 四节点三箭头（含闭合）
    }

    @Test
    void parallelPathsWithoutCyclePass() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("b", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "a"), edge("trigger", "b"), edge("a", "end"), edge("b", "end"));
        assertTrue(validator.validate(nodes, edges).valid());
    }

    // ---- 触发器 ----

    @Test
    void zeroTriggerRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("a", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(edge("a", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("TRIGGER") && e.contains("1")),
                () -> "errors: " + r.errors());
    }

    @Test
    void twoTriggersRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("t1", "TRIGGER"),
                node("t2", "TRIGGER"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(edge("t1", "end"), edge("t2", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("1 个 TRIGGER")),
                () -> "errors: " + r.errors());
    }

    @Test
    void triggerWithIncomingEdgeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "a"), edge("a", "trigger"), edge("trigger", "end"), edge("a", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("入边")),
                () -> "errors: " + r.errors());
    }

    @Test
    void triggerWithoutOutEdgeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(edge("end", "trigger"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("出边")),
                () -> "errors: " + r.errors());
    }

    // ---- 引用与孤立 ----

    @Test
    void edgeToUnknownNodeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(edge("trigger", "ghost"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("ghost")),
                () -> "errors: " + r.errors());
    }

    @Test
    void duplicateNodeKeyRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("a", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(edge("trigger", "a"), edge("a", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("重复")),
                () -> "errors: " + r.errors());
    }

    @Test
    void orphanNodeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("orphan", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(edge("trigger", "a"), edge("a", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("孤立") || e.contains("不可达")),
                () -> "errors: " + r.errors());
    }

    @Test
    void nodeNotReachingEndRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("dead", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(edge("trigger", "dead"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("END")),
                () -> "errors: " + r.errors());
    }

    @Test
    void missingEndRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"));
        List<DagValidator.EdgeDef> edges = List.of(edge("trigger", "a"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("END")),
                () -> "errors: " + r.errors());
    }

    // ---- 端口与条件边 ----

    @Test
    void conditionWithoutCondEdgeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("cond", "CONDITION"),
                node("a", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "cond"), edge("cond", "a"), edge("a", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("带条件出边")),
                () -> "errors: " + r.errors());
    }

    @Test
    void conditionWithTwoFallbacksRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("cond", "CONDITION"),
                node("a1", "ACTION"),
                node("a2", "ACTION"),
                node("a3", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "cond"),
                condEdge("cond", "a1"),
                edge("cond", "a2"), // 兜底 1
                edge("cond", "a3"), // 兜底 2 → 报错
                edge("a1", "end"), edge("a2", "end"), edge("a3", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("兜底")),
                () -> "errors: " + r.errors());
    }

    @Test
    void actionWithTwoOutEdgesRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("b", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "a"), edge("a", "b"), edge("a", "end"), edge("b", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("一条出边")),
                () -> "errors: " + r.errors());
    }

    @Test
    void actionWithCondEdgeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "a"), condEdge("a", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("带条件出边")),
                () -> "errors: " + r.errors());
    }

    @Test
    void endWithOutEdgeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("end", "END"),
                node("a", "ACTION"));
        List<DagValidator.EdgeDef> edges = List.of(edge("trigger", "end"), edge("end", "a"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("END") && e.contains("出边")),
                () -> "errors: " + r.errors());
    }

    @Test
    void agentSplitWithLayerEdgesPasses() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("split", "AGENT_SPLIT"),
                node("a1", "ACTION"),
                node("a2", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "split"),
                condEdge("split", "a1"),
                condEdge("split", "a2"),
                edge("split", "end"), // 无通道兜底
                edge("a1", "end"),
                edge("a2", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.valid(), () -> "errors: " + r.errors());
    }

    @Test
    void agentSplitWithoutCondEdgeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("split", "AGENT_SPLIT"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(edge("trigger", "split"), edge("split", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("带条件出边")),
                () -> "errors: " + r.errors());
    }

    @Test
    void agentSplitMultipleElseRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("split", "AGENT_SPLIT"),
                node("a1", "ACTION"),
                node("a2", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "split"),
                condEdge("split", "a1"),
                edge("split", "a2"),
                edge("split", "end"), // 第二条兜底 → 违规
                edge("a1", "end"),
                edge("a2", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("至多一条无条件")),
                () -> "errors: " + r.errors());
    }

    @Test
    void unknownTypeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "SOMETHING"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "a"), edge("a", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("非法")),
                () -> "errors: " + r.errors());
    }

    @Test
    void emptyCanvasRejected() {
        DagValidator.ValidationResult r = validator.validate(List.of(), List.of());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("至少需要")),
                () -> "errors: " + r.errors());
    }

    @Test
    void duplicateEdgeRejected() {
        List<DagValidator.NodeDef> nodes = List.of(
                node("trigger", "TRIGGER"),
                node("a", "ACTION"),
                node("end", "END"));
        List<DagValidator.EdgeDef> edges = List.of(
                edge("trigger", "a"), edge("a", "end"), edge("a", "end"));
        DagValidator.ValidationResult r = validator.validate(nodes, edges);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("重复边")),
                () -> "errors: " + r.errors());
    }
}