# 智能体设计（AgentScope Java 2.0）

## 1. 设计原则（四条硬线）

1. **LLM 定策略，引擎执行量**：人群可能十万级，LLM 不逐条决策、不搬运数据。智能体产出的是一份结构化分层 / 路由策略，由规则执行器批量落地。逐条 LLM 决策只用于单用户场景（事件触发）或小样本预跑。
2. **确定性兜底优先**：任何智能体调用失败 / 超时 / schema 不符 / 低置信，落到可配置的规则 fallback。核心场景（仅手机号 → 短信）本就是纯规则，不依赖 LLM —— 系统在 LLM 全挂时仍可运营。
3. **决策可审计、可回滚**：每次调用落 `audit_log`（入参摘要、输出、confidence、model、tokens、耗时、成本）。新策略 / 文案需人工审核闸门后才生效。
4. **PII 不出租户、不进模型**：传给 LLM 的是画像特征摘要（通道可用性、RFM 分、活跃度、价值分），绝不传手机号 / 邮箱 / 姓名原文。

## 2. 智能体全景

| Agent | 类型 | DAG 位置 | 决策粒度 | 模型档位 |
|---|---|---|---|---|
| 分层 Agent | 策略制定 | 人群圈选后 / 智能体分流节点 | 人群级（产出分层规则）| qwen-max（强推理）|
| 路由 Agent | 决策执行 | 智能体分流节点（单用户 / 小批量）| 用户级（逐条路由）| qwen-turbo（低延迟）|
| 流失预警 Agent | 策略分析 | 触发前预处理（M5 引入）| 人群级（风险分 ↔ 分层联动）| qwen-plus |
| 文案 Agent（可选）| 内容生成 | 动作节点的模板前置 | 模板级（生成变体 → 人工审核）| qwen-plus |
| 计划校验 Agent | 策略分析 | 发布前置（导入计划文件比对工作流）| 计划级（逐条规则比对）| qwen-plus |

## 3. 分层 Agent（核心）

**职责**：把圈定人群按运营指定的维度动态划分。核心场景即「通道可用性优先分层」：仅手机号 / 仅邮箱 / 双通道 / 无可用通道。

**输入**（人群画像摘要，非全量数据）：

```json
{
  "audience": "近30天活跃未复购",
  "size": 103420,
  "dimensions": ["channel_availability", "value_tier", "churn_risk"],
  "profile_summary": {
    "channel_availability": {"sms_only": 0.31, "email_only": 0.08, "multi": 0.59, "none": 0.02},
    "value_tier": {"high": 0.12, "mid": 0.45, "low": 0.43},
    "avg_ltv": 486.5,
    "avg_inactive_days": 12.3
  },
  "constraints": {"max_layers": 8, "min_layer_size": 1000}
}
```

**输出**（JSON Schema 强约束）：

```json
{
  "strategy_version": "2026-09-02-01",
  "layers": [
    {
      "id": "L1",
      "name": "双通道-高价值",
      "rule": {"channel_availability": "multi", "value_tier": "high"},
      "route_order": ["sms", "email"],
      "priority": 1,
      "confidence": 0.92,
      "rationale": "高价值双通道用户，短信先行保证到达，邮件次日跟进"
    }
  ],
  "fallback_rule": {"channel_availability": "sms_only", "route_order": ["sms"]},
  "auditable": true
}
```

**落地路径**：策略 → 编译为规则 → `StrategyExecutor` 批量对快照成员打分 → 分层标签写回 `contact_attribute`（如 `layer=L1`）→ 后续 DAG 分流节点按标签路由。分层结果持久化，供审计与复用。

**降级**（LLM 不可达）：租户配置的默认分层（通道优先，双通道按 DAG 顺序）直接生效，执行不中断。

## 4. 路由 Agent

**职责**：智能体分流节点内，对单个用户做通道决策 —— 结合当前时刻、最近触达史、退订 / 静默状态，微调分层规则给定的顺序。

**输入**：

```json
{
  "user": {"id": "****", "layer": "L1", "channels": ["sms", "email"]},
  "recent_touches_24h": [
    {"channel": "sms", "hours_ago": 3, "workflow": "W17"}
  ],
  "suppression": {"sms_remaining_today": 2, "in_quiet_period": false},
  "dag_intent": "promote_repurchase"
}
```

**输出**：

