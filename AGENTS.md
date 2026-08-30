# AGENTS.md

核心：用中文。

## 1. 项目定位

1. 本仓库是 03桌面 的项目仓库。
2. 一句话定位：Android 9 车机上补齐第三方应用展示与管理能力的轻量右侧应用抽屉
3. 项目类型：`desktop-client`，技术形态固定为 Android 9 原生车机客户端，不是 PC 桌面客户端。
4. 本仓库已经完成模板初始化，不再是通用模板本身。
5. 通用能力索引和 blueprints 只作为候选参考，未写入项目总纲和验证矩阵前不得视为已实现能力。
6. 产品包名固定为 `com.ninepointnine.desktop`；`03歌词` 的包名 `com.ninepointnine.desktoplyrics`、签名、源码和产品状态均不属于本项目。

## 2. 默认目录边界

1. `app/src/main/`：项目源码主目录。
2. `docs/`：产品、架构、计划、安全、运维、测试、决策和进度文档。
3. `.agents/skills/`：高频 AI 工作流，按任务动态读取。
4. `scripts/`：仓库级快检、验证、生成、迁移和机械护栏。
5. `.codex/`：项目级 Codex 配置说明和可提交配置，不放密钥、个人 token 或机器私有路径。
6. `app/src/main/java/com/ninepointnine/desktop/`：Kotlin 源码根包；包名只使用 ASCII。
7. `app/src/main/res/`：Android 资源与用户可见文案；中文文案必须从现有 UTF-8 源文件同源复制，禁止依据终端乱码重建。

## 3. 工作原则

1. 先定位真实目标，再决定改哪里；禁止只围绕表面报错堆分支。
2. 从底层架构和通用能力往上施工，先抽象稳定主链，再接具体调用点。
3. 不主动往降级、绕路或弱化验证的方向靠拢；用户提出的需求要尽可能完整完成。
4. 如果现有抽象不能表达需求，先升级抽象和契约，再扩展业务入口。
5. 只有用户纠偏、同类问题第二次出现或形成跨任务稳定经验时，才使用 `rule-discovery`；普通功能施工不做规则沉淀。
6. 发现适合所有新项目默认具备的能力、skill、规则、检查或文档槽位时，先在当前项目记录为候选通用能力；去除项目专有事实后，回流到本地模板源 `/Users/q/Documents/Projects/NewProject`。本仓库是具体项目仓库，不能作为模板源使用。
7. Android 主链采用单 `app` 模块、Kotlin、XML Views 和公开系统 API；无障碍仅允许用户已确认的全局返回单动作适配器，不得读取窗口内容、监听按键、执行手势或扩展其它全局动作；不得引入 Compose、ADB 运行依赖或车厂私有接口，除非专项方案重新论证并经确认。
8. 悬浮窗、应用枚举、安装、卸载和开机恢复施工时，沿用 `docs/plans/V1架构施工方案.md` 的架构边界、系统适配器和权限白名单；媒体能力当前不进入 V1。其中历史施工节奏与必跑验证项由本文件和 `docs/testing/验证矩阵.md` 的当前口径覆盖。
9. 项目采用用户主验收的敏捷节奏。每次只实现当前请求中可独立运行的最小功能切片；代码变更默认只执行最低成本机器检查和直接相关单测，不自动执行人机交互冒烟、截图、坐标点击或连续操作。需要把 Debug APK 放到目标设备时，只执行项目提供的 install-only 交付入口，不启动应用、不代替用户验收；只有用户明确要求、进入里程碑或用户报告卡顿 / 闪退需要诊断时，才执行针对性的运行级检查。不因视觉交互等待用户手测而延迟交付。
10. 本项目明确覆盖上级全局工作流的默认门禁：用户直接要求修改或修复即视为授权施工，普通任务无需先经历“互动态 -> 架构方案 -> 开始施工”三段确认。只有范围会产生显著不同结果、涉及安全红线或用户明确只要方案时才先确认。
11. `03歌词` 在产品中始终按普通第三方应用处理，不享受永久卸载保护。自动回归默认使用可删除测试包；用户在当前任务中明确指定 `com.ninepointnine.desktoplyrics` 并要求卸载时，确认卸载数据影响且核对可恢复 APK 后应执行实际卸载，不得以自动测试、调试保护或历史样本规则为由拒绝。未经独立任务授权不得修改其源码、签名或产品状态。

