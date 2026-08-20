# 文档索引

## 1. 定位

1. 本目录是新项目的长期上下文包，负责让 AI 和人都能快速判断“该读什么、该改哪里、该验证什么、什么不能碰”。
2. 文档只记录稳定事实、长期规则、阶段方案和可追溯结果；一次性聊天判断不直接写成长期规则。
3. 具体产品事实随项目落地逐步补齐，模板中的占位原则不得冒充已完成事实。

## 2. 文档路由

| 任务信号 | 必读文档 |
|---|---|
| 复制模板后的第一轮初始化 | `.agents/skills/project-bootstrap/SKILL.md` |
| 项目定位、系统边界、主链、不变量 | `docs/architecture/项目长期总纲.md` |
| V1 抽屉、应用管理、权限、状态流和物理施工边界 | `docs/plans/V1架构施工方案.md` |
| 未来新增车型、原车 UI 与事件行为适配顺序 | `docs/plans/多车型适配工作顺序.md` |
| Android 工具链、本机配置、构建与 ADB | `docs/operations/开发环境.md` |
| 包名、签名与升级身份 | `docs/decisions/0001-Android技术栈与应用身份.md` + `docs/plans/03desktop包名与签名迁移方案.md` |
| 隐藏自身及自卸载边界 | `docs/decisions/0002-隐藏自身及自卸载边界.md` |
| 原车 UI 证据与抽屉视觉 token | `docs/architecture/rules/design.md` + `03lyrics/docs/architecture/iCAR车机UI设计规范.md`（只读来源） |
| 车机系统能力探测结果 | `docs/testing/车机能力验证记录.md` |
| 界面语言、locale、fallback 与文案资源 | `docs/architecture/多语言与文案主链.md` |
| 新项目工作法、Vibe Coding、工程化升级 | `docs/architecture/新项目VibeCoding长期总纲.md` |
| 通用能力、能力成熟度、owner 边界 | `docs/architecture/通用能力索引.md` |
| Codex 工作流、任务分级、Agentic Engineering | `docs/architecture/Codex使用流程与AgenticEngineering差距分析.md` |
| 文档 / skill / script / guard / eval 路由 | `docs/architecture/Codex规则能力池.md` |
| 创建、更新、拆分或审查项目级 skill | `.agents/skills/skill-authoring/SKILL.md` |
| 通用能力去专有化并回流本地新项目模板 | `.agents/skills/template-feedback/SKILL.md` |
| 多 Agent 协作、并行边界、合并顺序 | `docs/architecture/multi-agent-workflow.md` |
| 需求、用户路径、商业边界、验收口径 | `docs/product/产品需求基线.md` |
| 任务入口、风险分级、完成标准 | `docs/plans/codex-task-intake-template.md` |
| 阶段施工方案、迁移方案、复杂改造 | `docs/plans/` |
| 验证命令、smoke、剩余手测 | `docs/testing/验证矩阵.md` |
| Codex 工作流复盘 | `docs/testing/codex-workflow-scorecard.md` |
| Codex 行为 eval | `docs/testing/codex-workflow-eval-cases.md` |
| 密钥、PII、日志、权限、生产数据 | `docs/security/安全与密钥边界.md` |
| 部署、环境、回滚、运维事实 | `docs/operations/` |
| 决策理由、路线取舍 | `docs/decisions/` |
| 当前事实、收尾记录、未完成事项 | `docs/progress.md` |
| 可复用规则、重复经验、设计和代码规范 | `docs/architecture/rules/README.md` |
| 项目类型选择、初始化能力包 | `docs/blueprints/README.md` |

## 3. 写作口径

1. 总纲写边界、主链、不变量和验收标准。
2. 计划写未来怎么做、物理锚点和验证闭环。
3. 进度写已经做了什么、验证了什么、还剩什么。
4. 决策写为什么选这条路线，以及拒绝哪些路线。
5. 规则文档按分类沉淀稳定经验，不把项目专属临时选择写成通用原则。
6. 能力池只写路由，不复制大型方案。
7. Eval 只评估 Agent 行为，不替代业务测试、smoke 或人工验收。

## 4. 更新规则

1. 每次发现新项目应具备的通用规则、能力、skill、检查或文档槽位，先使用 `rule-discovery` skill 判断分类。
2. 如果内容适合所有新项目默认具备，必须去专有化后回流到本地模板源 `/Users/q/Documents/Projects/NewProject`；复制后的项目只记录候选，不把自己当作模板源。
3. 如果规则只对某个项目成立，写入该项目自己的文档，不反向污染模板。
4. 如果规则已经可以机械检查，优先补脚本、hook、测试或 checklist，不只停留在自然语言。
5. 本项目已经完成初始化，V1 架构方案与决策 0002 已确认；业务施工遵守方案的架构与权限边界，但按根 `AGENTS.md` 的小切片、用户主验收节奏推进，不受历史内部检查点约束。