```json
{
  "route_order": ["email"],
  "skip": true,
  "skip_reason": "3小时前已短信触达，今日短信额度优先保留",
  "confidence": 0.88
}
```

**决策粒度规则**：事件 / API 触发的单用户流 → 逐条 LLM 决策；定时触达大批量 → 只在**小样本预跑**（≤ 200 人）用 LLM 校验分层策略，正式执行走缓存的分层标签，避免十万次 LLM 调用。

**降级**：`recent_touches_24h` 非空的用户按「已触达通道后置」的纯规则重排，其余按分层既定顺序。

## 5. 流失预警 Agent（M5 引入）

输入：用户行为序列摘要（近 30 / 90 天访问、下单、会话深度）+ 区间留存率。输出：

```json
{"churn_risk": 73, "tier": "HIGH", "drivers": ["30天未活跃", "连续2周无会话"]}
```

产出回写 `contact_attribute.churn_risk` 与分层联动（高流失人群优先触达、权重提高）。降级：规则版「N 天未活跃 = HIGH」。

## 6. AgentScope Java 2.0 集成

- **框架承载点（M6 已落地）**：主提供方逻辑以确定性 `RuleModel`（extends `ChatModelBase`）或 LLM 模型位经 `HarnessAgent` 执行，输出 JSON 由框架 native 结构化路径解析，多租户隔离由 `RuntimeContext(userId=tenantId, sessionId=action)` 承载。`HarnessAgent` 在此之上承载框架执行（模型位装配 / 会话隔离 / 超时），`AgentPolicy` 提供合规编排（硬 schema 校验 / 置信度闸门 / fallback / 审计构造）。
- **LLM 模型位（M6 延后）**：确定性 `RuleModel` 与真实 LLM 共用同一模型位，M6 接入时经 `agentscope-extensions-model-*` 注册，编排 / 审计 / 降级链路零改动。工具集（`ProfileSummaryTool` 画像摘要带租户 RLS 过滤脱敏、`TouchHistoryTool` 近 N 天触达史、`ChannelStatusTool` 通道可用性 / 配额余量）留待 LLM 里程碑注册。
- **结构化输出**：schema 语义校验由 `AgentPolicy` 承担（networknt 2.0.0 `SchemaRegistry`，实测框架 native 结构化路径只做 JSON 解析、不做 enum/required/minItems 语义校验），非法输出直接触发 fallback 分支，不进入结果分发。
- **多租户隔离**：AgentScope 2.0 原生 multi-tenant session（`RuntimeContext.userId`）与租户上下文（`TenantContext`）绑定，缺失时（纯规则执行 / 单测）兜底 `anonymous`；prompt 内禁止跨租户数据；工具调用全部经租户过滤器
- **统一执行封装**：`HarnessAgent`（框架执行）+ `AgentPolicy`（合规编排）封装「框架调用（确定性 / LLM 模型位）→ schema 校验 → 置信度阈值（< 0.7 走 fallback）→ 审计构造 → 结果分发」，Agent 业务与治理解耦。批处理 harness 单次迭代（`maxIters(1)`）+ 全工具裁剪、无状态（`disableSessionPersistence`）；超时由调用方 `AgentRunConfig.timeoutMs` 传入 `call().block()` 承担。装配期 LLM resolve 失败即确定性回退（启动不中断），运行时 LLM 故障经 `provider_error` 落入 fallback —— 两条降级路径执行均不中断、审计均落 `audit_log`

## 7. 治理清单

| 项 | 机制 |
|---|---|
| 输出可信 | JSON Schema + 置信度阈值 0.7 + 断言（分层并集 = 人群全集且不相交）|
| 失败兜底 | 超时 / 重试 2 次 → 规则 fallback，告警但不中断执行 |
| 成本 | 分层策略一次 qwen-max；逐条路由限量（租户配额）；批量走规则引擎 |
| 人工闸门 | 模板变体、新分层策略需运营审核发布；策略 `strategy_version` 可回滚 |
| 审计 | `audit_log`：入参摘要 / 输出 / schema 结果 / 置信度 / model / tokens / 耗时 / 成本 / 操作人 |
| 隐私 | 特征摘要进模型，PII 只存本地库；prompt 无手机号 / 姓名 |

## 8. 全流程时序（双通道场景）

