# 98 现有评测能力地图（Evaluation 域：agent 侧 + api 侧 + web 侧）

> 说明：`ea-sys-web/src/api/evaluation.ts`、`docs/04-agent-design.md` §13 已存在，本文是补齐可直接用于规划/改造的**单项能力视角**地图。行文引用以当前代码为准，标注与既有文档的出入。
> 评测域近期提交：`42b85f3`（LLM 判分并行化，全量 run 约 33s）、`0aad28d`（run 超时修复，nginx 600s）、`3620de4`（对齐：导入/轮次/自定义/TraceID）、`20b8a51`（execute 真实执行 + 四执行维度评测器）。评测中心**整体可用、已在用**（非实验）。
>
> 状态（V16 后端重构落地，见 docs/99 实施记录）：异步任务 `evaluation_task` 状态机 + 202 Accepted、LLM-Judge 结构化 `{score,reason}` 逐样本落库（含多轮离散度）、报告对比 compare、评测目录 catalog、CRUD/任务审计补齐。本文 A1「审计落库」、D2.2「仅 run() 落审计」、D4.2 已同步更新；H5/H7/M3/M4 留 roadmap。

---

## A. 关键能力（文件 → 能力 → 公开接口）

### A1. 评测运行核心

| 能力 | 承载文件 | 代码形态 | 公开接口 |
|---|---|---|---|
| 评测 Agent 托管（HarnessAgent：超时/降级兜底） | `ea-sys-agent/src/main/java/com/easysys/agent/AgentPolicy.java`、`HarnessAgent.java`、`EvaluationModel.java`；api 侧装配 `config/HarnessAgentConfig.java` | `HarnessAgent`（name=`evaluation`）+ Deterministic `EvaluationModel` | `POST /api/evaluations/run` |
| 批量运行编排（查数据集→逐用例判分→报告落库→审计） | `ea-sys-api/.../service/EvaluationService.java` | `run()`（`@Transactional`） | `POST /api/evaluations/run` |
| LLM-Judge 打分（真实 LLM，并行化、轮次均值） | `ea-sys-api/.../service/LlmJudgeScorer.java` | 按用例真实调用 `easysys.agent.llm` 模型，`blockFirst()` 取回，正则 `\b\d{1,3}\b` 解析 0-100，多轮均值注入 `judge_scores.{metric}` | 无独立端点，随 run |
| LLM 用量落账（中间件采集） | `service/LlmUsageService.java` + `middleware/LlmContextEstimator.java` + `db/migration/V13__llm_usage.sql` | `recordCall/markRound` upsert `llm_usage`（每 `(tenant_id, agent_type, session_id)` 累计 calls/rounds/tokens，context 覆盖快照；确定性模式不写行） | 无独立端点，驾驶舱 `GET /api/cockpit/overview` 聚合展示 |
| 审计落库 | `EvaluationService.persistAudit()` + `writeAudit()` → `AgentAudit`/`agent_audit` | `run()` 触发 `evaluation_run`；数据集/用例/导入/自定义/任务 create-cancel 全写审计（EVALUATION_*_CREATE/_UPDATE/_DELETE、EVALUATION_IMPORT、EVALUATION_TASK_CREATE/_CANCEL，异步任务 run 审计只写一次且为最后一条）；`input_summary` 顶层含 `trace_id`/judge 信息 | 无独立端点 |
| TraceID 联动驾驶舱 | post/run 生成 `eval-`+8hex traceId → 报告 `evaluation_report.trace_id` + 判分输入顶层 `trace_id`；会话 sessionId=`traceId-seq` | 驾驶舱 `GET /api/cockpit/llm-traces?trace=` 文本键值匹配过滤 | 报告详情「查看会话追踪」跳驾驶舱挂过滤标签 |

### A2. 数据与用例管理

