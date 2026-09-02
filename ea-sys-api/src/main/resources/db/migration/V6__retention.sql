-- M5：留存看板 + 流失预警
-- event：用户行为事件（访问/下单/会话等），留存曲线与流失预警的活跃信号源。
--   幂等：同一 (tenant, contact, event_name, occurred_at) 重复上报视为同一事件（ON CONFLICT DO NOTHING）。
-- 看板指标（漏斗/区间留存/渠道效果）由 audience_snapshot/execution/delivery_record/event 聚合得出，不建物化表。

CREATE TABLE event (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant (id),
    contact_id  BIGINT       NOT NULL REFERENCES contact (id),
    event_name  VARCHAR(64)  NOT NULL,
    payload     JSONB,
    occurred_at TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_event_dedup UNIQUE (tenant_id, contact_id, event_name, occurred_at)
);

CREATE INDEX idx_event_tenant_name_time ON event (tenant_id, event_name, occurred_at);
CREATE INDEX idx_event_contact_time ON event (tenant_id, contact_id, occurred_at);

COMMENT ON TABLE event IS '用户行为事件（活跃信号，幂等去重）';