```mermaid
sequenceDiagram
    participant S as 调度器(Cron)
    participant E as 人群引擎
    participant L as 分层Agent
    participant X as 策略执行器
    participant W as 工作流引擎
    participant R as 路由Agent
    participant A as 通道适配器
    S->>E: 定时触发, 圈选快照(10万人)
    E->>L: 画像摘要 + 分层维度
    L-->>E: 分层策略(schema 校验通过, conf 0.92)
    E->>X: 编译规则, 批量打标
    X-->>E: 仅手机30% / 双通道59% / 仅邮箱8% / 无通道2%
    E->>W: 按分层执行DAG
    W->>R: 双通道高价值分支 (小样本质检)
    R-->>W: 校验通过, 按 L1 顺序执行
    W->>A: 短信(仅手机+双通道) → 记录回执
    W-->>W: 延迟24h节点
    W->>A: 邮件(双通道跟进) → 记录回执
    rect rgb(240,240,240)
    Note over L,R: 全程: 决策落 audit_log
    end
```

## 9. 计划解析与校验 Agent（计划导入校验）

**职责**：运营人员在前端上传运营计划 / 规则文件（Excel / CSV / Word / PDF / Markdown），系统解析出结构化计划，与当前工作流执行配置逐条比对，判断计划中的智能触达安排是否与执行流程一致，输出分级校验报告。

**校验流水线**：

```mermaid
flowchart LR
    F[文件上传<br/>≤10MB 类型白名单] --> P[文件解析器<br/>POI / 文本抽取]
    P --> A[计划解析Agent<br/>抽取结构化计划]
    A --> C[一致性校验器]
    C --> R[分级校验报告<br/>PASSED / WARNINGS / BLOCKED]
```

| 环节 | 方式 | 说明 |
|---|---|---|
| 文件解析器 | 确定性 | Excel/CSV 用 POI 解析为表格再文本化；Word/PDF/Markdown 抽文本；扫描版 PDF（OCR）首版不支持 |
| 计划解析 Agent | LLM (qwen-plus) | 解析出 `{trigger, audience, channels, route_order, timing, frequency, copy_notes}` |
| 一致性校验器 | 混合 | 确定性比对优先 + LLM 语义比对补充 |

**比对维度**：

| 维度 | 比对方式 | 级别示例 |
|---|---|---|
| 触发 | 确定性 | 计划 Cron / 时点 vs 工作流触发配置 |
| 通道 | 确定性 | 计划要求的通道未启用 → BLOCKED |
| 路由顺序 | 确定性 | 计划「短信→邮件」 vs DAG 出边顺序 |
| 时序 / 延迟 | 确定性 | 动作节点间隔 vs 计划时间表 |
| 频率合规 | 确定性 | 计划频率 > 租户配额 / 触碰静默期 → BLOCKED |
| 人群定义 | LLM 语义 | 计划「高价值活跃」 vs 圈选规则 |
| 文案要求 | LLM 语义 | 变量 / 签名 / 调性与模板配置 |

**校验报告输出（节选）**：

```json
{
  "plan_summary": "7天未复购促活：周一早9点短信，次日邮件跟进",
  "dimensions": [
    {
      "name": "channel",
      "level": "BLOCKED",
      "plan": "双通道：短信 + 微信",
      "workflow": "仅启用：sms, email",
      "detail": "计划要求微信通道，当前通道配置未启用且未接入",
      "suggestion": "调整计划移除微信通道，或启用微信通道配置"
    }
  ],
  "summary": {"conflicts": 1, "warnings": 2, "passed": 5},
  "decision": "BLOCKED"
}
```

**发布闸门**：`decision=BLOCKED` 阻止发布（需调整计划或工作流）；`WARNINGS` 可发布但展示警告；强制发布需审批并留审计。

**安全与治理**：上传文件是外部输入 —— 类型白名单 + 大小限制 + 文件内容只作文本数据（不进提示词指令区），输出仍受 schema 校验，防提示词注入；报告持久化（`validation_report`）可回看；解析与比对全程落 `audit_log`。

**降级**：LLM 解析失败 → 提示文件格式问题或退回纯确定性解析（Excel 结构化列直接映射）；LLM 不可达不阻断发布，仅降级为确定性校验。

## 10. 工作流对话创建 + HITL 确认（AI 创建）

