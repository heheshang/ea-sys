package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.agent.TouchReorder;
import com.easysys.api.dto.agent.RoutePreviewView;
import com.easysys.common.tenant.TenantContext;
import com.easysys.engine.entity.DeliveryRecord;
import com.easysys.engine.mapper.DeliveryRecordMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 路由预览（ROUTER agent 确定性重排）：近 24h 已触达渠道（SENT/DELIVERED）后置，
 * 未触达渠道保持 DAG 原序在前；全部触达则保持原序（频率闸门在 ACTION 侧再兜底）。
 */
@Service
public class RoutePreviewService {

    private static final List<String> DEFAULT_ORDER = List.of("sms", "email");
    private static final List<String> TOUCHED_STATUS = List.of("SENT", "DELIVERED");
    private static final int WINDOW_HOURS = 24;

    private final DeliveryRecordMapper deliveryMapper;

    public RoutePreviewService(DeliveryRecordMapper deliveryMapper) {
        this.deliveryMapper = deliveryMapper;
    }

    public RoutePreviewView preview(Long contactId, List<String> routeOrder) {
        TenantContext.require();
        List<String> order = normalizeOrder(routeOrder);

        List<String> touched = new ArrayList<>();
        if (contactId != null) {
            Instant since = Instant.now().minus(WINDOW_HOURS, ChronoUnit.HOURS);
            List<DeliveryRecord> recs = deliveryMapper.selectList(new LambdaQueryWrapper<DeliveryRecord>()
                    .eq(DeliveryRecord::getContactId, contactId)
                    .ge(DeliveryRecord::getCreatedAt, since)
                    .in(DeliveryRecord::getStatus, TOUCHED_STATUS));
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (DeliveryRecord r : recs) {
                if (r.getChannel() != null && (r.getChannel().equals("sms") || r.getChannel().equals("email"))) {
                    seen.add(r.getChannel());
                }
            }
            touched = new ArrayList<>(seen);
        }

        List<String> reordered = TouchReorder.reorder(order, touched);
        return new RoutePreviewView(contactId, touched, reordered, reordered.equals(order));
    }

    private List<String> normalizeOrder(List<String> routeOrder) {
        if (routeOrder == null || routeOrder.isEmpty()) {
            return DEFAULT_ORDER;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String c : routeOrder) {
            if (c != null && (c.equals("sms") || c.equals("email"))) {
                seen.add(c);
            }
        }
        return seen.isEmpty() ? DEFAULT_ORDER : new ArrayList<>(seen);
    }
}