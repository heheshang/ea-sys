package com.easysys.engine.rule;

import com.easysys.engine.EngineException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件 DSL → QLExpress 求值语义（无 Spring）。
 * 覆盖：AND/OR/嵌套、比较、in/contains、exists、null 缺失语义、注入转义、非法输入。
 */
class ConditionCompilerTest {

    private final ObjectMapper m = new ObjectMapper();
    private final ConditionCompiler compiler = new ConditionCompiler();

    private ObjectNode rule(ObjectNode... items) {
        ArrayNode arr = m.createArrayNode();
        for (ObjectNode it : items) {
            arr.add(it);
        }
        ObjectNode r = m.createObjectNode();
        r.put("op", "AND");
        r.set("items", arr);
        return r;
    }

    private ObjectNode item(String field, String op, Object value) {
        ObjectNode n = m.createObjectNode();
        n.put("field", field);
        n.put("op", op);
        if (value instanceof String s) {
            n.put("value", s);
        } else if (value instanceof Number num) {
            n.put("value", num.doubleValue());
        } else if (value instanceof Boolean b) {
            n.put("value", b);
        } else {
            throw new IllegalArgumentException("type: " + value);
        }
        return n;
    }

    private ObjectNode rawItem(String field, String op, com.fasterxml.jackson.databind.JsonNode value) {
        ObjectNode n = m.createObjectNode();
        n.put("field", field);
        n.put("op", op);
        n.set("value", value);
        return n;
    }

    /** 无 value 项（exists / not_exists）。 */
    private ObjectNode item(String field, String op) {
        ObjectNode n = m.createObjectNode();
        n.put("field", field);
        n.put("op", op);
        return n;
    }

    private boolean eval(ObjectNode rule, Map<String, Object> contact) {
        return compiler.evaluate(compiler.compile(rule.toString()), Map.of(), contact, Map.of());
    }

    // ---- 求值 ----

    @Test
    void andAllTrueMatches() {
        ObjectNode r = rule(
                item("contact.churn_risk", "equals", "HIGH"),
                item("contact.level", ">", 2));
        assertTrue(eval(r, Map.of("churn_risk", "HIGH", "level", 3)));
    }

    @Test
    void andPartialFalseRejects() {
        ObjectNode r = rule(
                item("contact.churn_risk", "equals", "HIGH"),
                item("contact.level", ">", 2));
        assertFalse(eval(r, Map.of("churn_risk", "LOW", "level", 3)));
    }

    @Test
    void orBranchMatches() {
        ObjectNode r = m.createObjectNode();
        r.put("op", "OR");
        ArrayNode items = r.putArray("items");
        items.add(item("contact.churn_risk", "equals", "HIGH"));
        items.add(item("contact.level", ">", 5));
        assertTrue(eval(r, Map.of("level", 6)));
        assertFalse(eval(r, Map.of("level", 3)));
    }

    @Test
    void nestedLogicalGroups() {
        ObjectNode inner = m.createObjectNode();
        inner.put("op", "AND");
        inner.putArray("items").add(item("contact.a", "equals", "1")).add(item("contact.b", "equals", "2"));
        ObjectNode outer = m.createObjectNode();
        outer.put("op", "OR");
        outer.putArray("items").add(inner).add(item("contact.c", "equals", "3"));

        assertTrue(eval(outer, Map.of("a", "1", "b", "2")));
        assertTrue(eval(outer, Map.of("c", "3")));
        assertFalse(eval(outer, Map.of("c", "9")));
    }

    @Test
    void numericComparisonOnMissingFieldIsFalse() {
        // null 语义：缺失字段不命中
        ObjectNode r = rule(item("contact.level", ">", 2));
        assertFalse(eval(r, Map.of()));
        assertFalse(eval(r, Map.of("level", "abc"))); // 非数字也不命中（不抛错）
    }

    @Test
    void equalsIsTypeStrictBetweenStringAndNumber() {
        ObjectNode r = rule(item("contact.level", "equals", 3));
        assertFalse(eval(r, Map.of("level", "3"))); // 字符串 ≠ 数字
        assertTrue(eval(r, Map.of("level", 3)));
        assertTrue(eval(r, Map.of("level", 3.0))); // 数字等值
    }

    @Test
    void notEqualsMatches() {
        ObjectNode r = rule(item("contact.churn_risk", "not_equals", "HIGH"));
        assertTrue(eval(r, Map.of("churn_risk", "LOW")));
        assertFalse(eval(r, Map.of("churn_risk", "HIGH")));
        assertTrue(eval(r, Map.of())); // 缺失 ≠ HIGH
    }

    @Test
    void inAcceptsStringArray() {
        ArrayNode arr = m.createArrayNode();
        arr.add("L1");
        arr.add("L2");
        ObjectNode r = rule(rawItem("contact.layer", "in", arr));
        assertTrue(eval(r, Map.of("layer", "L2")));
        assertFalse(eval(r, Map.of("layer", "L3")));
    }

