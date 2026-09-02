package com.easysys.api.service;

import com.easysys.api.dto.retention.EventImportRequest;
import com.easysys.api.entity.Event;
import com.easysys.api.mapper.EventMapper;
import com.easysys.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 行为事件导入（留存/流失的活跃信号源）。
 * 幂等：uk_event_dedup + ON CONFLICT DO NOTHING，重复上报返回 duplicates 计数。
 * 新导入事件按 eventName 匹配已发布 EVENT 触发流程，命中则以该用户单成员执行；失败不阻断导入。
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private static final int BATCH = 500;

    private final EventMapper eventMapper;
    private final ObjectMapper json;
    private final EventQueueService eventQueue;

    public EventService(EventMapper eventMapper, ObjectMapper json, EventQueueService eventQueue) {
        this.eventMapper = eventMapper;
        this.json = json;
        this.eventQueue = eventQueue;
    }

    /** 批量导入，返回 [imported, duplicates]；新导入事件触发匹配的 EVENT 工作流。 */
    @Transactional
    public Map<String, Integer> importEvents(EventImportRequest req) {
        Long tenantId = TenantContext.require();
        int imported = 0;
        int duplicates = 0;
        List<EventImportRequest.EventItem> items = req.events();
        List<EventImportRequest.EventItem> newEvents = new ArrayList<>();
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
                if (eventMapper.insertIgnore(e) > 0) {
                    imported++;
                    newEvents.add(item);
                } else {
                    duplicates++;
                }
            }
            idx = end;
        }
        // 事件触发：异步入流（Redis Streams），消费端匹配 EVENT 流程执行。
        // 入流失败仅告警丢弃（同原同步语义「触发失败不影响导入结果」）。
        for (EventImportRequest.EventItem item : newEvents) {
            try {
                Map<String, Object> payload = item.payload() instanceof Map
                        ? (Map<String, Object>) item.payload() : null;
                eventQueue.enqueue(tenantId, item.contactId(), item.eventName(), payload);
            } catch (Exception ex) {
                log.warn("事件入流失败 contactId={} event={}", item.contactId(), item.eventName(), ex);
            }
        }
        return Map.of("imported", imported, "duplicates", duplicates);
    }
}