package com.easysys.api.controller;

import com.easysys.api.dto.plan.PlanValidationView;
import com.easysys.api.service.PlanValidationService;
import com.easysys.common.web.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 计划导入校验（①阶段）：导入校验 / 最近报告回看 / 模板下载。
 * 独立前缀 /api/plan-validation，避开 WorkflowController 的 /{id} 路径段纠缠。
 */
@RestController
@RequestMapping("/api/plan-validation")
public class PlanValidationController {

    private final PlanValidationService service;

    public PlanValidationController(PlanValidationService service) {
        this.service = service;
    }

    /** 导入计划文件并校验（multipart，≤10MB，.xlsx/.csv）。 */
    @PostMapping(value = "/{workflowId}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PlanValidationView> importPlan(@PathVariable Long workflowId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestAttribute String username) {
        return ApiResponse.ok(service.importPlan(workflowId, file, username));
    }

    /** 最近一次校验报告回看（无报告 → data=null）。 */
    @GetMapping("/{workflowId}")
    public ApiResponse<PlanValidationView> latest(@PathVariable Long workflowId) {
        return ApiResponse.ok(service.latest(workflowId));
    }

    /** 下载导入模板（type=xlsx|csv）。 */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam(value = "type", defaultValue = "xlsx") String type) {
        byte[] bytes = service.downloadTemplate(type);
        String ext = "csv".equalsIgnoreCase(type) ? "csv" : "xlsx";
        String disposition = "attachment; filename=plan-import-template." + ext;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(ext.equals("csv") ? MediaType.parseMediaType("text/csv;charset=UTF-8")
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}