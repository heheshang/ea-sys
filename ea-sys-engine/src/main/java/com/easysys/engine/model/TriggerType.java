package com.easysys.engine.model;

public enum TriggerType {
    SCHEDULED,
    EVENT,
    MANUAL,
    API;

    /** 严格名解析；非法/空 → null（校验层据此报配置错误）。 */
    public static TriggerType of(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
