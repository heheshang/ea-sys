package com.easysys.api.service.plan;

import com.easysys.api.dto.plan.PlanDocument;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 计划文件解析器：xlsx（模板 4 Sheet）与 csv（#节标记 + 表头 + 数据行同构）统一产出 {@link PlanDocument}。
 * 解析错误一律携带行列号，抛 {@link BizException}(40000)，格式 "[第X行第Y列] 原因"。
 * <p>模板 v1 固定结构：
 * <ul>
 *   <li>计划概览：计划名称 / 目标人群 / 触发方式(TIMED|EVENT|MANUAL) / 触发时间 / 事件名 / 时区 / 预算上限</li>
 *   <li>触达计划：人群分层 / 通道 / 顺序 / 时刻/延迟 / 消息模板 / 单用户频率上限 / 备注</li>
 *   <li>人群规则（可选）：操作 / 字段 / 操作符 / 值</li>
 *   <li>文案要求（可选）：通道 / 模板 / 要求</li>
 * </ul>
 */
@Component
public class PlanFileParser {

    private static final String SHEET_OVERVIEW = "计划概览";
    private static final String SHEET_ROUTES = "触达计划";
    private static final String SHEET_AUDIENCE = "人群规则";
    private static final String SHEET_COPY = "文案要求";

    private static final DataFormatter FORMATTER = new DataFormatter();

    // 计划概览列（顺序固定）
    private static final String[] OVERVIEW_COLS = {"计划名称", "目标人群", "触发方式", "触发时间", "事件名", "时区", "预算上限"};
    // 触达计划列（顺序固定）
    private static final String[] ROUTE_COLS = {"人群分层", "通道", "顺序", "时刻/延迟", "消息模板", "单用户频率上限", "备注"};
    // 人群规则列
    private static final String[] AUDIENCE_COLS = {"操作", "字段", "操作符", "值"};
    // 文案要求列
    private static final String[] COPY_COLS = {"通道", "模板", "要求"};