| 能力 | 承载文件 | 公开接口 | 前端入口 |
|---|---|---|---|
| 数据集 CRUD（scope=llm_call；mode=openjudge/execute；execute 带 agent_type） | `entity/EvaluationDataset.java`、`dto/evaluation/DatasetView.java`、`EvaluationService` | `GET/POST /api/evaluations/datasets`、`PUT/DELETE …/{id}` | `views/EvaluationView.vue` ①数据集卡片（预览/导入 jsonl/用例/编辑/删除） |
| 用例 CRUD（seq 升序；字段 question/system_prompt/expected_output/tool_schema/expected_tool/expected_steps/expected_policy/provided_response） | `entity/EvaluationCase.java`、`dto/evaluation/CaseView.java` | 列表 `GET …/datasets/{id}/cases`；新增 `POST …/cases`（seq 缺省=max+1）；编辑 `PUT /api/evaluations/cases/{id}`；删除 `DELETE /api/evaluations/cases/{id}` | 用例抽屉 + 用例表单对话框 |
| jsonl 批量导入（text/plain：逐行 JSON 或整体数组；question 必填，reference→expected_output、response→provided_response、system_prompt→system_prompt；坏行跳过并回行号） | `service/EvaluationService.java` + `dto/evaluation/ImportResultView.java` | `POST /api/evaluations/datasets/{id}/import` | 导入 jsonl 对话框（导入结果含坏行明细） |
| 预览前 2 样本 | 前端直取用例列表 | 复用 cases GET | 「预览」按钮 + 预览对话框 |

### A3. 评测器：内置目录 + 自定义

| 能力 | 承载文件 | 公开接口 / 形态 |
|---|---|---|
| 内置评测器目录（代码常量，不落表）：规则 9 + LLM-Judge 6 | 后端 `agent/EvaluationModel.java`（`ALL_METRICS` 逐字对齐）；前端 `api/evaluation.ts` `EVALUATOR_CATALOG` | 前端分组面板（规则判定 9 / LLM-Judge 6，每组一键全选/清空） |
| 自定义评测器 CRUD（category=rule / llm_judge；rule 三型规则；软删） | `entity/EvaluationCustomEvaluator.java`、`dto/evaluation/CustomEvaluatorView.java`、`service/EvaluationService.java`、db `V15`（`evaluation_custom_evaluator`，软删） | `GET/POST /api/evaluations/custom-evaluators`、`PUT/DELETE …/{id}`（评测指标名=`custom_{id}`） |
| 自定义规则三型（Java 确定性） | `agent/EvaluationModel.java`（按 rule_type 分发） | keyword_contains（keywords 数组：all=true 需全含、prohibit 命中 0、缺省任一命中判 1）、regex_match（`find()` 命中 1）、length_between（长度∈[min,max] 判 1）；params 非法/响应空 → 不适用 |
| 自定义 LLM-Judge（judge_prompt 含 {question}/{response}/{reference} 占位） | `service/LlmJudgeScorer.java` | 与内置 LLM-Judge 同一判分路径 |

### A4. 判分与报告

| 能力 | 承载文件 | 公开接口 |
|---|---|---|
| 判分对象与通过线 | 判分对象 `actual_response`（openjudge 由 service 复制 provided_response；execute 注入真实回复与轨迹）；空=不适用 null 不计均值；单用例得分 ≥0.8 记通过 | 随 run |
| 报告聚合（metrics 均值、findings 分级、summary 总分/verdict） | `dto/evaluation/ReportView.java`、`entity/EvaluationReport.java` | 随 run 返回；`GET /api/evaluations/reports`、`GET …/{id}` |
| 报告落库（jsonb metrics/findings/summary，含 judgeRounds/traceId） | `entity/EvaluationReport.java` + V12/V14 迁移 | 随 run |
| 报告删除 | `service/EvaluationService.java` | `DELETE /api/evaluations/reports/{id}` |

### A5. 前端能力（`ea-sys-web`）

- 页面：`src/views/EvaluationView.vue`（路由 `/evaluations`，name=`evaluations`，meta title 评测中心）。
- API 客户端：`src/api/evaluation.ts`（含 `EVALUATOR_CATALOG`）；类型 `src/api/types.ts`（DatasetView/CaseView/ReportView/CustomEvaluatorView/ImportResultView/…）。
- 交互：三组评测器面板（勾选=参与运行，全选/清空）；批量运行表单（数据集+评测器多选+LLM 判分轮次 1-5）；运行中提示「约需 1-3 分钟」且 `timeout: 0` 不限时（对应 0aad28d）；结果头部+指标均值表+findings；报告列表（含判分轮次/traceId列）+ 详情抽屉 + 驾驶舱跳转。
- LLM 未启用降级提示：面板副标题「LLM 未启用时 LLM-Judge 走确定性近似」。

