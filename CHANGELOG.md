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
- 评测中心：数据集/用例管理（openjudge + execute 两种模式），11 个内置评测器（规则 5 + LLM-Judge 6）批量运行 → 指标均值 → 报告落库 + 审计
- 驾驶舱 LLM 卡用量明细：总 Token / 提问轮次 / 调用 / 输入 / 输出 / 缓存命中六指标 + 上下文构成（六类条目数 / Token / 占比，字符折算估算），`llm_usage` 表按会话 upsert（V13），调用与 token 防双计合并 audit 口径
- 驾驶舱 LLM 卡上下文构成改为查询期 AgentState 实时派生：按 `llm_usage` 最近聊天会话定位，取 agent 实时转录剔末尾最终回复后实算六类构成（中间件快照保留为兜底），估算逻辑抽共享类 `LlmContextEstimator` 与中间件共用防口径漂移

### Changed

- 三路 agent 统一由 HarnessAgent 承载，移除 AgentExecutor（`c5c12a8`）
- 执行引擎关键节点补 SLF4J 日志（`4e6abc0`）
- 画布节点宽度按名称长度自适应（140–260px，超长省略悬浮全名），dagre 布局同步；新增居中和全屏按钮（`045d178`）

### Fixed

- 新画布节点展示过大：fitView 统一 `maxZoom=1` 只缩不放，滚轮缩放上限保持不变（`9cb6668`）
- 条件 / 分流节点画布展示分支摘要；修复空 position 与布局尺寸脱节（`ec7cf41`）
- 画布打开时加载人群列表，TRIGGER 人群下拉显示名称而非数字（`03b2249`）
- 触发路径存在失败下发时执行状态降级 PARTIAL，与手动执行一致（`d7f0c60`）

> 早期里程碑（M0–M6b）功能范围见 README「里程碑」表。