    /** 解析入口：按扩展名分派；不支持的扩展名直接拒绝。 */
    public PlanDocument parse(byte[] bytes, String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseXlsx(bytes);
        }
        if (lower.endsWith(".csv")) {
            return parseCsv(bytes);
        }
        throw new BizException(ErrorCode.BAD_REQUEST,
                "不支持的文件类型: " + filename + "（仅支持 .xlsx / .csv）");
    }

    // ---------- xlsx ----------

    private PlanDocument parseXlsx(byte[] bytes) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet overview = requireSheet(wb, SHEET_OVERVIEW);
            Sheet routes = requireSheet(wb, SHEET_ROUTES);
            Sheet audience = wb.getSheet(SHEET_AUDIENCE);
            Sheet copy = wb.getSheet(SHEET_COPY);
            PlanDocument.PlanOverview ov = readOverview(overview);
            List<PlanDocument.PlanRouteRow> routeRows = readRoutes(routes);
            List<PlanDocument.PlanAudienceRule> audienceRules =
                    audience == null ? List.of() : readAudience(audience);
            List<PlanDocument.PlanCopyNote> copyNotes =
                    copy == null ? List.of() : readCopy(copy);
            return new PlanDocument(ov, routeRows, audienceRules, copyNotes);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "xlsx 解析失败: " + e.getMessage());
        }
    }

    private Sheet requireSheet(Workbook wb, String name) {
        Sheet sheet = wb.getSheet(name);
        if (sheet == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少工作表: " + name);
        }
        return sheet;
    }

    private PlanDocument.PlanOverview readOverview(Sheet sheet) {
        Map<String, Integer> cols = headerIndex(sheet, 0, OVERVIEW_COLS);
        Row data = sheet.getRow(1);
        if (data == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "[计划概览] 缺少数据行");
        }
        String planName = value(data, cols, "计划名称", 2);
        String audience = value(data, cols, "目标人群", 2);
        String triggerType = normalizeTrigger(value(data, cols, "触发方式", 2));
        String triggerTime = cellText(data.getCell(cols.get("触发时间")));
        String eventName = cellText(data.getCell(cols.get("事件名")));
        String timezone = cellText(data.getCell(cols.get("时区")));
        String budget = cellText(data.getCell(cols.get("预算上限")));
        requireTriggerFields(triggerType, triggerTime, eventName, 2);
        return new PlanDocument.PlanOverview(planName, audience, triggerType, triggerTime,
                eventName, timezone, budget);
    }

    private List<PlanDocument.PlanRouteRow> readRoutes(Sheet sheet) {
        Map<String, Integer> cols = headerIndex(sheet, 0, ROUTE_COLS);
        List<PlanDocument.PlanRouteRow> rows = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || isBlankRow(row)) {
                continue;
            }
            String layer = value(row, cols, "人群分层", r + 1);
            String channel = value(row, cols, "通道", r + 1);
            int sequence = intValue(row, cols, "顺序", r + 1);
            String timing = value(row, cols, "时刻/延迟", r + 1);
            String template = value(row, cols, "消息模板", r + 1);
            String freq = cellText(row.getCell(cols.get("单用户频率上限")));
            Integer frequencyLimit = freq.isBlank() ? null : intValue(row, cols, "单用户频率上限", r + 1);
            String remark = cellText(row.getCell(cols.get("备注")));
            rows.add(new PlanDocument.PlanRouteRow(layer, channel, sequence, timing, template,
                    frequencyLimit, remark));
        }
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "[触达计划] 缺少路由数据行");
        }
        return rows;
    }

    private List<PlanDocument.PlanAudienceRule> readAudience(Sheet sheet) {
        Map<String, Integer> cols = headerIndex(sheet, 0, AUDIENCE_COLS);
        List<PlanDocument.PlanAudienceRule> rows = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || isBlankRow(row)) {
                continue;
            }
            rows.add(new PlanDocument.PlanAudienceRule(
                    cellText(row.getCell(cols.get("操作"))),
                    cellText(row.getCell(cols.get("字段"))),
                    cellText(row.getCell(cols.get("操作符"))),
                    cellText(row.getCell(cols.get("值")))));
        }
        return rows;
    }

    private List<PlanDocument.PlanCopyNote> readCopy(Sheet sheet) {
        Map<String, Integer> cols = headerIndex(sheet, 0, COPY_COLS);
        List<PlanDocument.PlanCopyNote> rows = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || isBlankRow(row)) {
                continue;
            }
            rows.add(new PlanDocument.PlanCopyNote(
                    cellText(row.getCell(cols.get("通道"))),
                    cellText(row.getCell(cols.get("模板"))),
                    cellText(row.getCell(cols.get("要求")))));
        }
        return rows;
    }

    // ---------- csv ----------

    /** CSV 节标记行：#计划概览 / #触达计划 / #人群规则 / #文案要求，其后首行 = 表头。 */
    private PlanDocument parseCsv(byte[] bytes) {
        List<List<String>> table = parseCsvTable(bytes);
        Map<String, List<List<String>>> sections = splitSections(table);
        List<List<String>> overviewRows = sections.get(SHEET_OVERVIEW);
        List<List<String>> routeRows = sections.get(SHEET_ROUTES);
        if (overviewRows == null || overviewRows.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "csv 缺少节标记: #计划概览");
        }
        if (routeRows == null || routeRows.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "csv 缺少节标记: #触达计划");
        }
        PlanDocument.PlanOverview ov = readOverviewCsv(overviewRows);
        List<PlanDocument.PlanRouteRow> routes = readRoutesCsv(routeRows);
        List<PlanDocument.PlanAudienceRule> audience = readAudienceCsv(sections.get(SHEET_AUDIENCE));
        List<PlanDocument.PlanCopyNote> copy = readCopyCsv(sections.get(SHEET_COPY));
        return new PlanDocument(ov, routes, audience, copy);
    }

    private PlanDocument.PlanOverview readOverviewCsv(List<List<String>> rows) {
        Map<String, Integer> cols = headerIndex(rows.get(0), OVERVIEW_COLS, 0);
        List<String> data = rows.get(1);
        String planName = value(data, cols, "计划名称", 1);
        String audience = value(data, cols, "目标人群", 1);
        String triggerType = normalizeTrigger(value(data, cols, "触发方式", 1));
        String triggerTime = cell(data, cols, "触发时间");
        String eventName = cell(data, cols, "事件名");
        String timezone = cell(data, cols, "时区");
        String budget = cell(data, cols, "预算上限");
        requireTriggerFields(triggerType, triggerTime, eventName, 1);
        return new PlanDocument.PlanOverview(planName, audience, triggerType, triggerTime,
                eventName, timezone, budget);
    }

    private List<PlanDocument.PlanRouteRow> readRoutesCsv(List<List<String>> rows) {
        Map<String, Integer> cols = headerIndex(rows.get(0), ROUTE_COLS, 0);
        List<PlanDocument.PlanRouteRow> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> data = rows.get(i);
            int line = i + 1;
            String layer = value(data, cols, "人群分层", line);
            String channel = value(data, cols, "通道", line);
            int sequence = intValue(data, line, cols, "顺序");
            String timing = value(data, cols, "时刻/延迟", line);
            String template = value(data, cols, "消息模板", line);
            String freq = cell(data, cols, "单用户频率上限");
            Integer frequencyLimit = freq.isBlank() ? null : intValue(data, line, cols, "单用户频率上限");
            String remark = cell(data, cols, "备注");
            out.add(new PlanDocument.PlanRouteRow(layer, channel, sequence, timing, template,
                    frequencyLimit, remark));
        }
        if (out.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "[触达计划] 缺少路由数据行");
        }
        return out;
    }

    private List<PlanDocument.PlanAudienceRule> readAudienceCsv(List<List<String>> rows) {
        if (rows == null || rows.size() < 2) {
            return List.of();
        }
        Map<String, Integer> cols = headerIndex(rows.get(0), AUDIENCE_COLS, 0);
        List<PlanDocument.PlanAudienceRule> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> data = rows.get(i);
            out.add(new PlanDocument.PlanAudienceRule(
                    cell(data, cols, "操作"), cell(data, cols, "字段"),
                    cell(data, cols, "操作符"), cell(data, cols, "值")));
        }
        return out;
    }

    private List<PlanDocument.PlanCopyNote> readCopyCsv(List<List<String>> rows) {
        if (rows == null || rows.size() < 2) {
            return List.of();
        }
        Map<String, Integer> cols = headerIndex(rows.get(0), COPY_COLS, 0);
        List<PlanDocument.PlanCopyNote> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> data = rows.get(i);
            out.add(new PlanDocument.PlanCopyNote(
                    cell(data, cols, "通道"), cell(data, cols, "模板"), cell(data, cols, "要求")));
        }
        return out;
    }

    /** BOM 剥离 + RFC4180 字段解析（引号包裹、"" 转义）；返回全表行。 */
    private List<List<String>> parseCsvTable(byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<List<String>> table = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        List<String> row = new ArrayList<>();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString().trim());
                field.setLength(0);
                if (row.size() > 1 || !row.get(0).isBlank()) {
                    table.add(row);
                }
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString().trim());
            if (row.size() > 1 || !row.get(0).isBlank()) {
                table.add(row);
            }
        }
        return table;
    }

    private Map<String, List<List<String>>> splitSections(List<List<String>> table) {
        Map<String, List<List<String>>> sections = new LinkedHashMap<>();
        List<List<String>> current = null;
        for (List<String> row : table) {
            String sec = sectionMarker(row);
            if (sec != null) {
                current = sections.computeIfAbsent(sec, k -> new ArrayList<>());
            } else if (current != null) {
                current.add(row);
            }
        }
        return sections;
    }

    private String sectionMarker(List<String> row) {
        if (row.size() == 1) {
            String t = row.get(0).trim();
            if (t.startsWith("#")) {
                String name = t.substring(1).trim();
                for (String s : new String[]{SHEET_OVERVIEW, SHEET_ROUTES, SHEET_AUDIENCE, SHEET_COPY}) {
                    if (s.equals(name)) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    // ---------- 通用 ----------

    private Map<String, Integer> headerIndex(Sheet sheet, int headerRow, String[] cols) {
        Row header = sheet.getRow(headerRow);
        if (header == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少表头行");
        }
        Map<String, Integer> idx = new HashMap<>();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String name = FORMATTER.formatCellValue(header.getCell(c)).trim();
            if (!name.isEmpty()) {
                idx.put(name, c);
            }
        }
        for (String col : cols) {
            if (!idx.containsKey(col)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "缺少列: " + col);
            }
        }
        return idx;
    }

    private Map<String, Integer> headerIndex(List<String> header, String[] cols, int line) {
        Map<String, Integer> idx = new HashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String name = header.get(c).trim();
            if (!name.isEmpty()) {
                idx.put(name, c);
            }
        }
        for (String col : cols) {
            if (!idx.containsKey(col)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "[第" + line + "行] 缺少列: " + col);
            }
        }
        return idx;
    }

    private String value(Row row, Map<String, Integer> cols, String col, int rowNum) {
        String v = cellText(row.getCell(cols.get(col)));
        if (v.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, cellLocation(rowNum, cols.get(col)) + " 必填字段缺失: " + col);
        }
        return v;
    }

    private int intValue(Row row, Map<String, Integer> cols, String col, int rowNum) {
        String v = cellText(row.getCell(cols.get(col)));
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, cellLocation(rowNum, cols.get(col)) + " 字段须为整数: " + col);
        }
    }

    private String value(List<String> data, Map<String, Integer> cols, String col, int line) {
        String v = cell(data, cols, col);
        if (v.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "[第" + line + "行第" + (cols.get(col) + 1) + "列] 必填字段缺失: " + col);
        }
        return v;
    }

    private int intValue(List<String> data, int line, Map<String, Integer> cols, String col) {
        String v = cell(data, cols, col);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "[第" + line + "行第" + (cols.get(col) + 1) + "列] 字段须为整数: " + col);
        }
    }

    private String cell(List<String> data, Map<String, Integer> cols, String col) {
        Integer c = cols.get(col);
        return c != null && c < data.size() ? data.get(c) : "";
    }

    private String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        String v = FORMATTER.formatCellValue(cell).trim();
        return v.replace("\u00A0", "");
    }

    private String cellLocation(int rowNum, int colNum) {
        return "[第" + rowNum + "行第" + (colNum + 1) + "列]";
    }

    private boolean isBlankRow(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            if (!cellText(row.getCell(c)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String normalizeTrigger(String raw) {
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.contains("TIMED") || t.equals("定时")) {
            return "TIMED";
        }
        if (t.contains("EVENT") || t.equals("事件")) {
            return "EVENT";
        }
        if (t.contains("MANUAL") || t.equals("手动")) {
            return "MANUAL";
        }
        throw new BizException(ErrorCode.BAD_REQUEST,
                "[触发方式] 仅支持 TIMED / EVENT / MANUAL，收到: " + raw);
    }

    private void requireTriggerFields(String triggerType, String triggerTime, String eventName, int rowNum) {
        if ("TIMED".equals(triggerType) && (triggerTime == null || triggerTime.isBlank())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "[第" + rowNum + "行] TIMED 触发须填写触发时间");
        }
        if ("EVENT".equals(triggerType) && (eventName == null || eventName.isBlank())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "[第" + rowNum + "行] EVENT 触发须填写事件名");
        }
    }
}