**职责**：运营人员在画布页发起对话式创建，AI 逐项查询通道 / 模板 / 人群并与用户确认后生成工作流 DAG 草稿，前端载入画布人工校对后保存。生成不自动落库、人群不自动创建（未匹配仅提示）。

**承载**：`HarnessAgent`（name=workflow-dialogue）+ 确定性 `WorkflowDialogueModel`（无 LLM 可跑；模型位接入任何 LLM 同构零改动，HITL 是框架级能力）。

```mermaid
sequenceDiagram
    participant V as 画布页(前端)
    participant C as WorkflowAiController
    participant A as HarnessAgent
    participant P as WorkflowDialoguePolicy
    V->>C: POST /api/workflows/ai-chat (SSE)
    C->>A: streamEvents([msg], RuntimeContext)
    A->>P: WorkflowDialogueModel 轮询 decide(history)
    P-->>A: Reply/Query(带3个查询工具) / Draft(plan_workflow)
    A-->>C: SSE 事件流(文本增量/工具状态)
    C-->>V: TEXT_BLOCK_DELTA / TOOL_CALL_START / TOOL_RESULT_*
    alt plan_workflow 需人工确认
        A-->>C: RequireUserConfirmEvent(挂起工具调用)
        C-->>V: REQUIRE_USER_CONFIRM(前端确认卡, 输入框禁用)
        V->>C: POST {confirm:{confirmed:true|false}}
        C->>A: ConfirmResult(METADATA_CONFIRM_RESULTS)
        A-->>C: 恢复执行 → TOOL_RESULT_* / AGENT_RESULT
        C-->>V: draft_ready(草稿卡) 或「已取消」文本
    end
    V->>V: 载入画布(人工校对) → 保存
```

**策略决策**（WorkflowDialoguePolicy，确定性规则，分支顺序）：

1. 末词命中取消词 → 回复「已取消，可继续补充需求」；
2. 需求文本画像齐备（触发显式 + 人群已表达）→ Draft（plan_workflow）；
3. 缺触发只追问触发；缺人群引用已检索人群前 5 名；均缺先跑 3 个查询工具。

**HITL 确认闸门**：模型输出 `ToolUseBlock(plan_workflow)` → 框架 `checkPermissions` 返回 ASK → `RequireUserConfirmEvent`；controller 缓存原工具调用块（session 维度 pendingAsks），确认消息走 `Msg` 元数据通道（不落对话上下文），恢复执行不重走模型输出工具。挂起期间前端禁输入框，后端对未确认消息返回 400 防御。

**SSE 事件语义**：统一 `{type: AgentEventType.name()}`（大写枚举名），关键事件：`TEXT_BLOCK_DELTA`（打字机增量）、`TOOL_CALL_START` / `TOOL_RESULT_TEXT_DELTA` / `TOOL_RESULT_END`（工具行状态）、`REQUIRE_USER_CONFIRM`（确认卡）、`USER_CONFIRM_RESULT`（清除确认态；拒绝时前端回标挂起工具行「已取消」）、`AGENT_RESULT`（结语）、自定义 `draft_ready`（后端从 plan_workflow 工具输出增量重建草稿 JSON）。

**数据流与安全**：租户上下文 `TenantContext` 为 ThreadLocal，工具线程经 `RuntimeContext` 类型化属性注入（`withTenant + Mono.defer`）；对话会话状态第一版用 JsonFileAgentStateStore（生产可切 agentscope-extensions-redis 的 RedisAgentStateStore）；工作流草稿不落库，人工保存走既有审计。

## 11. AI 智能客服（悬浮窗助手）

**职责**：全站右下角悬浮 UI 提供统一运营助手：知识库问答（文档上传 + 检索引用）、运营数据问答（到达率 / 留存率 / 漏斗 / 工作流效果）、人群检索、工作流触发（HITL 人工确认）、工作流创建（切换到既有对话创建会话）、通用闲聊。与「工作流对话创建」共融而非重复实现：创建工作直接复用 `WorkflowAiController` 会话能力。

**承载**：`HarnessAgent`（name=assistant）+ 确定性 `AssistantModel`（无 LLM 全功能可跑；模型位接入 LLM 同构零改动）。入口 `AssistantController`：

| 端点 | 说明 |
|---|---|
| `POST /api/assistant/ai-chat` | SSE 流式对话（assistant 会话） |
| `GET /api/assistant/documents` | 知识库文档列表（状态 / 分块数） |
| `POST /api/assistant/documents` | 上传文档（≤10MB，同步解析入库） |
| `DELETE /api/assistant/documents/{id}` | 删除文档（软删文档行 + 物理删分块） |