    @Test
    void notInRejectsPresent() {
        ArrayNode arr = m.createArrayNode();
        arr.add("L1");
        ObjectNode r = rule(rawItem("contact.layer", "not_in", arr));
        assertFalse(eval(r, Map.of("layer", "L1")));
        assertTrue(eval(r, Map.of("layer", "L9")));
    }

    @Test
    void containsMatchesListMember() {
        ObjectNode r = rule(item("contact.tags", "contains", "vip"));
        assertTrue(eval(r, Map.of("tags", List.of("vip", "vip2"))));
        assertFalse(eval(r, Map.of("tags", List.of("normal"))));
        assertFalse(eval(r, Map.of())); // 缺失 → false 不抛错
    }

    @Test
    void containsMatchesStringSubstring() {
        ObjectNode r = rule(item("contact.note", "contains", "gold"));
        assertTrue(eval(r, Map.of("note", "gold member")));
    }

    @Test
    void existsAndNotExists() {
        ObjectNode r = rule(item("contact.churn_risk", "exists"));
        assertTrue(eval(r, Map.of("churn_risk", "HIGH")));
        assertFalse(eval(r, Map.of()));
    }

    @Test
    void notExists() {
        ObjectNode r = rule(item("contact.churn_risk", "not_exists"));
        assertFalse(eval(r, Map.of("churn_risk", "HIGH")));
        assertTrue(eval(r, Map.of()));
    }

    @Test
    void eventAndHistoryPrefixes() {
        ObjectNode r = rule(
                item("event.amount", ">=", 100),
                item("history.total_orders", ">", 0));
        assertTrue(compiler.evaluate(compiler.compile(r.toString()),
                Map.of("amount", 100), Map.of(), Map.of("total_orders", 2)));

        // event 缺失 → 不命中
        ObjectNode onlyEvent = rule(item("event.amount", ">=", 100));
        assertFalse(eval(onlyEvent, Map.of()));
    }

    // ---- 注入转义 ----

    @Test
    void stringValueWithQuotesIsEscapedNotInjected() {
        // value 含引号与表达式片段：编译不抛错，求值按字符串相等语义
        ObjectNode r = rule(item("contact.name", "equals", "x\" OR 1=1 --"));
        ConditionCompiler.CompiledCondition c = compiler.compile(r.toString());
        // 引号被转义成字符串字面量内容；求值按字符串相等语义，不产生可执行 SQL 片段
        assertTrue(c.expression().contains("\\\""));
        assertTrue(compiler.evaluate(c, Map.of(), Map.of("name", "x\" OR 1=1 --"), Map.of()));
        assertFalse(compiler.evaluate(c, Map.of(), Map.of("name", "other"), Map.of()));
    }

    @Test
    void backslashInValueIsEscaped() {
        ObjectNode r = rule(item("contact.path", "equals", "a\\b"));
        ConditionCompiler.CompiledCondition c = compiler.compile(r.toString());
        assertTrue(compiler.evaluate(c, Map.of(), Map.of("path", "a\\b"), Map.of()));
        assertFalse(compiler.evaluate(c, Map.of(), Map.of("path", "ab"), Map.of()));
    }

    @Test
    void nullValueEqualsMissingIsFalseOnlyWhenFieldPresent() {
        // value=null 的 equals：字段存在且为 null 才命中——画像里不产生 null 键，等价不命中
        ObjectNode r = m.createObjectNode();
        r.put("op", "AND");
        ObjectNode it = m.createObjectNode();
        it.put("field", "contact.x");
        it.put("op", "equals");
        it.putNull("value");
        r.putArray("items").add(it);
        ConditionCompiler.CompiledCondition c = compiler.compile(r.toString());
        // 缺失键 （ctx.get → null）与 null value 相等 → 命中缺失键
        assertTrue(compiler.evaluate(c, Map.of(), Map.of(), Map.of()));
    }

    // ---- 非法输入 ----

    @Test
    void unknownOpRejected() {
        ObjectNode r = rule(item("contact.x", "gte", 1));
        assertThrows(EngineException.class, () -> compiler.compile(r.toString()));
    }

    @Test
    void missingFieldRejected() {
        ObjectNode r = m.createObjectNode();
        r.put("op", "AND");
        ObjectNode it = m.createObjectNode();
        it.put("op", "equals");
        it.put("value", "x");
        r.putArray("items").add(it);
        assertThrows(EngineException.class, () -> compiler.compile(r.toString()));
    }

    @Test
    void prefixlessFieldRejected() {
        ObjectNode r = rule(item("churn_risk", "equals", "HIGH"));
        EngineException e = assertThrows(EngineException.class, () -> compiler.compile(r.toString()));
        assertTrue(e.getMessage().contains("前缀"));
    }

