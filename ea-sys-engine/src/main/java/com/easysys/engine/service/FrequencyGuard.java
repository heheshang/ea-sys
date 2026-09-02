package com.easysys.engine.service;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 触达频率控制（Redisson）：
 * - 用户级近期触达去重：同一 (contact, channel) 在 userWindowHours 内只允许一次，
 *   防跨执行重复打扰（试算与真实执行共用，跨执行幂等的事实来源）；
 * - 租户级每日总量：dailyCap 次/日，超限拒绝（次日键自然过期）。
 * 键均带 TTL，Redis 无需定期清理。
 * @Lazy：Redisson 仅在真实下发时连 Redis；干跑/画布管理不依赖 Redis，
 * 避免无 Redis 环境（如仅干跑的测试）启动即连接失败。真实执行时 Redis 必须在线。
 */
@Service
@Lazy
public class FrequencyGuard {

    public enum Decision {
        ALLOW("allow"),
        USER_RECENT("userRecent"),
        DAILY_CAP("dailyCap");

        private final String label;

        Decision(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String USER_KEY = "easysys:freq:user:%s:%s:%s";
    private static final String DAILY_KEY = "easysys:freq:daily:%s:%s";

    private final RedissonClient redisson;
    private final long dailyCap;
    private final long userWindowHours;

    public FrequencyGuard(RedissonClient redisson,
                          @Value("${easysys.frequency.daily-cap:10000}") long dailyCap,
                          @Value("${easysys.frequency.user-window-hours:24}") long userWindowHours) {
        this.redisson = redisson;
        this.dailyCap = dailyCap;
        this.userWindowHours = userWindowHours;
    }

    /** 幂等检查 + 消费额度：ALLOW 才可下发；USER_RECENT/DAILY_CAP 由调用方计入 skipped。 */
    public Decision checkAndConsume(Long tenantId, Long contactId, String channel) {
        // 用户级去重（SETNX 语义）：已存在 → 近期触达过，拒绝
        RBucket<String> user = redisson.getBucket(String.format(USER_KEY, tenantId, contactId, channel));
        if (!user.trySet("1", userWindowHours, java.util.concurrent.TimeUnit.HOURS)) {
            return Decision.USER_RECENT;
        }
        // 租户级每日总量（首次计数时设定当日 TTL，值 >1 说明 TTL 已就位）
        RAtomicLong daily = redisson.getAtomicLong(String.format(DAILY_KEY, tenantId, LocalDate.now(ZONE)));
        long count = daily.incrementAndGet();
        if (count == 1) {
            daily.expire(Duration.between(LocalDateTime.now(ZONE), LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE)));
        }
        if (count > dailyCap) {
            return Decision.DAILY_CAP;
        }
        return Decision.ALLOW;
    }
}