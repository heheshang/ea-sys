# Changelog

本项目变更记录，格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。
项目尚未发布语义化版本，当前变更累积于 `Unreleased`。

## [Unreleased]

### Added

- Docker 化部署：根 `docker-compose.yml` 一键启动 api / notify / web + 三通道 e2e mock（smtp / wechat / sms，仅内网无宿主端口映射），web nginx 反代 `/api/` 到 api 服务，README 增部署章节
- Dockerfile 三份：api / notify（maven 多阶段 + fat jar）、web（node 构建 → nginx）
- AI 智能客服：右下角悬浮窗，知识库问答（RAG 确定性检索 + 引用）、运营数据问答（到达率/留存率/漏斗/工作流效果）、人群检索、工作流触发（HITL 人工确认）、对话式创建工作流一键载入画布（复用 ai-chat 会话）（`64f6b1a`）
- 知识库文档管理：上传（txt/md/csv/xlsx/docx/pdf ≤10MB）同步分块入库，列表/删除，CJK 分词 + BM25 引用检索（`64f6b1a`）
- AUDIENCE 人群节点：DAG 中直接圈选人群快照，作为批量成员来源参与执行（`89248c2`）
- 通道真实下发发送日志：生命周期 / 请求 / 结果 / 供应商细节落库（`40d9f7f`）
- 执行报告增加通道触达日志，醒目展示每次真实下发记录（`fe215d9`）
- 联系人支持批量随机添加（最多 5000 条），用于人群圈选与触达验证（`101da4b`）
- TRIGGER 支持立即触发：发布后立即对配置人群执行一次（`7c92256`）
- 驾驶舱：八类知识领域图谱登记/状态管理（内置目录 + 用户登记覆盖），LLM 调用监控总览（token/调用/成本/延迟/降级，按 Agent 与模型聚合），确定性系统洞察与 LLM 调用追踪（审计驱动）
- 评测中心：数据集/用例管理（openjudge + execute 两种模式，execute 真实运行被测智能体 assistant / workflow-dialogue 注入工具轨迹），15 个内置评测器（规则 9 + LLM-Judge 6）批量运行 → 指标均值 → 报告落库 + 审计；执行维度新增 tool_call_accuracy / task_success / step_efficiency / policy_compliance 四个确定性规则评测器（V14）
- 驾驶舱 LLM 卡用量明细：总 Token / 提问轮次 / 调用 / 输入 / 输出 / 缓存命中六指标 + 上下文构成（六类条目数 / Token / 占比，字符折算估算），`llm_usage` 表按会话 upsert（V13），调用与 token 防双计合并 audit 口径
- 驱动舱 LLM 卡上下文构成改为查询期 AgentState 实时派生：按 `llm_usage` 最近聊天会话定位，取 agent 实时转录剔末尾最终回复后实算六类构成（中间件快照保留为兜底），估算逻辑抽共享类 `LlmContextEstimator` 与中间件共用防口径漂移
- 评测中心对齐：jsonl 批量导入（逐行/整体数组，坏行跳过错明细行号）、预览前 2 样本、LLM 判分轮次（1-5 多次取均值）、三组评测器面板（规则 9/LLM-Judge 6/自定义，每组一键全选清空）、自定义评测器（rule 三型 Java 参数化规则 + llm_judge 可配提示词，不引 Python）、real LLM-Judge 打分（service 层 LlmJudgeScorer 逐用例调用取均值，LLM 关闭降级确定性近似）、报告 TraceID 联动驾驶舱 LLM 追踪（evaluation_report.trace_id + audit …input_summary 过滤）（V15）
- 评测中心 RAG 评测：rag_hit_rate（execute 专属，search_kb 检索命中率——用例期望知识片段 expected_kb_hits 与实际检索结果字符 bigram 重合 ≥0.5 判中，无检索不适用）、用例期望片段录入、execute 轨迹增采 actual_tool_results（工具结果 name/state/output）（V17）
- 评测中心目录扩容：内置指标 15→16（规则 10 + LLM-Judge 6）
- 评测中心五层架构重构（V18）：用例分层 basic/edge/real（40/30/30 目标分布，缺省 basic 兼容旧数据）+ 逐用例评测器/阈值/提示词（`judge_rule`）+ 多轮对话用例（`dialogue`）；数据集发布版本化（`evaluation_dataset_version` 不可变 JSONB 快照，run/task 绑定版本锁复现，旧数据集自动回填 v1）；分层偏差 <20% 且用例数 ≥5 → findings WARNING
- 评测中心完整执行轨迹（Transcript）：`evaluation_transcript` 表逐轮落库（角色/文本/思考/工具参数与结果，thinking/args 截 4000、output 截 8000，executeSubject 增量采集）+ 报告/任务 `transcript?caseSeq=` 端点 + 每样本 latency_ms；多轮 = 同一 ReActAgent 共享会话顺序调用（HITL 工具无人值守挂起→超时→不适用，权限自动裁决 E4 未实现留作后续增强）
- 评测中心 Human 黄金标准：`evaluation_human_review` 表 + 复评提交/列表/删除端点 + 校准对比端点（per-metric n/均值/绝对差/一致率/Top 偏差，metric='*' 纯人工整分）（V19 修复复评 UNIQUE 与软删墓碑冲突）
- 评测中心内置评分器 decision_accuracy：execute 专属「首意图决策准确率」（首个 executed 工具调用与 expected_tool 匹配，参数全匹配 1.0 / 命中 0.5 / 无调用 0），内置指标 16→17（规则 10→11）
- 评测中心报告快照与统计：数据集版本 + env/code 三快照、execution（延迟 p50/p95/步数/llm 与 judge tokens/估算成本，LLM 未启用显示「—」）、分层统计（basic/edge/real count/tested/pass_rate）、summary recommendation（GO/WATCH/NO_GO 规则化上线决策 + reason）与 top_regressions
- 评测中心看板端点 `GET /api/evaluations/dashboard?datasetId=&limit=`（layering/trend/metrics/regressions/costLatency）
- 评测中心复现端点 `POST /reports/{id}/rerun`（按版本快照复现 + 基线锚定）；compare 增强（layer=basic/edge/real 过滤 + topDegradedSamples[]）
- 评测中心前端升级：分层分布色条列、发布版本弹窗/快照只读、运行版本下拉 + datasetVersionId、用例表单 category/judge_rule/dialogue 编辑、样本抽屉 transcript 逐轮视图 + latency、报告详情五区块（总览 recommendation 徽章/分层卡/执行统计/Top 退化/复现）+ 复评/校准抽屉、对比三 tab（指标/分层/Top 退化）、看板区块（Element Plus 无图表依赖）

