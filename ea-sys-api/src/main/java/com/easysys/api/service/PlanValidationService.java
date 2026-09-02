package com.easysys.api.service;

import com.easysys.api.dto.plan.PlanDocument;
import com.easysys.api.dto.plan.PlanValidationView;
import com.easysys.api.entity.AgentAudit;
import com.easysys.api.entity.ValidationReport;
import com.easysys.api.mapper.AgentAuditMapper;
import com.easysys.api.mapper.ValidationReportMapper;
import com.easysys.api.service.plan.PlanConsistencyValidator;
import com.easysys.api.service.plan.PlanFileParser;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 计划导入校验：文件解析 → 一致性校验 → 报告落库（validation_report）+ 审计（audit_log）→ 回看。
 * 发布闸门由 {@code WorkflowService.publish} 查询最近报告 decision=BLOCKED 拦截。
 */
@Service
public class PlanValidationService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final DateTimeFormatter REPORT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final PlanFileParser parser;
    private final PlanConsistencyValidator validator;
    private final ValidationReportMapper reportMapper;
    private final AgentAuditMapper auditMapper;
    private final ObjectMapper json;

    public PlanValidationService(PlanFileParser parser,
                                 PlanConsistencyValidator validator,
                                 ValidationReportMapper reportMapper,
                                 AgentAuditMapper auditMapper,
                                 ObjectMapper json) {
        this.parser = parser;
        this.validator = validator;
        this.reportMapper = reportMapper;
        this.auditMapper = auditMapper;
        this.json = json;
    }

    /** 导入并校验计划文件，落库报告与审计，返回报告视图。 */
    public PlanValidationView importPlan(Long workflowId, MultipartFile file, String operator) {
        Long tenantId = TenantContext.require();
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请上传计划文件（.xlsx / .csv）");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "计划文件超过 10MB 限制");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".xlsx") || lower.endsWith(".csv"))) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的文件类型: " + filename + "（仅支持 .xlsx / .csv）");
        }

        PlanValidationView view = null;
        String reason = null;
        String status = "SUCCESS";
        try {
            byte[] bytes = file.getBytes();
            PlanDocument doc = parser.parse(bytes, filename);
            view = validator.validate(workflowId, doc, tenantId);
            String ext = lower.substring(lower.lastIndexOf('.') + 1); // xlsx / csv
            persistReport(tenantId, workflowId, view, doc.overview().planName(), ext, filename, operator);
        } catch (BizException e) {
            status = "FAILED";
            reason = e.getMessage();
            throw e;
        } catch (Exception e) {
            status = "FAILED";
            reason = "解析或校验异常: " + e.getMessage();
            throw new BizException(ErrorCode.BAD_REQUEST, reason);
        } finally {
            persistAudit(tenantId, workflowId, filename, file.getSize(), status, reason,
                    status.equals("SUCCESS") ? view : null, operator);
        }
        return view;
    }

    /** 最近一次校验报告回看（无 → null）。 */
    public PlanValidationView latest(Long workflowId) {
        Long tenantId = TenantContext.require();
        ValidationReport row = reportMapper.selectLatest(tenantId, workflowId);
        if (row == null) {
            return null;
        }
        return toView(row);
    }

    // ---------- 报告持久化 ----------

    private void persistReport(Long tenantId, Long workflowId, PlanValidationView view,
                               String planName, String fileType, String fileName, String operator) {
        ValidationReport row = new ValidationReport();
        row.setTenantId(tenantId);
        row.setWorkflowId(workflowId);
        row.setDecision(view.decision());
        row.setReport(reportJson(view, planName, fileType, fileName));
        row.setFileType(fileType.startsWith(".") ? fileType.substring(1).toUpperCase(Locale.ROOT) : fileType.toUpperCase(Locale.ROOT));
        row.setFileName(fileName);
        row.setCreatedBy(operator);
        row.setCreatedAt(Instant.now());
        reportMapper.insert(row);
    }

    /** report JSON 与 PlanValidationView 同构：{planName,fileType,fileName,planSummary,dimensions,summary,decision}。 */
    private String reportJson(PlanValidationView view, String planName, String fileType, String fileName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planName", planName);
        m.put("fileType", fileType);
        m.put("fileName", fileName);
        m.put("planSummary", view.planSummary());
        m.put("dimensions", view.dimensions());
        m.put("summary", view.summary());
        m.put("decision", view.decision());
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "报告序列化失败: " + e.getMessage());
        }
    }

    /** 回看视图：行字段（id/workflowId/createdAt/createdBy/decision/fileType/fileName）+ report JSON 内字段。 */
    private PlanValidationView toView(ValidationReport row) {
        try {
            Map<String, Object> m = json.readValue(row.getReport(),
                    json.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            String planName = str(m.get("planName"));
            String planSummary = str(m.get("planSummary"));
            List<PlanValidationView.Dimension> dims = json.convertValue(m.get("dimensions"),
                    json.getTypeFactory().constructCollectionType(List.class, PlanValidationView.Dimension.class));
            PlanValidationView.Summary summary = m.get("summary") == null ? null
                    : json.convertValue(m.get("summary"), PlanValidationView.Summary.class);
            String fileType = row.getFileType() == null ? null : switch (row.getFileType().toLowerCase(Locale.ROOT)) {
                case "xlsx", "xls" -> "xlsx";
                case "csv" -> "csv";
                default -> row.getFileType();
            };
            return new PlanValidationView(row.getId(), row.getWorkflowId(), planName, fileType,
                    row.getFileName(), row.getDecision(), planSummary, dims, summary,
                    row.getCreatedAt() == null ? null : REPORT_TIME.format(row.getCreatedAt()), row.getCreatedBy());
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "报告回读失败: " + e.getMessage());
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ---------- 审计 ----------

    private void persistAudit(Long tenantId, Long workflowId, String filename, long size,
                              String status, String reason, PlanValidationView view, String operator) {
        try {
            AgentAudit a = new AgentAudit();
            a.setTenantId(tenantId);
            a.setAgentType("PLAN_VALIDATION");
            a.setAction("import_validate");
            a.setStatus(status);
            a.setReason(reason == null ? "workflowId=" + workflowId : reason + "（workflowId=" + workflowId + "）");
            a.setInputSummary("{\"file\":\"" + filename + "\",\"size\":" + size + "}");
            a.setOutput(view == null ? null : reportJson(view, null, null, null));
            a.setSchemaValid(true);
            a.setModel(null); // 确定性校验，无模型
            a.setOperator(operator);
            a.setCreatedAt(Instant.now());
            auditMapper.insert(a);
        } catch (Exception ignored) {
            // 审计失败不影响主流程
        }
    }

    // ---------- 模板下载 ----------

    /** 下载导入模板：xlsx（4 Sheet）/ csv（#节标记 + 表头 + 示例行）。 */
    public byte[] downloadTemplate(String type) {
        if ("csv".equalsIgnoreCase(type)) {
            return csvTemplate().getBytes(StandardCharsets.UTF_8);
        }
        if ("xlsx".equalsIgnoreCase(type)) {
            return xlsxTemplate();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "模板类型仅支持 xlsx / csv");
    }

    private byte[] xlsxTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet overview = wb.createSheet("计划概览");
            overview.createRow(0).createCell(0).setCellValue("计划名称");
            overview.getRow(0).createCell(1).setCellValue("目标人群");
            overview.getRow(0).createCell(2).setCellValue("触发方式");
            overview.getRow(0).createCell(3).setCellValue("触发时间");
            overview.getRow(0).createCell(4).setCellValue("事件名");
            overview.getRow(0).createCell(5).setCellValue("时区");
            overview.getRow(0).createCell(6).setCellValue("预算上限");
            Row ovRow = overview.createRow(1);
            String[] ovSample = {"618 大促召回", "近 30 天未购会员", "TIMED", "0 0 9 * * 1", "", "Asia/Shanghai", "5000"};
            for (int c = 0; c < ovSample.length; c++) {
                ovRow.createCell(c).setCellValue(ovSample[c]);
            }

            Sheet routes = wb.createSheet("触达计划");
            String[] routeHead = {"人群分层", "通道", "顺序", "时刻/延迟", "消息模板", "单用户频率上限", "备注"};
            Row routHead = routes.createRow(0);
            for (int c = 0; c < routeHead.length; c++) {
                routHead.createCell(c).setCellValue(routeHead[c]);
            }
            String[][] routeSamples = {
                    {"未购会员", "sms", "1", "D+0", "大促召回短信", "1", "首触短信"},
                    {"未购会员", "email", "2", "D+1", "大促召回邮件", "", "次日补邮件"},
            };
            for (int r = 0; r < routeSamples.length; r++) {
                Row row = routes.createRow(1 + r);
                for (int c = 0; c < routeSamples[r].length; c++) {
                    row.createCell(c).setCellValue(routeSamples[r][c]);
                }
            }

            Sheet audience = wb.createSheet("人群规则");
            String[] audHead = {"操作", "字段", "操作符", "值"};
            Row audRow = audience.createRow(0);
            for (int c = 0; c < audHead.length; c++) {
                audRow.createCell(c).setCellValue(audHead[c]);
            }
            String[] audSample = {"包含", "last_purchase_days", ">=", "30"};
            Row audData = audience.createRow(1);
            for (int c = 0; c < audSample.length; c++) {
                audData.createCell(c).setCellValue(audSample[c]);
            }

            Sheet copy = wb.createSheet("文案要求");
            String[] copyHead = {"通道", "模板", "要求"};
            Row copyRow = copy.createRow(0);
            for (int c = 0; c < copyHead.length; c++) {
                copyRow.createCell(c).setCellValue(copyHead[c]);
            }
            String[] copySample = {"sms", "大促召回短信", "突出折扣力度，附带链接"};
            Row copyData = copy.createRow(1);
            for (int c = 0; c < copySample.length; c++) {
                copyData.createCell(c).setCellValue(copySample[c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "xlsx 模板生成失败: " + e.getMessage());
        }
    }

    private String csvTemplate() {
        StringBuilder sb = new StringBuilder();
        sb.append("#计划概览\n");
        sb.append("计划名称,目标人群,触发方式,触发时间,事件名,时区,预算上限\n");
        sb.append("618 大促召回,近 30 天未购会员,TIMED,0 0 9 * * 1,,Asia/Shanghai,5000\n");
        sb.append("\n#触达计划\n");
        sb.append("人群分层,通道,顺序,时刻/延迟,消息模板,单用户频率上限,备注\n");
        sb.append("未购会员,sms,1,D+0,大促召回短信,1,首触短信\n");
        sb.append("未购会员,email,2,D+1,大促召回邮件,,次日补邮件\n");
        sb.append("\n#人群规则\n");
        sb.append("操作,字段,操作符,值\n");
        sb.append("包含,last_purchase_days,>=,30\n");
        sb.append("\n#文案要求\n");
        sb.append("通道,模板,要求\n");
        sb.append("sms,大促召回短信,突出折扣力度，附带链接\n");
        return sb.toString();
    }
}