package com.easysys.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 事件触发消息队列（Redis Streams，Redisson RStream）。
 *
 * <p>事件触发从「导入 HTTP 请求内同步执行」改为「异步入流消费」：事件批量导入时仅写入
 * stream，消费端 {@link EventQueueConsumer} 轮询投递，解耦导入吞吐与工作流执行。
 * 消息自包含租户（tid），消费线程无 HTTP 租户上下文，按消息显式 set/clear。
 *
 * <p>投递语义：消费者组 + ACK；执行结果只记录不重投（失败等同现状「触发失败仅告警」，
 * 避免重复触达与顺序错乱）。
 */
@Service
public class EventQueueService {

    private static final Logger log = LoggerFactory.getLogger(EventQueueService.class);

    public static final String STREAM = "easysys:trigger:event:stream";

    private final RedissonClient redisson;
    private final ObjectMapper json;

    public EventQueueService(RedissonClient redisson, ObjectMapper json) {
        this.redisson = redisson;
        this.json = json;
    }

    /** 事件入流。payload 序列化为 JSON 字符串存储（值全 String，不依赖 Redisson codec）。 */
    public void enqueue(Long tenantId, Long contactId, String eventName, Map<String, Object> payload) {
        String payloadJson = null;
        if (payload != null) {
            try {
                payloadJson = json.writeValueAsString(payload);
            } catch (Exception ex) {
                throw new IllegalArgumentException("事件 payload 入流序列化失败: " + payload, ex);
            }
        }
        RStream<String, String> stream = redisson.getStream(STREAM);
        stream.add(StreamAddArgs.entries(Map.of(
                "tid", String.valueOf(tenantId),
                "cid", String.valueOf(contactId),
                "event", eventName,
                "payload", payloadJson)));
    }
}