## 4. 安全红线

1. 禁止提交真实密钥、token、私钥、生产配置、本地环境文件、数据库备份和部署日志。
2. 禁止把 `.env`、`.env.*.local`、`*.local.*`、本地缓存、构建产物、临时截图、录屏和调试日志作为普通改动提交。
3. 生产、支付、账号、权限、用户数据、数据库迁移、密钥变更和正式上线必须先取得用户明确确认。
4. 客户端可公开配置和服务端机密必须在 `docs/security/安全与密钥边界.md` 中分开记录。
5. 权限采用白名单；禁止申请 root、`QUERY_ALL_PACKAGES`、`MANAGE_EXTERNAL_STORAGE`、忽略电池优化、车辆控制或与 V1 无关的网络权限。`READ_EXTERNAL_STORAGE` 和 `REQUEST_INSTALL_PACKAGES` 只允许内置 Download APK 安装页使用；唯一获准的无障碍能力是用户在系统侧手动启用的全局返回服务，只能调用公开 `GLOBAL_ACTION_BACK`，不得读取或操纵其它界面内容。
6. 发布签名必须为 `03桌面` 独立创建并在仓库外备份，禁止复用 `03歌词` 或其它产品的密钥。

## 5. 任务入口

1. 任务入口模板见 `docs/plans/codex-task-intake-template.md`。
2. L1/L2 任务直接施工并快速交付；只有涉及权限扩张、设备破坏性操作、发布签名、正式发布、架构主链重写或范围不清会产生显著不同结果时，才先明确范围和人工门禁。
3. 用户明确只要方案、分析、review 或状态检查时，不得擅自改文件。
4. 文档路由见 `docs/README.md`；规则分类见 `docs/architecture/rules/README.md`。
5. 开发环境与车机连接任务读取 `docs/operations/开发环境.md`；权限与高风险系统入口读取 `docs/security/安全与密钥边界.md`。

## 6. Skills 路由

1. 规则发现、经验沉淀、重复流程归纳或新规则分类时，使用 `.agents/skills/rule-discovery/SKILL.md`。
2. 用户明确要求提交、交接、handoff、归档或正式收尾时，使用 `.agents/skills/task-closeout/SKILL.md`；普通功能交付和状态汇报不触发该 skill。
3. 创建、更新、拆分或审查项目级 skill 时，使用 `.agents/skills/skill-authoring/SKILL.md`。
4. 将适合所有新项目默认具备的能力去专有化并回流本地模板源时，使用 `.agents/skills/template-feedback/SKILL.md`。
5. 新增业务专项 skill 前，先确认它不是一次性规则，也不是已有文档、脚本或测试能承接的机械动作。
6. 参考其它项目 skill 时，只复制通用工作流骨架和可验证做法，必须去除其它项目专有约束、名称、路径、部署入口和业务能力。
7. 模板级候选能力必须通过 `rule-discovery` 判断，再通过 `template-feedback` 回流本地模板源 `/Users/q/Documents/Projects/NewProject`；当前项目只承载项目事实和候选记录。
8. 涉及本产品包名、namespace、版本、签名、远端仓库或发布产物时，必须先使用 `.agents/skills/03-app-repository/SKILL.md` 路由到共享 `03-app-manager`；不得在本仓库维护第二份 03 APP 身份表。

## 7. 验证分级