---

## B. 实质新增能力（实现存在，文档无或已过时）

1. **LLM 判分并行化与超时收紧**（42b85f3）：`LlmJudgeScorer` 并发完成多轮 judge，全量 run ≈33s；前端 `runEvaluation(..., { timeout: 0 })` + nginx 反代读写超时放宽到 600s —— `docs/04-agent-design.md` 未提及。
2. **execute 模式的实测轨迹判分**（20b8a51）：`executeSubject` 从被测 AgentState 取最后回复 + 工具调用链（`tool_calls` 含 name/args/result）/步数，注入判分输入 `actual_trace`；四个执行维度评测器（tool_call_accuracy / task_success / step_efficiency / policy_compliance）据此判分 —— 文档 §13 只有一句概括，无实际判分输入结构说明。
3. **判分输入结构**：`input_summary` 顶层含 `actual_response`/`actual_trace`/`trace_id`/`judge_scores.{metric}` 等（随 audit_log 落库）—— 无文档。
4. **llm_usage 记账**：判分轮成功即 `recordCall` 写 llm_usage（agent 侧中间件 `onModelCall`）—— 文档只提「成功轮次写 llm_usage」，无表结构/口径细节（表结构见 V13 注释，不需要新文档，但既有文档可补一句）。
5. **numpy/sklearn 不依赖**：全部规则算法为纯 Java（无 Python 依赖）—— 无文档提及，但属于实现细节。
6. **数据集/报告级联软删**：删数据集级联软删用例+报告；自定义评测器软删 —— 有文档（§13 端点表），代码一致，仅此条。

---

## C. 规划参考号（既有事实，勿重复设计）

1. V12：`agent_cockpit_evaluation.sql`（evaluation_dataset / evaluation_case / evaluation_report 基表）；V14：`agent_evaluation_execute.sql`（execute 模式、agent_type 列、四执行维度评测器）；V15：`evaluation_custom_evaluator`（自定义评估器，软删）。**后续表变更应新建 V16+，禁止改已执行迁移。**
2. `AgentLlmProperties`（`easysys.agent.llm`：enabled/model-id/base-url/api-key/timeout-ms）+ `ModelRegistry.resolve`：LLM 模型位统一入口；key 缺失/失效/超时/结构不符 → 确定性 fallback，执行不中断。
3. `AgentPolicy`（`AgentRunConfig`: queued/parallel/sequential + timeoutMs/deterministic+fallback）；`HarnessAgentConfig`：evaluation 装配点。
4. 前端从 `EVALUATOR_CATALOG` 派生内置评测器分组（rule/llm），自定义评测器 metric=`custom_{id}` 并入 `enabledCustomMetrics`；新增内置评测器需**双端同步**（EvaluationModel 常量 + EVALUATOR_CATALOG）。
5. 判分入参与通过线：`actual_response` 空 = 不适用（不计均值）；单用例 ≥0.8 通过；metrics 只含 applicable_count>0 的评测器；无适用用例者仅以 findings INFO 列出；summary verdict ≥80 PASS / ≥60 WARN / 其余 FAIL。

---

## D. 能力边界核查（逐条回答，含「实验性」判断）

### D1. LLM 未启用的降级行为：已实现，非实验
`AgentLlmProperties.enabled=false` 时执行器保持确定性 RuleModel/EvaluationModel；LLM-Judge 六个指标 + 自定义 llm_judge 走确定性近似（前端明确标注），不发真实 LLM 调用、不写 llm_usage 行；批量运行整体仍成功。文档 §13 已描述。

