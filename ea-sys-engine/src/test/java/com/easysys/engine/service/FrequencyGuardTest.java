package com.easysys.engine.service;

import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 频率守卫（无 Spring；Redisson 用动态代理最小 stub）：
 * - user-window-hours > 0：SETNX 键已存在 → USER_RECENT；首次 → ALLOW
 * - user-window-hours <= 0：跳过用户级检查（关闭频控），仅 dailyCap 生效
 * - 超 dailyCap → DAILY_CAP
 */
class FrequencyGuardTest {

    private static final long TENANT = 1L;
    private static final long CONTACT = 42L;
    private static final String CHANNEL = "sms";

    private static final class Stub {
        boolean userKeyExists;
        boolean userCheckCalled;
        long dailyCount;
    }

    @Test
    void windowPositiveRejectsWhenUserKeyExists() {
        Stub s = new Stub();
        s.userKeyExists = true;
        FrequencyGuard guard = guard(s, 24, 10000);

        assertEquals(FrequencyGuard.Decision.USER_RECENT, guard.checkAndConsume(TENANT, CONTACT, CHANNEL));
        assertTrue(s.userCheckCalled);
    }

    @Test
    void windowPositiveAllowsWhenUserKeyFresh() {
        Stub s = new Stub();
        FrequencyGuard guard = guard(s, 24, 10000);

        assertEquals(FrequencyGuard.Decision.ALLOW, guard.checkAndConsume(TENANT, CONTACT, CHANNEL));
        assertTrue(s.userCheckCalled);
    }

    @Test
    void windowZeroSkipsUserCheckAndAllows() {
        Stub s = new Stub();
        s.userKeyExists = true; // 即便 Redis 键存在，窗口关闭也必须放行
        FrequencyGuard guard = guard(s, 0, 10000);

        assertEquals(FrequencyGuard.Decision.ALLOW, guard.checkAndConsume(TENANT, CONTACT, CHANNEL));
        assertFalse(s.userCheckCalled);
    }

    @Test
    void windowZeroStillEnforcesDailyCap() {
        Stub s = new Stub();
        FrequencyGuard guard = guard(s, 0, 2);

        assertEquals(FrequencyGuard.Decision.ALLOW, guard.checkAndConsume(TENANT, CONTACT, CHANNEL));
        assertEquals(FrequencyGuard.Decision.ALLOW, guard.checkAndConsume(TENANT, CONTACT, CHANNEL));
        assertEquals(FrequencyGuard.Decision.DAILY_CAP, guard.checkAndConsume(TENANT, CONTACT, CHANNEL));
        assertFalse(s.userCheckCalled);
    }

    private FrequencyGuard guard(Stub s, long windowHours, long dailyCap) {
        RBucket<String> bucket = (RBucket<String>) Proxy.newProxyInstance(
                RBucket.class.getClassLoader(), new Class<?>[]{RBucket.class}, (p, m, args) -> {
                    if ("trySet".equals(m.getName())) {
                        s.userCheckCalled = true;
                        return !s.userKeyExists;
                    }
                    return defaultValue(m.getReturnType());
                });
        RAtomicLong daily = (RAtomicLong) Proxy.newProxyInstance(
                RAtomicLong.class.getClassLoader(), new Class<?>[]{RAtomicLong.class}, (p, m, args) -> {
                    if ("incrementAndGet".equals(m.getName())) {
                        return ++s.dailyCount;
                    }
                    if ("expire".equals(m.getName())) {
                        return Boolean.TRUE;
                    }
                    return defaultValue(m.getReturnType());
                });
        RedissonClient redisson = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(), new Class<?>[]{RedissonClient.class}, (p, m, args) -> {
                    if ("getBucket".equals(m.getName())) {
                        return bucket;
                    }
                    if ("getAtomicLong".equals(m.getName())) {
                        return daily;
                    }
                    return defaultValue(m.getReturnType());
                });
        return new FrequencyGuard(redisson, dailyCap, windowHours);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return (byte) 0;
    }
}