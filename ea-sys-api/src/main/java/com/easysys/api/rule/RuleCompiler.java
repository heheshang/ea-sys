package com.easysys.api.rule;

import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 圈选规则 DSL 编译器：结构化 JSON → 参数化 SQL WHERE 片段。
 * 字段与操作符全部走白名单常量，值一律映射为 ? 占位参数，杜绝注入。
 * <p>
 * 字段空间（对齐 docs/03-workflow-engine.md §3）：
 * <ul>
 *   <li>{@code contact.<列>}：contact 直属列（id/external_id/phone/email/push_token/status）</li>
 *   <li>{@code contact.<key>}：非列名即 contact_attribute 属性（含智能体分层标签 layer/churn_risk）</li>
 *   <li>{@code tag.<标签>}：contact_tag</li>
 *   <li>{@code event.*}：行为事件条件依赖事件数据链路，M3 后支持（校验期拒绝）</li>
 * </ul>
 * 语义约定：属性 equals = 属性存在且值相等；not_equals = 属性存在且值不等；
 * 标签 equals/not_equals = 有无该标签；空规则（空 items）禁止。
 */
public final class RuleCompiler {

    private static final Set<String> CONTACT_COLUMNS =
            Set.of("id", "external_id", "phone", "email", "push_token", "status");
    private static final Set<String> OPS = Set.of(
            "equals", "not_equals", "in", "not_in", "contains", "gt", "gte", "lt", "lte", "exists", "not_exists");
    private static final Set<String> NUMERIC_OPS = Set.of("gt", "gte", "lt", "lte");
    private static final Set<String> EXISTENCE_OPS = Set.of("exists", "not_exists");

    private static final String ATTR_TABLE = "contact_attribute";
    private static final String TAG_TABLE = "contact_tag";

    /** 编译结果：参数化 SQL 片段 + 顺序绑定的参数。 */
    public record SqlSegment(String sql, List<Object> params) {
    }

    private RuleCompiler() {
    }

    /** 编译整条规则；任何非法结构抛 BizException(40000)。tenantId 非空时属性/标签 EXISTS 子查询追加租户过滤。 */
    public static SqlSegment compile(JsonNode rule) {
        return compile(rule, null);
    }