**策略决策**（AssistantPolicy，确定性规则，分支顺序）：取消词 → 不调工具直接收尾；知识库命中 → 摘要 + 引用卡；stats / 人群 / 工作流列表意图 → 各查询工具并出卡；创建意图 → `begin_workflow_dialogue` 工具，成功后发自定义事件 `switch_workflow_dialogue`，前端切会话并自动发起创建；触发意图 → `search_workflows` 必经 + `trigger_workflow`（框架 ASK → HITL 确认卡）；其余 → 直接文本回复（可闲聊）。

**RAG 确定性检索**（无嵌入模型）：文档同步解析分块入 `kb_document_chunk`（CJK 分词 + 词频 JSONB `tokens`，`JsonbTypeHandler` 落库）；检索 = 语义无关的「CJK 分词 → JSONB 词频交集预筛（`jsonb_exists_any`，MyBatis 无法透传 `?` 运算符，GIN 索引留待原生 SQL 检索层）→ Java BM25 打分 → top-3 引用」。引用逐条含原文段落与相关度，前端以知识库卡展示。

**SSE 事件与卡片**：复用 `{type}` 枚举事件（TEXT_BLOCK_DELTA / TOOL_CALL_* / REQUIRE_USER_CONFIRM / USER_CONFIRM_RESULT / AGENT_RESULT），另加自定义帧：`assistant_card {kind: kb|stats|audiences|workflows|trigger, data}`（controller 在工具结果终态按工具名映射下发，前端按 kind 渲染卡片：引用 / 统计表 / 人群列表 / 工作流快捷触发 / 触发结果）、`switch_workflow_dialogue`。工作流创建会话内的 `draft_ready` 草稿经 localStorage 中转，画布页 onMounted 消费并 `applyAiDraft()` 载入。

**HITL 闸门**：`trigger_workflow` 与创建工作流的 `plan_workflow` 同走框架 ASK → `RequireUserConfirmEvent`；挂起期间前端禁输入、仅确认 / 取消，未确认消息后端 400 防御；确认经 Msg 元数据通道传递，不污染对话上下文（恢复执行不重走模型输出）。真实触发走既有执行链路（画布 AUDIENCE 人群节点为批量成员来源），失败类目以 `error` 字段透出。

## 12. 驾驶舱（图谱管理 + 监控总览）

**职责**：两张页面补全 Agent 基础设施的可观测性 —— 图谱管理页对八类知识领域（ONTOLOGY / WORKFLOW / AUDIENCE / CHANNEL / TEMPLATE / TRIGGER / RULE / MONITOR）做登记与状态管理；监控总览页聚合 LLM 调用（token / 调用 / 成本 / 延迟 / 降级率）并给出确定性系统洞察（不引入图数据库，登记即状态清单）。

**承载**：`HarnessAgent`（name=cockpit）+ 确定性 `CockpitInsightModel`（洞察 = 规则聚合，无 LLM/网络/随机）。新 `AgentType.COCKPIT`，入口 `CockpitController`：

| 端点 | 说明 |
|---|---|
| `GET /api/cockpit/overview` | 总览：llm 聚合（byAgent / byModel / 近 7 天 trend）+ 图谱状态（模块统计）+ 知识库 / 记忆 / Agent 目录（LLM 状态） |
| `GET /api/cockpit/graph?module=` | 某领域登记清单（内置 25 项 + 用户登记，同 key 用户行覆盖内置） |
| `POST /api/cockpit/graph` | 新建登记（payload 透传 JSONB） |
| `PUT /api/cockpit/graph/{id}` | 编辑用户登记（内置行 id=null 不可编辑） |
| `PATCH /api/cockpit/graph/{id}/status` | 启用 / 停用（仅用户行） |
| `DELETE /api/cockpit/graph/{id}` | 删除用户登记 |
| `GET /api/cockpit/insights?force=` | 确定性洞察（缓存 300s；force=1 强制重新生成，经 AgentPolicy 链路 ~秒级） |
| `GET /api/cockpit/llm-traces?limit=` | LLM 调用追踪（agent_audit 聚合，最近 N 条） |