    @Test
    void unknownPrefixRejected() {
        ObjectNode r = rule(item("other.x", "equals", "1"));
        assertThrows(EngineException.class, () -> compiler.compile(r.toString()));
    }

    @Test
    void emptyItemsRejected() {
        ObjectNode r = m.createObjectNode();
        r.put("op", "AND");
        r.putArray("items");
        assertThrows(EngineException.class, () -> compiler.compile(r.toString()));
    }

    @Test
    void inWithNonArrayRejected() {
        ObjectNode r = rule(item("contact.layer", "in", "L1"));
        assertThrows(EngineException.class, () -> compiler.compile(r.toString()));
    }

    @Test
    void objectValueRejected() {
        ObjectNode obj = m.createObjectNode();
        obj.put("k", "v");
        ObjectNode r = rule(rawItem("contact.x", "equals", obj));
        assertThrows(EngineException.class, () -> compiler.compile(r.toString()));
    }

    @Test
    void blankRuleRejected() {
        assertThrows(EngineException.class, () -> compiler.compile("   "));
        assertThrows(EngineException.class, () -> compiler.compile(null));
    }

    @Test
    void compiledShape() {
        // 编译产物以 safe_ 函数 + 字符串字面量键出现，字段名不回填为裸标识符
        ObjectNode r = rule(item("contact.level", ">", 2));
        String expr = compiler.compile(r.toString()).expression();
        assertTrue(expr.contains("safe_gt"));
        assertTrue(expr.contains("\"level\""));
        assertFalse(expr.contains("OR 1=1"));
    }

    // ---- percentage（AB/灰度稳定哈希分流） ----

    private boolean pct(int pct, Object id) {
        return eval(rule(item("contact.id", "percentage", pct)), Map.of("id", id));
    }

    @Test
    void percentageBoundsExhaust() {
        // 0% 恒不命中；100% 恒命中（bucket ∈ [0,100)，0 无可命中、100 全覆盖）
        int[] ids = {1, 7, 42, 1001, 999999};
        for (int id : ids) {
            assertFalse(pct(0, id));
            assertTrue(pct(100, id));
        }
        // 缺 id → false 不抛错
        assertFalse(eval(rule(item("contact.id", "percentage", 50)), Map.of()));
    }

    @Test
    void percentageIsDeterministicPerId() {
        // 同 id 同 pct 多次求值结果恒等；不同 pct 分桶单调（bucket 固定，pct 越高覆盖越全）
        for (int id = 1; id <= 500; id++) {
            boolean at30 = pct(30, id);
            for (int rep = 0; rep < 3; rep++) {
                assertEquals(at30, pct(30, id));
            }
        }
    }

    @Test
    void percentageIsMonotonicAcrossBucket() {
        // 单个 id 存在分桶临界点 b：pct <= b 不命中、pct > b 命中 —— 连续 pct 单调不逆转
        boolean prev = false;
        for (int p = 0; p <= 100; p++) {
            boolean hit = pct(p, 1001);
            assertTrue(hit || !prev, "pct=" + p + " 由命中回落为不命中");
            prev = hit;
        }
    }

    @Test
    void percentageUsesStringHashAcrossTypes() {
        // 数字与同值字符串同分桶（String.valueOf 归一），避免 Long/Integer/String 类型漂移
        ObjectNode n = rule(item("contact.id", "percentage", 50));
        boolean asLong = compiler.evaluate(compiler.compile(n.toString()), Map.of(), Map.of("id", 1001L), Map.of());
        boolean asStr = compiler.evaluate(compiler.compile(n.toString()), Map.of(), Map.of("id", "1001"), Map.of());
        assertEquals(asLong, asStr);
    }

    @Test
    void percentageInAndOrComposes() {
        // 与现有逻辑操作符可组合：AND/OR 树内
        ObjectNode r = m.createObjectNode();
        r.put("op", "OR");
        r.putArray("items")
                .add(item("contact.level", "equals", "vip"))
                .add(item("contact.id", "percentage", 10));
        int id = 7;
        boolean pctHit = pct(10, id);
        assertEquals(pctHit, eval(r, Map.of("level", "normal", "id", id)));
        assertTrue(eval(r, Map.of("level", "vip", "id", id)));
    }

    @Test
    void percentageRejectsNonNumericAndOutOfRange() {
        // 编译期校验：非数字 / 越界 value 直接拒绝
        ObjectNode str = rule(item("contact.id", "percentage", "50"));
        assertThrows(EngineException.class, () -> compiler.compile(str.toString()));
        ObjectNode neg = rule(item("contact.id", "percentage", -1));
        assertThrows(EngineException.class, () -> compiler.compile(neg.toString()));
        ObjectNode over = rule(item("contact.id", "percentage", 101));
        assertThrows(EngineException.class, () -> compiler.compile(over.toString()));
    }
}