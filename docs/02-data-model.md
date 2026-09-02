# 数据模型

约定：全部业务表带 `tenant_id` 行级隔离；逻辑删除 `deleted`；时间戳 `created_at` / `updated_at`。

## 租户与配置

### tenant

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| name | varchar | 租户名 |
| status | varchar | active / suspended |
| settings | jsonb | 时区、默认频率上限等 |
| quota | jsonb | 每日触达上限、速率、成本预算 |
| created_at / updated_at | timestamptz | |

### channel_config

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| tenant_id | bigint | |
| channel | varchar | sms / email / push / wechat |
| config_encrypted | text | Jasypt 加密的通道凭据 |
| enabled | bool | |
| created_at / updated_at | | |

## 用户与画像

### contact

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| tenant_id | bigint | |
| external_id | varchar | 外部系统标识，unique(tenant_id, external_id) |
| phone | varchar | 允许空；unique(tenant_id, phone) |
| email | varchar | |
| push_token | varchar | |
| wechat_openid | varchar | 预留 |
| status | varchar | active / silent / unsubscribed |
| suppression | jsonb | 各通道退订 / 静默期配置 |
| created_at / updated_at | | |

### contact_attribute

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / contact_id | | |
| key | varchar | 属性名，含智能体分层标签（layer、churn_risk 等）|
| value | jsonb | 属性值 |
| updated_at | | |
| unique(tenant_id, contact_id, key) | | |

### contact_tag

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / contact_id / tag | | unique(tenant_id, contact_id, tag) |

## 人群

### audience

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id | | |
| name | varchar | |
| rule | jsonb | 圈选规则（结构化 DSL，见工作流引擎文档）|
| version | int | 规则版本 |
| status | varchar | draft / published |
| created_by / created_at / updated_at | | |

### audience_snapshot

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / audience_id | | |
| executed_at | timestamptz | 圈选时间 |
| member_count | int | 成员数 |
| status | varchar | building / ready |
| filter_version | int | 对应 audience.version，保证可追溯 |

### audience_snapshot_member

| 字段 | 类型 | 说明 |
|---|---|---|
| snapshot_id + contact_id | 联合主键 | 成员；量大时按 snapshot_id 分区 |

## 工作流

### workflow

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id | | |
| name / description | varchar | |
| status | varchar | draft / published / archived |
| version | int | 发布后改动升版本，旧版本执行不受影响 |
| created_by / published_at / created_at / updated_at | | |

### workflow_node

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / workflow_id / version | | |
| node_key | varchar | 节点唯一标识（画布内）|
| type | varchar | TRIGGER / CONDITION / AGENT_SPLIT / DELAY / ACTION / UPDATE / END |
| name | varchar | 节点名 |
| config | jsonb | 节点配置，见下 |
| position | jsonb | 画布坐标 |

触发节点 config：

```json
{
  "triggerType": "SCHEDULED | EVENT | MANUAL | API",
  "cron": "0 0 10 * * ?",
  "timezone": "Asia/Shanghai",
  "eventName": "ORDER_PAID",
  "eventFilter": {"op": "AND", "items": [{"field": "event.amount", "op": ">", "value": 100}]}
}
```

### workflow_edge

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / workflow_id / version | | |
| source_key / target_key | varchar | 边两端节点 |
| condition | jsonb | 出边条件（条件 / 分流节点的多出口判定）|
| unique(workflow_id, version, source_key, target_key) | | |

## 执行

### execution

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / workflow_id | | |
| workflow_version | int | 执行时的工作流版本 |
| trigger_type | varchar | SCHEDULED / EVENT / MANUAL / API |
| trigger_payload | jsonb | 触发上下文（事件数据、人员标识）|
| audience_snapshot_id | bigint | 定时 / 手动触发的快照；事件触发为空 |
| status | varchar | running / succeeded / failed / partial / canceled |
| started_at / finished_at / created_at | | |

### execution_node_state

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / execution_id | | |
| node_key | varchar | |
| status | varchar | pending / running / done / failed / skipped |
| attempt | int | 重试次数 |
| in_at / out_at | timestamptz | 节点进出时间 |
| output | jsonb | 节点输出（如分流结果）|
| unique(execution_id, node_key) | | |

### delivery_record

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / execution_id / node_key / contact_id | | |
| channel | varchar | sms / email / push |
| template_id | bigint | |
| content_snapshot | text | 渲染后的内容快照（合规留痕）|
| status | varchar | pending / sent / failed / blocked |
| channel_msg_id | varchar | 通道侧消息 ID（查回执）|
| provider / error / cost | | 成本核算 |
| sent_at / created_at | | |

幂等唯一键 `unique(tenant_id, contact_id, execution_id, node_key)` —— 同一用户同一执行同一节点只触达一次。

## 消息与审计

### message_template

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / channel | | |
| name | varchar | 模板名 |
| subject / body | text | 邮件主题 / 内容主体（FreeMarker 渲染）|
| vars | jsonb | 变量声明 |
| status | varchar | draft / published |

### event

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / contact_id | | |
| event_name | varchar | 如 ORDER_PAID、REGISTER |
| payload | jsonb | 事件数据（金额、商品等）|
| occurred_at / created_at | | |
| index(tenant_id, event_name, occurred_at) | | |

### validation_report（计划校验）

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / workflow_id | | |
| file_name / file_hash | | 上传文件与指纹（防重复校验）|
| status | varchar | parsing / checking / done / failed |
| report | jsonb | 分级校验报告全文（dimensions + decision）|
| decision | varchar | PASSED / WARNINGS / BLOCKED |
| created_by / created_at | | |

### audit_log（智能体决策审计）

| 字段 | 类型 | 说明 |
|---|---|---|
| id / tenant_id | | |
| agent | varchar | 分层 / 路由 / 流失预警 |
| model | varchar | 所用模型 |
| input_hash / input_summary | | 入参指纹与摘要（脱敏）|
| output | jsonb | 决策输出 |
| schema_valid | bool | schema 校验结果 |
| confidence | numeric | 置信度 |
| tokens / duration_ms / cost | | 用量与成本 |
| operator / created_at | | 触发人 / 时间 |