package com.easysys.common.tenant;

/**
 * 租户上下文（ThreadLocal）。每个请求在过滤器中写入、finally 中清理，
 * 供 MyBatis-Plus 租户插件与业务代码读取。
 */
public final class TenantContext {

    private static final ThreadLocal<TenantInfo> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantInfo info) {
        HOLDER.set(info);
    }

    public static TenantInfo get() {
        return HOLDER.get();
    }

    public static Long tenantId() {
        TenantInfo info = HOLDER.get();
        return info == null ? null : info.tenantId();
    }

    /** 无租户上下文直接抛错：业务数据写入/读取必须发生在租户上下文中。 */
    public static Long require() {
        Long tid = tenantId();
        if (tid == null) {
            throw new IllegalStateException("租户上下文缺失：请求未携带租户信息");
        }
        return tid;
    }

    public static void clear() {
        HOLDER.remove();
    }
}