# 代码规则

## 1. 默认规则

1. 先沿现有架构和模块边界施工，再考虑新增抽象。
2. 共享逻辑必须沉到通用模块，不能在页面、命令、脚本或适配器里复制。
3. 领域规则应在领域层表达，UI 只负责展示和交互，基础设施只负责连接外部能力。
4. 错误处理必须有分类、可观测信息和用户可理解结果，禁止简单吞掉异常。
5. 新增依赖前必须说明长期收益、替代方案和验证方式。
6. 存在用户可见界面时，文案必须通过统一 localization owner 和结构化 locale 资源进入 UI；组件、页面、状态机和错误分支不得各自维护语言判断或重复 fallback。

## 2. 03桌面项目规则

1. 触发条件：新增 Android 业务能力。动作：保持 Kotlin + XML Views 单模块，按 `model`、`overlay`、`apps`、`install`、`system`、`boot` 分包；UI/Service/Receiver 只编排，系统 API 由适配器独占。验证：配置检查、单元测试、Lint 与编译。边界：只有现有抽象无法表达且出现真实复用时才新增模块或框架。
2. 触发条件：新增文件或子包。动作：从 `MainActivity.kt` 复制 UTF-8 文件头与 `com.tcrrry.desktop` ASCII 根包，XML 从现有 Manifest 复制声明。验证：每组结构性文件后运行 Kotlin 编译和资源处理。边界：禁止复制 `03歌词` 的命名空间、源码或 Manifest。
3. 触发条件：处理应用排序。动作：包名 + `firstInstallTime` 是安装身份，SharedPreferences JSON 是顺序唯一真值；拖拽帧只改内存，松手最多写一次。验证：`AppOrderReconcilerTest` 与 `DragSessionTest`。边界：显示名称、组件顺序和 Bitmap 不得进入持久排序身份。
4. 触发条件：连接 PackageManager、WindowManager、Storage、Intent 或 AudioManager。动作：先在 `docs/plans/V1架构施工方案.md` 指定适配器入口施工。验证：纯函数单测 + `S56_HQX` 真机 smoke。边界：车厂私有接口、反射、无障碍、ADB 和 shell 不能成为产品主链。
