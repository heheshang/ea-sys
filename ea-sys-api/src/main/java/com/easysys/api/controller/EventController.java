package com.easysys.api.controller;

import com.easysys.api.dto.retention.EventImportRequest;
import com.easysys.api.service.EventService;
import com.easysys.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 行为事件上报（留存曲线/流失预警的活跃信号输入）。
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /** 批量导入（幂等：重复事件忽略，返回 imported/duplicates）。 */
    @PostMapping
    public ApiResponse<Map<String, Integer>> importEvents(@Valid @RequestBody EventImportRequest req) {
        return ApiResponse.ok(eventService.importEvents(req));
    }
}