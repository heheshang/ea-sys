package com.easysys.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.easysys.common.web.ApiResponse;
import com.easysys.engine.entity.DeliveryRecord;
import com.easysys.engine.mapper.DeliveryRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * 通道回执回调入口：ea-sys-notify 收到真实通道异步回执后回调此处，
 * 按 channelMsgId 把 delivery_record 从 SENT 更新为 DELIVERED/FAILED（真正触达确认）。
 *
 * 鉴权：内部 token（X-Internal-Token，与 notify 配置一致）；已终态（DELIVERED/FAILED）重复回调幂等忽略。
 */
@RestController
@RequestMapping("/api/deliveries")
public class DeliveryCallbackController {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCallbackController.class);

    private final DeliveryRecordMapper deliveryRecordMapper;
    private final String callbackToken;

    public DeliveryCallbackController(DeliveryRecordMapper deliveryRecordMapper,
                                      @Value("${easysys.notify.callback-token:ea-sys-notify-dev-token}") String callbackToken) {
        this.deliveryRecordMapper = deliveryRecordMapper;
        this.callbackToken = callbackToken;
    }

    @PostMapping("/callback")
    public ApiResponse<Void> callback(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                      @RequestBody Map<String, String> body) {
        if (token == null || !token.equals(callbackToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部 token 无效");
        }
        String channelMsgId = body.get("channelMsgId");
        String status = body.get("status");
        String tenantIdStr = body.get("tenantId");
        if (channelMsgId == null || channelMsgId.isBlank()
                || (!"DELIVERED".equals(status) && !"FAILED".equals(status))
                || tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelMsgId/tenantId/status 非法");
        }
        final long tenantId;
        try {
            tenantId = Long.parseLong(tenantIdStr);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 非法");
        }
        try {
            TenantContext.set(new TenantInfo(tenantId));
            DeliveryRecord r = deliveryRecordMapper.selectOne(new LambdaQueryWrapper<DeliveryRecord>()
                    .eq(DeliveryRecord::getChannelMsgId, channelMsgId)
                    .last("LIMIT 1"));
            if (r == null) {
                log.warn("回执无匹配 delivery_record：tenantId={} channelMsgId={} status={}", tenantId, channelMsgId, status);
                return ApiResponse.ok(null); // 已受理过但记录缺失（幂等安全）
            }
            if ("DELIVERED".equals(r.getStatus()) || "FAILED".equals(r.getStatus())) {
                log.info("回执幂等忽略（已终态）：channelMsgId={} status={} current={}", channelMsgId, status, r.getStatus());
                return ApiResponse.ok(null);
            }
            DeliveryRecord upd = new DeliveryRecord();
            upd.setId(r.getId());
            upd.setStatus(status);
            upd.setError(body.get("error"));
            upd.setUpdatedAt(Instant.now());
            deliveryRecordMapper.updateById(upd);
            log.info("回执更新触达状态：deliveryId={} channelMsgId={} {}→{}",
                    r.getId(), channelMsgId, r.getStatus(), status);
            return ApiResponse.ok(null);
        } finally {
            TenantContext.clear();
        }
    }
}