**总览聚合**：llm 卡片聚合 `agent_audit` 中 LLM 各状态（success / fallback / error）的调用数、token、成本、平均耗时与降级率；`easysys.agent.llm.enabled=false` 时 `schemaValidRate=1.0`、模型位显示 `deterministic`，总览仍完整可读。图谱统计按内置目录 + 用户覆盖行的启用状态汇总，前端以 8 域 Tab + 表格管理，builtin 行只读展示来源 / 状态。

**LLM 用量明细 + 上下文构成（`llm_usage` 表，V13）**：M8 后驾驶舱 LLM 卡补六项指标 —— 总 Token / 提问轮次 / 调用 / 输入 Token / 输出 Token / 缓存命中 Token，以及「上下文构成」（窗口内最近一次对话模型输入：系统提示词 / 工具 Schema / 用户消息 / 助手消息 / 注入上下文 / 工具结果 六类的条目数 + Token + 占比）。

- **采集点**：`LlmUsageMiddleware`（挂载于 assistant / workflow-dialogue / 五路批处理 agent，`onModelCall` 同 OtelTracingMiddleware）：pre 调共享估算类 `LlmContextEstimator.compose(messages, tools)` 生成六类构成 JSON（中间件与驾驶舱共用同一实现防口径漂移）；doOnNext 捕获 `ModelCallEndEvent.getUsage()` 真实 token；仅当 usage.total > 0 记账（确定性模型 usage 恒 0 → 不写行，测试环境零影响）。同一次调用闭环记一次：ReAct 工具循环多轮 = 多次 upsert 累加 `calls`，`context` 覆盖为最后一次调用输入。`markRound` 在聊天入口无条件执行（LLM 禁用也写轮次行）→ `llm_usage` 表同时是聊天会话台账，构成查询期据其定位最近会话。
- **表结构**：`llm_usage(id, tenant_id, agent_type, session_id, calls, rounds, input_tokens, output_tokens, cached_tokens, context jsonb, created_at, updated_at)`，`UNIQUE(tenant_id, agent_type, session_id)`，每会话一行 upsert（calls +1 / token 累加 / context 覆盖）。
- **口径**：轮次 = 聊天（ai-chat / workflow-dialogue）请求数，控制器入口 markRound，批处理不记轮次；构成 token 为字符折算估算（chars/2.5 + overhead，与框架 compaction 同启发式），UI 标注「估算」，占比分母 = 构成总和；注入上下文以 synthetic 元数据判定优先于角色。
- **上下文构成主源 = 查询期 AgentState 实时派生**：overview 时按 `llm_usage` 最近 7 天聊天会话行（agent_type ∈ assistant / workflow-dialogue）定位 session_id → 对应 HarnessAgent delegate `getAgentState(tenantId, sessionId)` 取实时转录 → 剔末尾 ASSISTANT 最终回复（不在任何模型输入内；中间 assistant tool_use 消息属于后续输入保留）→ `LlmContextEstimator.compose` 实算；工具 Schema = agent 注册 `toolkit.getToolSchemas()` 全量。查得到状态即展示真实转录构成；状态缺失/会话过期回退 `llm_usage.context` 快照（快照本身仍由中间件预写，双源口径一致）。
- **防双计合并**：overview 的 `calls` = audit 批处理 + llm_usage 聊天通道（assistant / workflow-dialogue，SQL FILTER 拆出）；`sumTokens` = audit + llm_usage 输入 + 输出（LLM 模式下审计 tokens 恒 null 不双计）；轮次 / 输入 / 输出 / 缓存命中 = llm_usage 全通道 SUM；上下文 = 最近一次聊天调用（批处理 digest 不污染对话上下文）；速率 / 错误率 / 降级率 / 耗时 / 成本 / 成功降级等仍保持 audit 口径。

**洞察（CockpitInsightModel）**：健康度 = 各维度加权分（LLM 错误率 / 降级率、图谱启用比例、审计完整性等），输出 `overallHealth + insights[] {level: critical|warning|info, dimension, detail, suggestion}`；全确定性，测试断言健康度区间与 insight 条目。

## 13. 评测中心（数据集 + 内置评测器 + 批量运行）

