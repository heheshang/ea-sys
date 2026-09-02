package com.easysys.api.service;

import com.easysys.api.dto.retention.EventImportRequest;
import com.easysys.api.entity.Event;
import com.easysys.api.mapper.EventMapper;
import com.easysys.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 行为事件导入（留存/流失的活跃信号源）。
 * 幂等：uk_event_dedup + ON CONFLICT DO NOTHING，重复上报返回 duplicates 计数。
 */
@Service
public class EventService {

    private static final int BATCH = 500;

    private final EventMapper eventMapper;
    private final ObjectMapper json;

    public EventService(EventMapper eventMapper, ObjectMapper json) {
        this.eventMapper = eventMapper;
        this.json = json;
    }

    /** 批量导入，返回 [imported, duplicates]。 */
    @Transactional
    public Map<String, Integer> importEvents(EventImportRequest req) {
        Long tenantId = TenantContext.require();
        int imported = 0;
        int duplicates = 0;
        List<EventImportRequest.EventItem> items = req.events();
        int idx = 0;
        while (idx < items.size()) {
            int end = Math.min(idx + BATCH, items.size());
            List<EventImportRequest.EventItem> batch = items.subList(idx, end);
            for (EventImportRequest.EventItem item : batch) {
                Event e = new Event();
                e.setTenantId(tenantId);
                e.setContactId(item.contactId());
                e.setEventName(item.eventName());
                e.setOccurredAt(item.occurredAt());
                if (item.payload() != null) {
                    try {
                        e.setPayload(json.writeValueAsString(item.payload()));
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("payload 序列化失败: " + item.payload(), ex);
                    }
                }
                imported += eventMapper.insertIgnore(e);
            }
            idx = end;
        }
        return Map.of("imported", imported, "duplicates", items.size() - imported);
    }
}