1. 初始化完成度检查执行 `node scripts/check-project-ready.mjs`。
2. Skill 基础结构检查执行 `node scripts/check-skills.mjs`。
3. 普通 Kotlin、资源或 Manifest 切片的默认验证仅为 `./scripts/gradlew-jdk17.sh assembleDebug`；改动命中已有直接相关单测时追加对应测试，构建失败必须修复后交付。
4. 纯文案、文档、只读查询和纯 Git 操作只做与改动直接相关的静态检查，不启动 Android 全量构建或车机验证。
5. 产生可在车机手测的应用行为变化时，默认交付完整用户手测用例；如需把 Debug APK 放到设备，只使用项目提供的 install-only 入口，不启动应用、不代替用户验收。坐标、手势、视觉、连续操作、系统弹窗、地图边缘和倒车影像由用户手动验收。
6. `testDebugUnitTest lintDebug assembleDebug`、完整回归和真机主路径 smoke 只在里程碑收口、准备提交/发布、高风险系统能力变更、用户明确要求或故障诊断时执行；不得在每个功能切片和收尾阶段重复运行。
7. 本项目自身 Debug APK 的无清数据覆盖安装，以及安装时自动授予 Manifest 白名单内的 `READ_EXTERNAL_STORAGE` 和 `REQUEST_INSTALL_PACKAGES` AppOp，已获得长期授权，不再逐轮确认；安装脚本必须使用 `adb install -r -g` 并核验未知来源安装 AppOp，使开发验证与手机安装流程一致。安装其它 APK、其它授权、卸载、清数据、重启车机、修改系统设置和车辆安全场景仍属于人工门禁；未经用户明确授权不得执行。自动卸载验证默认使用可删除测试包；用户在当前任务中明确指定普通第三方应用的精确包名并要求卸载，即视为已打开该目标的人工门禁，确认数据影响并核对可恢复 APK 后应执行，不得以自动测试或调试阶段保护为由拒绝。
8. 验证结论必须按实际范围表述：机器检查通过只证明基础可构建或安装，交互效果等待用户手测，不得伪造通过。
9. 用户明确要求“由我手测 / 不做冒烟”时，该人工分工优先于默认验证建议；若其它约束文档仍要求交互 smoke，必须先更新冲突文档，再开始施工。
10. 只读车机能力检查使用 `./scripts/check-android-env.sh --device` 和 `./scripts/check-device-capabilities.sh`，脚本不得安装、卸载、授权或修改系统设置。

## 8. Git 与收尾

1. 只暂存本轮任务直接相关文件，不回退用户未授权改动。
2. 普通功能切片完成后立即交付，不为收尾重复构建、补跑完整验证或强制更新进度文档。
3. 只有用户明确要求提交或任务开始时已授权本轮提交，才执行 `git add` 和提交；用户手动验收可以发生在任意功能切片之后，不必等待整个 V1。
4. 普通代码交付需说明改动、实际最低成本机器检查、是否执行 install-only，以及完整手测用例；不把启动状态或交互 smoke 作为默认交付项。提交、发布、阻断或高风险操作时再补充 Git、部署和门禁状态。
5. 未经用户明确要求，不默认部署测试环境，不默认上线正式环境；向车机覆盖安装 Debug APK 属于本项目开发验证，不代表部署或正式发布。

## 9. Release 版本约束

1. `release-version.properties` 是 Release 的唯一版本真值；Release 输出固定使用其中的 `releaseVersionName`，当前为 `1.0.1-icar03`。Debug 与 Staging 继续使用 `app/build.gradle.kts` 的 `defaultConfig` 版本，不因 Release 版本变化而改动。
2. 用户未指定版本时，正式发布准备必须执行 `node scripts/bump-release-version.mjs`，只递增 `major.minor.patch-icar03` 的最后一位并同步递增 `releaseVersionCode`；用户明确指定版本时使用 `--version <major.minor.patch-icar03>`，不得擅自改写用户指定值。
3. 构建本身不得修改版本文件；发布前先运行 `node scripts/bump-release-version.mjs --check`，再构建 Release 并核对 APK 元数据、签名和 `versionCode` 单调递增。该规则只约束 Release，不适用于 Debug / Staging。