**职责**：数据集（scope=llm_call，mode=openjudge / execute，execute 携带被测智能体 agent_type）+ 用例管理 + 15 个内置评测器批量运行 → 各评测器均值 → 报告落库（jsonb）+ 审计。评测器是代码常量内置目录（规则 9 + LLM-Judge 6），不落表。openjudge 用预置响应判分；execute 由 service 真实运行被测智能体（assistant / workflow-dialogue，`ReActAgent.call` 同步 + 30s 超时），注入实际回复与工具调用轨迹（`actual_tool_calls` / `actual_steps`）后走同一判分链路。

**承载**：`HarnessAgent`（name=evaluation）+ 确定性 `EvaluationModel`（判分全确定性：规则算法实现；LLM-Judge 在 `easysys.agent.llm.enabled=false` 时走确定性近似降级，测试不依赖 LLM）。新 `AgentType.EVALUATION`，入口 `EvaluationController`：

| 端点 | 说明 |
|---|---|
| `GET /api/evaluations/datasets` | 数据集列表（含 caseCount） |
| `POST /api/evaluations/datasets` | 新建（scope=llm_call，mode=openjudge/execute，agent_type=assistant/workflow-dialogue，status） |
| `PUT /api/evaluations/datasets/{id}` | 编辑（mode / status 可改） |
| `DELETE /api/evaluations/datasets/{id}` | 删除（级联软删用例 + 报告） |
| `GET /api/evaluations/datasets/{id}/cases` | 用例列表（seq 升序） |
| `POST /api/evaluations/datasets/{id}/cases` | 新增用例（seq 缺省 = max+1） |
| `PUT /api/evaluations/datasets/{id}/cases/{caseId}` | 编辑用例 |
| `DELETE /api/evaluations/cases/{id}` | 删除用例 |
| `POST /api/evaluations/run` | 批量运行：`{datasetId, evaluators?}`（缺省 = 全量 15；openjudge 用预置响应判分，execute 真实运行被测智能体） |
| `GET /api/evaluations/reports` | 报告列表（摘要列） |
| `GET /api/evaluations/reports/{id}` | 报告详情 |
| `DELETE /api/evaluations/reports/{id}` | 删除报告 |

**评测目录（与 `EvaluationModel` 常量逐字对齐）**：规则 `number_accuracy` / `string_exact` / `response_repetition` / `text_similarity` / `observation_information_gain` / `tool_call_accuracy` / `task_success` / `step_efficiency` / `policy_compliance`；LLM-Judge `llm_correctness` / `llm_instruction_following` / `llm_relevance` / `llm_hallucination` / `llm_reasoning_groundedness` / `llm_response_completeness`。四个执行维度评测器参考通用 agent 评测指标：工具调用正确性（期望工具名精确命中 0.5 / 参数全匹配 1.0，有轨迹但未调用 0）、端到端任务成功（期望数字全集命中或文本 bigram 相似度 ≥0.8）、步骤效率（min(1, 期望步数/实际步数)）、策略合规（`expected_policy=[{keyword,prohibit}]` 必备/禁区词，任一违规 0）。模型入参会静默丢弃不在 `ALL_METRICS` 的 metric，前端目录必须与之对齐。

**判分与报告**：判分对象 `actual_response`（openjudge 由 service 复制 provided_response；execute 注入真实回复与轨迹；空 = 不适用 null，不计均值）；单用例得分 ≥0.8 记通过；`metrics[] {metric, category, avg_score, passed_count, applicable_count}` 只含适用用例数 >0 的评测器，无适用用例的评测器不进 metrics、仅以 `findings[]` INFO 发现列出（jsonb 原文 snake_case），`summary {score, verdict}`（≥80 PASS / ≥60 WARN / 其余 FAIL），`findings[]` 含 INFO / WARNING / BLOCKED（均值 <0.6）分级与修复建议。报告落 `evaluation_report`（jsonb 原样透传），顶层 `testedCases/totalCases` 走 JavaBean 序列化驼峰。execute 单用例失败 / 超时 / 空回复不整轮报错，该用例判分不适用（INFO），审计形状与 openjudge 一致（同一 `AgentPolicy.run` + `persistAudit`）。

**审计约定**：图谱 / 数据集 / 用例 / 报告的全部写操作（create/update/delete/run）均写入 `agent_audit`（action = 端到端动作名，schema 合规即有效），运行评测触发完整 HarnessAgent 链路（超时 / 降级兜底齐全）；前端 `POST /run` 前检查数据集 enabled、用例数 >0，execute 模式同样可运行（数据集对话框选择被测智能体）。