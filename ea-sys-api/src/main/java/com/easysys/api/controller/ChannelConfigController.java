package com.easysys.api.controller;

import com.easysys.api.dto.channel.ChannelConfigRequest;
import com.easysys.api.dto.channel.ChannelConfigView;
import com.easysys.api.service.ChannelConfigService;
import com.easysys.common.web.ApiResponse;
import com.easysys.common.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 通道凭据配置（按租户）：短信 / 邮件真实供应商接入参数。 */
@RestController
@RequestMapping("/api/channel-configs")
public class ChannelConfigController {

    private final ChannelConfigService service;

    public ChannelConfigController(ChannelConfigService service) {
        this.service = service;
    }

    /** 通道配置列表（config 已脱敏）。 */
    @GetMapping
    public ApiResponse<List<ChannelConfigView>> list(@RequestParam(required = false) String channel) {
        return ApiResponse.ok(service.list(TenantContext.require(), channel));
    }

    /** 覆盖保存通道配置（加密落库）。 */
    @PutMapping("/{channel}")
    public ApiResponse<ChannelConfigView> save(@PathVariable String channel,
                                               @Valid @RequestBody ChannelConfigRequest req) {
        return ApiResponse.ok(service.save(TenantContext.require(), channel, req.config(), req.enabled()));
    }

    /** 删除通道配置。 */
    @DeleteMapping("/{channel}")
    public ApiResponse<Void> delete(@PathVariable String channel) {
        service.delete(TenantContext.require(), channel);
        return ApiResponse.ok(null);
    }
}