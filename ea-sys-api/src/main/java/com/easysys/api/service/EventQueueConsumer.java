package com.easysys.api.service;

import com.easysys.common.tenant.TenantContext;
import com.easysys.common.tenant.TenantInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 事件队列消费端：轮询 {@link EventQueueService#STREAM} 消费者组，逐条还原租户上下文后
 * 走 {@link TriggerService#fireEvent}（匹配 EVENT 流程 + eventFilter + 单用户执行）。
 *
 * <p>线程模型与 {@code TriggerService.scanScheduledDues} 一致：@Scheduled 轮询 + RLock
 * 防多实例双跑；消费成功后 XACK，失败同样 ACK 仅告警（对齐原同步语义「触发失败只告警、
 * 导入不受影响」，不重投避免重复触达）。测试经 {@link #pollOnce()} 显式驱动，
 * @Scheduled 仅在 {@code easysys.trigger.event.enabled=true}（默认）时生效。
 */
@Service
public class EventQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventQueueConsumer.class);

    public static final String GROUP = "easysys:trigger:event:group";
    private static final String CONSUMER = "primary";
    private static final String LOCK_KEY = "easysys:trigger:event:lock";

    private final RedissonClient redisson;
    private final TriggerService triggerService;
    private final ObjectMapper json;
    private final boolean enabled;

    public EventQueueConsumer(RedissonClient redisson, TriggerService triggerService,
                              ObjectMapper json,
                              @Value("${easysys.trigger.event.enabled:true}") boolean enabled) {
        this.redisson = redisson;
        this.triggerService = triggerService;
        this.json = json;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${easysys.trigger.event.poll-ms:2000}")
    public void pollScheduled() {
        if (!enabled) {
            return;
        }
        pollOnce();
    }

    /** 单轮消费：抢锁 → 建组兜底 → 批量读 → 逐条执行并 ACK。幂等，可被测试直接驱动。 */
    public void pollOnce() {
        RLock lock = redisson.getLock(LOCK_KEY);
        if (!lock.tryLock()) {
            return; // 其他实例正在消费
        }
        try {
            RStream<String, String> stream = redisson.getStream(EventQueueService.STREAM);
            ensureGroup(stream);
            // timeout 传正数短等待：BLOCK 0 会无限阻塞（≥Duration.ZERO 语义），轮询必须非阻塞
            Map<StreamMessageId, Map<String, String>> batch = stream.readGroup(
                    GROUP, CONSUMER, StreamReadGroupArgs.neverDelivered().count(100).timeout(Duration.ofMillis(10)));
            if (batch.isEmpty()) {
                return;
            }
            for (Map.Entry<StreamMessageId, Map<String, String>> e : batch.entrySet()) {
                dispatch(stream, e.getKey(), e.getValue());
            }
        } catch (Exception ex) {
            log.warn("事件队列消费异常", ex);
        } finally {
            lock.unlock();
        }
    }

    /** 组不存在（首启 / flushall 后）时以 MAKESTREAM 重建；已存在则忽略 BUSYGROUP 异常。
     * 建组 id 用 ALL（从 stream 头起）：组只在首次消费时创建，若建组前已有入流消息
     * （导入先行、宕机期间积压），NEWEST 会令其永远不在投递范围；ALL 确保不丢事件。 */
    private void ensureGroup(RStream<String, String> stream) {
        try {
            stream.createGroup(StreamCreateGroupArgs.name(GROUP)
                    .makeStream()
                    .id(StreamMessageId.ALL));
        } catch (Exception ignored) {
            // 组已存在：BUSYGROUP
        }
    }

    private void dispatch(RStream<String, String> stream, StreamMessageId id, Map<String, String> msg) {
        String tid = msg.get("tid");
        String cid = msg.get("cid");
        String event = msg.get("event");
        try {
            Map<String, Object> payload = null;
            String payloadJson = msg.get("payload");
            if (payloadJson != null && !payloadJson.isBlank()) {
                payload = json.readValue(payloadJson,
                        json.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            }
            TenantContext.set(new TenantInfo(Long.parseLong(tid)));
            try {
                triggerService.fireEvent(Long.parseLong(cid), event, payload);
            } finally {
                TenantContext.clear();
            }
            stream.ack(GROUP, id);
        } catch (Exception ex) {
            // 消息损坏或执行失败：ACK 丢弃并告警，不阻塞后续消息（对齐原同步语义）。
            log.warn("事件队列消息处理失败 id={} tid={} cid={} event={}", id, tid, cid, event, ex);
            stream.ack(GROUP, id);
        }
    }
}