### Changed

- 三路 agent 统一由 HarnessAgent 承载，移除 AgentExecutor（`c5c12a8`）
- 执行引擎关键节点补 SLF4J 日志（`4e6abc0`）
- 画布节点宽度按名称长度自适应（140–260px，超长省略悬浮全名），dagre 布局同步；新增居中和全屏按钮（`045d178`）
- 智能客服意图路由：政策值问句（目标/达标线/基线/阈值 + 数值问法）优先走知识库检索 search_kb，而非实时指标 query_stats（AssistantPolicy isKbPolicyValueQuestion）
- 评测中心内置评测器目录 16→17（规则 10→11：新增 decision_accuracy，execute 专属首意图决策）；M8EvalTaskTests catalog 断言 16→17 同步更新

### Fixed

- 新画布节点展示过大：fitView 统一 `maxZoom=1` 只缩不放，滚轮缩放上限保持不变（`9cb6668`）
- 条件 / 分流节点画布展示分支摘要；修复空 position 与布局尺寸脱节（`ec7cf41`）
- 画布打开时加载人群列表，TRIGGER 人群下拉显示名称而非数字（`03b2249`）
- 触发路径存在失败下发时执行状态降级 PARTIAL，与手动执行一致（`d7f0c60`）

> 早期里程碑（M0–M6b）功能范围见 README「里程碑」表。