### D2. 会话全链路消息：部分存在，未暴露到评测域（视为缺）
- **评测域内**：execute 仅注入「最后回复 + 工具调用链摘要（name/args/result）」与步数，**不保存完整会话转录**；判分输入落 audit 的是 input_summary 摘要。
- **系统级已有**（可复用）：`AgentState`/会话转录（llm_usage 台账定位最近会话）+ `llm_usage.lastChatSession/lastChatContext` + 驾驶舱 `GET /api/cockpit/llm-traces?trace=` 按评测 traceId 联动（报告详情「查看会话追踪」跳转，可清空恢复最近 20 条）——但不含评测域完整消息链。**若「会话全链路消息」是待建能力，建议作为新增项，不做实验性标记。**
- 相同问题「数据/动作/消息的可观性」在评测域**文档仅有 TraceID 联动说明**；完整转录存在于 AgentState 但无评测侧导出接口。

### D3. 断言式评测：全部已实现（确定性规则）；LLM 断言与多模型不支持
- rule 9 + 自定义三条规则全部纯 Java 确定性，无 Python 依赖、无断言式求解器（非可满足性求解）；字符串近似靠二元组 Jaccard/重复率，非 embedding 语义相似。
- LLM-Judge 6 + 自定义 llm_judge 是**评分式**（0-100 标量均值），非断言式真/假判定。
- **不支持**：多模型交叉验证/投票判分（每次运行一个模型位，经 ModelRegistry.resolve 取 `easysys.agent.llm`）；结构化评分输出仍靠正则 `\b\d{1,3}\b` 解析（**非实验**，但在线提示词评测领域通常视为近似解析）。
- 「确定性 + LLM 混合」「LLM 未启用可跑（近似）」是稳定策略，非实验。

### D4. 对比文档的出入（含 12h 前改动导致的过期）
1. **`PUT /api/evaluations/datasets/{id}/cases/{caseId}`**（文档 §13 端点表）→ 实现为 **`PUT /api/evaluations/cases/{id}`**（EvaluationController / evaluation.ts 一致）——**文档过期（历史遗留，非 12h 改动）**。
2. ~~**文档 §13「数据集/用例/导入/自定义评测器/报告的全部写操作均写入 agent_audit」** → 实现**仅 run() 落审计**~~ —— **已修复（V16）**：CRUD/任务审计已补齐，文档口径恢复一致。
3. 文档说「数据集编辑 mode/status 可改」——实现与之一致（scope 不可改，前端编辑时禁改 scope）。
4. 12h 前改动（`42b85f3`/`0aad28d`）已与实现一致：并行判分、前端 `timeout:0`、nginx 600s。无新过期项。
5. **执行侧被测智能体名称**：execute 支持 `assistant` / `workflow-dialogue`（agent_type 列）——文档一致。
6. **报告列表含 judgeRounds/traceId**：与文档一致（`GET /api/evaluations/reports` 返回两者）。
7. 两种模式均真实生成 report 落库；openjudge 不真实运行被测智能体（用预置 provided_response）——文档一致。
8. 前端评测器面板显示「规则判定 9 + LLM-Judge 6 + 自定义」固定文案；若未来新增内置评测器，此处硬编码总数（9/6）与 `EVALUATOR_CATALOG.filter().length` 不一致风险 —— **实现观感小出入（非功能问题）**。

---

## 附录：复用/改造要点速查

- 加内置评测器：`EvaluationModel` 常量 + `EVALUATOR_CATALOG`（双端）→ 前端面板自动出现；判分输入在 `EvaluationService` 组装。
- 加自定义规则型：`EvaluationModel` 按 rule_type 分发处 + 前端 ruleType 下拉 + types.ts 注释。
- LLM 相关改造：`AgentLlmProperties` + `ModelRegistry.resolve` + `LlmJudgeScorer`（同步 `blockFirst()` 调用；并行度在 42b85f3 收口处调整）。
- 判分轮次：`judgeRounds` 1-5，多轮取均值注入 `judge_scores.{metric}`；前端 `runJudgeRounds` 输入框联动。
- 全链路消息缺口：复用 `AgentState` 转录 + 驾驶舱 trace 过滤为起点，需新增评测侧导出存储/接口。