    public static SqlSegment compile(JsonNode rule, Long tenantId) {
        if (rule == null || !rule.isObject()) {
            fail("规则必须为 JSON 对象");
        }
        String op = text(rule, "op");
        if (!"AND".equals(op) && !"OR".equals(op)) {
            fail("op 仅支持 AND / OR");
        }
        JsonNode items = rule.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            fail("items 不能为空：空规则语义未定义，M1 禁止空圈选");
        }
        List<String> sqls = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (JsonNode item : items) {
            SqlSegment seg = compileItem(item, tenantId);
            sqls.add(seg.sql());
            params.addAll(seg.params());
        }
        return new SqlSegment("(" + String.join(" " + op + " ", sqls) + ")", params);
    }

    private static SqlSegment compileItem(JsonNode item, Long tenantId) {
        if (item == null || !item.isObject()) {
            fail("规则项必须为 JSON 对象");
        }
        String op = text(item, "op");
        if ("AND".equals(op) || "OR".equals(op)) {
            return compile(item, tenantId); // 嵌套子规则（AND/OR 递归展开）
        }
        String field = text(item, "field");
        if (field == null || field.isBlank()) {
            fail("规则项缺少 field");
        }
        if (op == null || !OPS.contains(op)) {
            fail("未知操作符: " + op);
        }
        if (field.startsWith("event.")) {
            fail("行为事件条件（event.*）依赖事件数据链路，M3 后支持");
        }
        if (field.startsWith("contact.")) {
            String key = field.substring("contact.".length());
            if (key.isBlank() || !CONTACT_COLUMNS.contains(key)) {
                fail("未知字段: " + field + "（contact.* 仅支持直属列；属性请用 attribute.*）");
            }
            return columnCond(key, op, item.get("value"));
        }
        if (field.startsWith("attribute.")) {
            String key = field.substring("attribute.".length());
            if (key.isBlank()) {
                fail("field 不合法: " + field);
            }
            return attributeCond(key, op, item.get("value"), tenantId);
        }
        if (field.startsWith("tag.")) {
            String tag = field.substring("tag.".length());
            if (tag.isBlank()) {
                fail("field 不合法: " + field);
            }
            return tagCond(tag, op, item.get("value"), tenantId);
        }
        fail("未知字段: " + field + "（支持 contact.* / tag.*；event.* 暂不支持）");
        return null; // unreachable
    }

    /** contact 直属列条件（文本列，仅字符串语义操作符）。 */
    private static SqlSegment columnCond(String col, String op, JsonNode value) {
        if (NUMERIC_OPS.contains(op)) {
            fail("数值比较 op(" + op + ") 仅支持属性字段，contact 直属列均为文本");
        }
        if (EXISTENCE_OPS.contains(op)) {
            fail("op(" + op + ") 仅支持属性/标签字段");
        }
        List<Object> params = new ArrayList<>();
        String cmp;
        switch (op) {
            case "equals", "not_equals" -> {
                Object v = requireScalar(value, op);
                cmp = col + (op.equals("equals") ? " = ?" : " <> ?");
                params.add(v);
            }
            case "in", "not_in" -> {
                List<Object> vs = toScalarList(value, op);
                cmp = col + (op.equals("in") ? " IN (" : " NOT IN (") + placeholders(vs.size()) + ")";
                params.addAll(vs);
            }
            case "contains" -> {
                Object v = requireScalar(value, op);
                cmp = col + " LIKE ?";
                params.add("%" + v + "%");
            }
            default -> throw new IllegalStateException("unreachable: " + op);
        }
        return new SqlSegment(cmp, params);
    }

    /** contact_attribute 属性条件：EXISTS 子查询 + jsonb 标量文本化比较；tenantId 非空时子查询锁当前租户。 */
    private static SqlSegment attributeCond(String key, String op, JsonNode value, Long tenantId) {
        List<Object> params = new ArrayList<>();
        params.add(key);
        String keyCmp = "ca.key = ?";
        if ("exists".equals(op) || "not_exists".equals(op)) {
            return existSubquery(ATTR_TABLE, "ca", keyCmp, op.equals("not_exists"), tenantId, params);
        }
        String cmp;
        if (NUMERIC_OPS.contains(op)) {
            params.add(requireNumeric(value, op));
            cmp = "(ca.value #>> '{}')::numeric " + numericCmp(op) + " ?";
        } else {
            switch (op) {
                case "equals", "not_equals" -> {
                    Object v = requireScalar(value, op);
                    params.add(v);
                    cmp = "ca.value #>> '{}' " + (op.equals("equals") ? "= ?" : "<> ?");
                }
                case "in", "not_in" -> {
                    List<Object> vs = toScalarList(value, op);
                    cmp = "ca.value #>> '{}' " + (op.equals("in") ? "IN (" : "NOT IN (") + placeholders(vs.size()) + ")";
                    params.addAll(vs);
                }
                case "contains" -> {
                    Object v = requireScalar(value, op);
                    params.add("%" + v + "%");
                    cmp = "ca.value #>> '{}' LIKE ?";
                }
                default -> throw new IllegalStateException("unreachable: " + op);
            }
        }
        return existSubquery(ATTR_TABLE, "ca", keyCmp + " AND " + cmp, false, tenantId, params);
    }

    /** contact_tag 标签条件：exists = 有该标签；in = 含任一标签；not_* = 均不含。tenantId 非空时子查询锁当前租户。 */
    private static SqlSegment tagCond(String tag, String op, JsonNode value, Long tenantId) {
        List<Object> params = new ArrayList<>();
        boolean negate = op.startsWith("not_");
        switch (op) {
            case "equals", "exists", "not_equals", "not_exists" -> {
                params.add(tag);
                return existSubquery(TAG_TABLE, "ct", "ct.tag = ?", negate, tenantId, params);
            }
            case "in", "not_in" -> {
                List<Object> tags = toScalarList(value, op);
                params.addAll(tags);
                return existSubquery(TAG_TABLE, "ct", "ct.tag IN (" + placeholders(tags.size()) + ")", negate, tenantId, params);
            }
            default -> {
                fail("op(" + op + ") 不支持标签字段（标签仅判断存在性）");
                return null; // unreachable
            }
        }
    }

    private static SqlSegment existSubquery(String table, String alias, String innerCmp, boolean negate, Long tenantId, List<Object> params) {
        String tenantCmp = tenantId == null ? "" : " AND " + alias + ".tenant_id = ?";
        String expr = "EXISTS (SELECT 1 FROM " + table + " " + alias + " WHERE " + alias + ".contact_id = contact.id"
                + (innerCmp == null ? "" : " AND " + innerCmp) + tenantCmp + ")";
        if (tenantId != null) {
            params.add(tenantId);
        }
        return new SqlSegment((negate ? "NOT " : "") + expr, params);
    }

    private static String numericCmp(String op) {
        return switch (op) {
            case "gt" -> ">";
            case "gte" -> ">=";
            case "lt" -> "<";
            case "lte" -> "<=";
            default -> throw new IllegalStateException("unreachable: " + op);
        };
    }

    private static String placeholders(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("占位符数量必须为正: " + n);
        }
        return "?,".repeat(n).substring(0, n * 2 - 1);
    }

    /** 标量文本化：列/属性文本比较（jsonb #>> '{}' 为 text）必须传 String 参数，避免 PG 类型歧义。 */
    private static String requireScalar(JsonNode value, String op) {
        if (value == null || value.isContainerNode() || !value.isValueNode()) {
            fail("op(" + op + ") 的 value 必须为标量（字符串/数字/布尔）");
        }
        return toScalarText(value);
    }

    /** 数值比较参数：返回 Number（jsonb 提取后 ::numeric 转换比较）。 */
    private static Number requireNumeric(JsonNode value, String op) {
        if (value == null || !(value.isNumber() || isNumericTextNode(value))) {
            fail("op(" + op + ") 的 value 必须为数值");
        }
        return value.numberValue();
    }

    private static boolean isNumericTextNode(JsonNode n) {
        return n.isTextual() && isNumericText(n.textValue());
    }

    private static List<Object> toScalarList(JsonNode value, String op) {
        if (value == null || !value.isArray() || value.isEmpty()) {
            fail("op(" + op + ") 的 value 必须为非空数组");
        }
        List<Object> out = new ArrayList<>();
        for (JsonNode n : value) {
            if (n.isContainerNode() || !n.isValueNode()) {
                fail("op(" + op + ") 的数组元素必须为标量");
            }
            out.add(toScalarText(n));
        }
        return out;
    }

    private static String toScalarText(JsonNode n) {
        if (n.isTextual()) return n.textValue();
        if (n.isNumber()) return n.asText();
        if (n.isBoolean()) return n.asText();
        fail("仅支持字符串/数字/布尔标量值");
        return null; // unreachable
    }

    private static boolean isNumericText(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || !n.isTextual() ? null : n.textValue();
    }

    private static void fail(String message) {
        throw new BizException(ErrorCode.BAD_REQUEST, message);
    }
}