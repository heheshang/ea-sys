package com.easysys.engine.rule;

import com.easysys.engine.EngineException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;
import com.ql.util.express.Operator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 条件规则 DSL → QLExpress 可执行谓词。
 *
 * DSL（结构化 JSON，与 M1 圈选同构，字段升级为 event./contact./history. 前缀）：
 * <pre>
 * {"op": "AND", "items": [
 *   {"field": "event.amount", "op": "gt", "value": 100},
 *   {"field": "contact.layer", "op": "in", "value": ["L1", "L2"]},
 *   {"field": "contact.tags", "op": "contains", "value": "vip"},
 *   {"field": "contact.churn_risk", "op": "exists"}
 * ]}
 * </pre>
 *
 * 编译产物：QLExpress 表达式，原子条件编译为 safe_* 函数调用（如
 * {@code safe_gt(contact, "level", 100)}、{@code safe_in(event, "layer", ["L1","L2"])}）。
 * - 字段 key 是字符串字面量（编译器转义），value 是数字/字符串/布尔字面量 → 无表达式注入面
 * - null / 数值类型归一语义全部在 Java 侧 Safe 操作符内决定；QLExpress 只做解析与 && / || 组合
 */
@Component
public class ConditionCompiler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PREFIXES = Set.of("event", "contact", "history");
    private static final Set<String> LOGICAL_OPS = Set.of("AND", "OR");
    /** 对照 docs/03-workflow-engine.md §3：比较用符号（> >= < <=），其余语义名 */
    private static final Set<String> OPS = Set.of(
            ">", ">=", "<", "<=",
            "equals", "not_equals", "in", "not_in", "contains",
            "exists", "not_exists",
            "percentage");
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

    private final ExpressRunner runner;

    public ConditionCompiler() {
        runner = new ExpressRunner();
        runner.addFunction("safe_eq", new CmpOperator() {
            @Override
            protected boolean compare(Object a, Object b) {
                return valueEq(a, b);
            }
        });
        runner.addFunction("safe_in", new CmpOperator() {
            @Override
            protected boolean compare(Object a, Object b) {
                // a = ctx 值，b = 数组字面量（容器）
                return listContains(b, a);
            }
        });
        runner.addFunction("safe_contains", new CmpOperator() {
            @Override
            protected boolean compare(Object a, Object b) {
                return containsValue(a, b);
            }
        });
        runner.addFunction("safe_gt", new BigDecimalOp() {
            @Override
            protected boolean compare(int c) {
                return c > 0;
            }
        });
        runner.addFunction("safe_gte", new BigDecimalOp() {
            @Override
            protected boolean compare(int c) {
                return c >= 0;
            }
        });
        runner.addFunction("safe_lt", new BigDecimalOp() {
            @Override
            protected boolean compare(int c) {
                return c < 0;
            }
        });
        runner.addFunction("safe_lte", new BigDecimalOp() {
            @Override
            protected boolean compare(int c) {
                return c <= 0;
            }
        });
        runner.addFunction("safe_exists", new Operator() {
            @Override
            public Object executeInner(Object[] list) {
                Map<?, ?> ctx = asMap(list, 0);
                if (ctx == null || list.length < 2 || list[1] == null) {
                    return Boolean.FALSE;
                }
                return ctx.containsKey(list[1].toString()) && ctx.get(list[1].toString()) != null;
            }
        });
        runner.addFunction("safe_pct", new Operator() {
            @Override
            public Object executeInner(Object[] list) {
                // list = [ctx, key, pct]；ctx.key 的稳定哈希落入 [0,100)，命中 pct 以内 ⇒ true
                Map<?, ?> ctx = asMap(list, 0);
                if (ctx == null || list.length < 3 || list[1] == null) {
                    return Boolean.FALSE;
                }
                Object id = ctx.get(list[1].toString());
                if (id == null) {
                    return Boolean.FALSE;
                }
                BigDecimal pct = toNumber(list[2]);
                if (pct == null || pct.compareTo(BigDecimal.ZERO) < 0 || pct.compareTo(new BigDecimal("100")) > 0) {
                    return Boolean.FALSE;
                }
                // String.hashCode 是语言规范定义的稳定哈希（跨 JVM 一致），负数经 floorMod 归一
                int bucket = Math.floorMod(String.valueOf(id).hashCode(), 100);
                return bucket < pct.intValue();
            }
        });
    }

    /** 编译规则 JSON；合法但求值逻辑永远为 null 结果（无内容）时返回 null 由上层处理。 */
    public CompiledCondition compile(String ruleJson) {
        try {
            JsonNode node = ruleJson == null || ruleJson.isBlank() ? null : MAPPER.readTree(ruleJson);
            if (node == null) {
                throw new EngineException("条件不能为空");
            }
            String expr = compileLogical(node);
            // 试执行一次（空上下文）验证语法，编译期失败前置到保存环节
            DefaultContext probe = new DefaultContext();
            probe.put("event", Map.of());
            probe.put("contact", Map.of());
            probe.put("history", Map.of());
            List<String> errors = new ArrayList<>();
            runner.execute(expr, probe, errors, true, false);
            if (!errors.isEmpty()) {
                throw new EngineException("条件表达式求值失败: " + errors.get(0));
            }
            return new CompiledCondition(expr);
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("条件编译失败: " + e.getMessage(), e);
        }
    }

    /** 对执行上下文求值（event / contact / history 均可为 null，内部归一为空 map）。 */
    public boolean evaluate(CompiledCondition condition,
                            Map<String, Object> event,
                            Map<String, Object> contact,
                            Map<String, Object> history) {
        DefaultContext ctx = new DefaultContext();
        ctx.put("event", event == null ? Map.of() : event);
        ctx.put("contact", contact == null ? Map.of() : contact);
        ctx.put("history", history == null ? Map.of() : history);
        List<String> errors = new ArrayList<>();
        Object result;
        try {
            result = runner.execute(condition.expression(), ctx, errors, true, false);
        } catch (Exception e) {
            throw new EngineException("条件求值异常: " + e.getMessage(), e);
        }
        if (!errors.isEmpty()) {
            throw new EngineException("条件求值失败: " + errors.get(0));
        }
        return Boolean.TRUE.equals(result);
    }

    public record CompiledCondition(String expression) {
    }

    // ---- 编译 ----

    private String compileLogical(JsonNode node) throws EngineException {
        String op = text(node, "op");
        if (!LOGICAL_OPS.contains(op)) {
            throw new EngineException("未知逻辑操作符: " + op);
        }
        JsonNode items = node.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new EngineException("items 不能为空");
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode item : items) {
            parts.add(compileItem(item));
        }
        // QLExpress 关键字为 && / ||，不支持大写 AND/OR
        return "(" + String.join(op.equals("AND") ? " && " : " || ", parts) + ")";
    }

    private String compileItem(JsonNode item) throws EngineException {
        if (item == null || !item.isObject()) {
            throw new EngineException("规则项必须是对象");
        }
        String op = text(item, "op");
        // 嵌套子规则：item 级 AND/OR
        if (LOGICAL_OPS.contains(op)) {
            return compileLogical(item);
        }
        String field = text(item, "field");
        if (field == null || field.isEmpty()) {
            throw new EngineException("规则项缺少 field");
        }
        if (op == null || !OPS.contains(op)) {
            throw new EngineException("未知操作符: " + op);
        }
        int dot = field.indexOf('.');
        if (dot <= 0 || dot == field.length() - 1) {
            throw new EngineException("字段必须带前缀 event./contact./history.: " + field);
        }
        String prefix = field.substring(0, dot);
        String key = field.substring(dot + 1);
        if (!PREFIXES.contains(prefix)) {
            throw new EngineException("未知字段前缀: " + prefix + "（支持 event./contact./history.）");
        }
        if (!KEY_PATTERN.matcher(key).matches() || key.length() > 128) {
            throw new EngineException("非法字段 key: " + key);
        }
        JsonNode value = item.get("value");
        if ("percentage".equals(op)) {
            if (value == null || !value.isNumber()) {
                throw new EngineException("percentage 的 value 必须是 0-100 的数字");
            }
            BigDecimal pct = value.decimalValue();
            if (pct.compareTo(BigDecimal.ZERO) < 0 || pct.compareTo(new BigDecimal("100")) > 0) {
                throw new EngineException("percentage 的 value 必须在 0-100 区间");
            }
        }
        String ctxVar = prefix;
        return switch (op) {
            case "equals" -> "safe_eq(" + ctxVar + ", " + str(key) + ", " + literal(value, false) + ")";
            case "not_equals" -> "!safe_eq(" + ctxVar + ", " + str(key) + ", " + literal(value, false) + ")";
            case "in" -> "safe_in(" + ctxVar + ", " + str(key) + ", " + literal(value, true) + ")";
            case "not_in" -> "!safe_in(" + ctxVar + ", " + str(key) + ", " + literal(value, true) + ")";
            case "contains" -> "safe_contains(" + ctxVar + ", " + str(key) + ", " + literal(value, false) + ")";
            case ">" -> "safe_gt(" + ctxVar + ", " + str(key) + ", " + literal(value, false) + ")";
            case ">=" -> "safe_gte(" + ctxVar + ", " + str(key) + ", " + literal(value, false) + ")";
            case "<" -> "safe_lt(" + ctxVar + ", " + str(key) + ", " + literal(value, false) + ")";
            case "<=" -> "safe_lte(" + ctxVar + ", " + str(key) + ", " + literal(value, false) + ")";
            case "exists" -> "safe_exists(" + ctxVar + ", " + str(key) + ")";
            case "not_exists" -> "!safe_exists(" + ctxVar + ", " + str(key) + ")";
            case "percentage" -> "safe_pct(" + ctxVar + ", " + str(key) + ", " + literal(value, false) + ")";
            default -> throw new EngineException("未知操作符: " + op);
        };
    }

    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String literal(JsonNode value, boolean asArray) throws EngineException {
        if (value == null) {
            return "null";
        }
        if (asArray) {
            if (!value.isArray()) {
                throw new EngineException("in/not_in 的 value 必须是数组");
            }
            List<String> elems = new ArrayList<>();
            for (JsonNode e : value) {
                elems.add(scalar(e));
            }
            return "[" + String.join(", ", elems) + "]";
        }
        return scalar(value);
    }

    private String scalar(JsonNode v) throws EngineException {
        if (v == null || v.isNull()) {
            return "null";
        }
        if (v.isNumber()) {
            return v.decimalValue().toPlainString();
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? "true" : "false";
        }
        if (v.isTextual()) {
            return str(v.asText());
        }
        throw new EngineException("条件 value 仅支持数字/字符串/布尔/数组: " + v);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || !v.isTextual() ? null : v.asText();
    }

    // ---- Safe 操作符（null / 类型语义收敛点） ----

    private abstract static class CmpOperator extends Operator {
        @Override
        public Object executeInner(Object[] list) {
            Map<?, ?> ctx = asMap(list, 0);
            Object a = ctx == null ? null : ctx.get(list.length < 2 || list[1] == null ? null : list[1].toString());
            Object b = list.length < 3 ? null : list[2];
            return compare(a, b);
        }

        protected abstract boolean compare(Object a, Object b);
    }

    private abstract static class BigDecimalOp extends Operator {
        @Override
        public Object executeInner(Object[] list) {
            Map<?, ?> ctx = asMap(list, 0);
            Object a = ctx == null ? null : ctx.get(list.length < 2 || list[1] == null ? null : list[1].toString());
            Object b = list.length < 3 ? null : list[2];
            if (a == null || b == null) {
                return Boolean.FALSE;
            }
            BigDecimal x = toNumber(a);
            BigDecimal y = toNumber(b);
            if (x == null || y == null) {
                return Boolean.FALSE;
            }
            return compare(x.compareTo(y));
        }

        protected abstract boolean compare(int c);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asMap(Object[] list, int i) {
        if (list == null || list.length <= i || !(list[i] instanceof Map)) {
            return null;
        }
        return (Map<?, ?>) list[i];
    }

    private static boolean valueEq(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            BigDecimal x = toNumber(na);
            BigDecimal y = toNumber(nb);
            return x != null && x.compareTo(y) == 0;
        }
        return a.equals(b);
    }

    private static boolean listContains(Object a, Object target) {
        if (a == null || target == null) {
            return false;
        }
        if (a instanceof List<?> list) {
            for (Object e : list) {
                if (valueEq(e, target)) {
                    return true;
                }
            }
            return false;
        }
        // QLExpress 的 [a, b] 字面量产物是 Object[]
        if (a instanceof Object[] arr) {
            for (Object e : arr) {
                if (valueEq(e, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsValue(Object a, Object sub) {
        if (a == null || sub == null) {
            return false;
        }
        if (a instanceof List<?> list) {
            return listContains(a, sub);
        }
        if (a instanceof String s) {
            return s.contains(String.valueOf(sub));
        }
        return false;
    }

    private static BigDecimal toNumber(Object o) {
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return null;
    }
}