# 代码规则

## 1. 默认规则

1. 先沿现有架构和模块边界施工，再考虑新增抽象。
2. 共享逻辑必须沉到通用模块，不能在页面、命令、脚本或适配器里复制。
3. 领域规则应在领域层表达，UI 只负责展示和交互，基础设施只负责连接外部能力。
4. 错误处理必须有分类、可观测信息和用户可理解结果，禁止简单吞掉异常。
5. 新增依赖前必须说明长期收益、替代方案和验证方式。
6. 存在用户可见界面时，文案必须通过统一 localization owner 和结构化 locale 资源进入 UI；组件、页面、状态机和错误分支不得各自维护语言判断或重复 fallback。

## 2. 03桌面项目规则

1. 触发条件：新增 Android 业务能力。动作：保持 Kotlin + XML Views 单模块，按 `model`、`overlay`、`apps`、`install`、`system`、`boot` 分包；UI/Service/Receiver 只编排，系统 API 由适配器独占。验证：普通切片执行 Debug 构建和命中的直接相关测试；完整配置、单测与 Lint 只在里程碑口径执行。边界：只有现有抽象无法表达且出现真实复用时才新增模块或框架。
2. 触发条件：新增文件或子包。动作：从 `MainActivity.kt` 复制 UTF-8 文件头与 `com.tcrrry.desktop` ASCII 根包，XML 从现有 Manifest 复制声明。验证：当前功能切片结束时统一构建；出现命名空间、类型归属或资源错误时先修复。边界：禁止复制 `03歌词` 的命名空间、源码或 Manifest。
3. 触发条件：处理应用排序。动作：包名 + `firstInstallTime` 是安装身份，SharedPreferences JSON 是顺序唯一真值；拖拽帧只改内存，松手最多写一次。验证：`AppOrderReconcilerTest` 与 `DragSessionTest`。边界：显示名称、组件顺序和 Bitmap 不得进入持久排序身份。
4. 触发条件：连接 PackageManager、WindowManager、Storage 或 Intent。动作：从 `docs/plans/V1架构施工方案.md` 指定的适配器入口施工。验证：直接相关纯函数单测 + Debug 构建，运行效果交给用户按本轮路径手测；里程碑或明确要求时再做 `S56_HQX` smoke。边界：车厂私有接口、反射、无障碍、ADB 和 shell 不能成为产品主链。
5. 触发条件：Kotlin 函数使用表达式体（`=`）且需要在条件分支提前退出。动作：改用块函数体，或把分支表达为局部结果；不得在表达式体中使用非局部 `return`。验证：先运行受影响 Kotlin 编译与对应单测。边界：此规则适用于 Kotlin 编译器语义，不是 Android 或本项目特例；可作为模板候选规则。
6. 触发条件：抽屉动作需要打开应用、03桌面自有 Activity 或系统 Activity。动作：先完成抽屉关闭，再统一交给 `SystemActionLauncher`；普通应用按目标 metadata 决定是否互斥，安装页、卸载页及未来设置页等顶层窗口必须强制复用 `StandardFloatingWindowLaunchCoordinator`。隐式系统 Intent 必须在等待前解析并固定为显式组件，保证 HOME 切换期间目标不漂移。验证：协调器覆盖 `window_mode=0/2/未知`、重复点击、HOME/目标失败、超时和取消；`check-android-config` 拦截 `SystemActionLauncher` 直接启动旁路。边界：安装流程中必须返回结果才能续接的授权页属于当前顶层任务的子页面，除非先建立可持久恢复的续接状态，否则不得为了互斥切断父子结果链。
