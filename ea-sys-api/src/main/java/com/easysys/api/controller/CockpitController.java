package com.easysys.api.controller;

import com.easysys.api.dto.cockpit.AgentGraphEntryView;
import com.easysys.api.dto.cockpit.CockpitInsightView;
import com.easysys.api.dto.cockpit.CockpitOverviewView;
import com.easysys.api.dto.cockpit.LlmTraceView;
import com.easysys.api.service.CockpitService;
import com.easysys.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 驾驶舱：监控总览（LLM 调用聚合/图谱/知识库/记忆）+ 图谱管理（登记 + 状态，纯 CRUD）
 * + 洞察（AgentPolicy 确定性规划 + 300s 缓存）+ LLM 调用追踪。
 */
@RestController
@RequestMapping("/api/cockpit")
public class CockpitController {

    private final CockpitService cockpitService;

    public CockpitController(CockpitService cockpitService) {
        this.cockpitService = cockpitService;
    }

    /** 监控总览：LLM 调用聚合 + 图谱 + 知识库 + 记忆 + Agent 目录。 */
    @GetMapping("/overview")
    public ApiResponse<CockpitOverviewView> overview() {
        return ApiResponse.ok(cockpitService.overview());
    }

    /** 图谱清单：按 module 过滤（缺省 = 全部八类领域）；内置目录 ∪ 用户登记，同 key 用户行覆盖内置。 */
    @GetMapping("/graph")
    public ApiResponse<List<AgentGraphEntryView>> graph(@RequestParam(required = false) String module) {
        return ApiResponse.ok(cockpitService.listGraph(module));
    }

    /** 新建图谱登记（模块判别 + 状态管理，纯 CRUD 不触发 Agent）。 */
    @PostMapping("/graph")
    public ApiResponse<AgentGraphEntryView> createGraph(@Valid @RequestBody AgentGraphEntryView.SaveRequest req,
                                                        @RequestAttribute String username) {
        return ApiResponse.ok(cockpitService.saveEntry(req, username));
    }

    /** 编辑图谱登记。 */
    @PutMapping("/graph/{id}")
    public ApiResponse<AgentGraphEntryView> updateGraph(@PathVariable Long id,
                                                        @Valid @RequestBody AgentGraphEntryView.SaveRequest req,
                                                        @RequestAttribute String username) {
        return ApiResponse.ok(cockpitService.updateEntry(id, req, username));
    }

    /** 状态开关（ENABLED/DISABLED）。 */
    @PatchMapping("/graph/{id}/status")
    public ApiResponse<AgentGraphEntryView> setGraphStatus(@PathVariable Long id,
                                                           @RequestParam String status,
                                                           @RequestAttribute String username) {
        return ApiResponse.ok(cockpitService.setStatus(id, status));
    }

    /** 删除图谱登记（软删）。 */
    @DeleteMapping("/graph/{id}")
    public ApiResponse<Void> deleteGraph(@PathVariable Long id, @RequestAttribute String username) {
        cockpitService.deleteEntry(id);
        return ApiResponse.ok(null);
    }

    /** 洞察：force=1 绕过 300s 缓存重新生成（AgentPolicy + 审计）。 */
    @GetMapping("/insights")
    public ApiResponse<CockpitInsightView> insights(@RequestParam(defaultValue = "false") boolean force,
                                                    @RequestAttribute String username) {
        return ApiResponse.ok(cockpitService.insights(force, username));
    }

    /** LLM 调用追踪：最近 limit 条（默认 20，上限 100）；trace 非空按评测运行追踪 ID 过滤联动。 */
    @GetMapping("/llm-traces")
    public ApiResponse<List<LlmTraceView>> llmTraces(@RequestParam(defaultValue = "20") int limit,
                                                     @RequestParam(required = false) String trace) {
        return ApiResponse.ok(cockpitService.llmTraces(limit, trace));
    }
}