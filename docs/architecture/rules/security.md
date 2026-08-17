# 安全规则

## 1. 默认规则

1. 禁止提交密钥、token、私钥、生产配置、本地环境文件、数据库备份和部署日志。
2. 日志、截图、测试 fixture 和错误报告不得包含敏感数据。
3. 客户端可公开配置和服务端机密必须分开记录。
4. 生产数据、用户数据、权限、账号、支付和密钥变更必须人工确认。

## 2. 03桌面项目规则

1. 触发条件：修改 Manifest。动作：`<uses-permission>` 必须存在于 V1 方案白名单，并同步安全文档和配置检查；全局返回 AccessibilityService 必须保持窗口内容、按键过滤和手势能力关闭。验证：`node scripts/check-android-config.mjs` 与 Manifest review。边界：禁止 `QUERY_ALL_PACKAGES`、全文件管理、联网、root 和车辆控制权限，不得把全局返回服务扩展为其它无障碍能力。
2. 触发条件：调整“安装 APK”入口。动作：Download 读取和 `REQUEST_INSTALL_PACKAGES` AppOp 均由安装流程前置授予，内置页不提供授权按钮，不调用 `requestPermissions`，也不跳转未知来源设置；页面只扫描公共 Download，并通过 FileProvider 只读 URI和系统安装器完成用户点选安装；增强文件管理器只承担可选 U 盘浏览。验证：扫描规则单测、安装脚本的 `-g` 与 AppOp 核验、Manifest/权限配置检查、Debug 构建和真机安装页路径。边界：权限缺失只提示安装流程故障，不在页面维护第二套授权，不申请全文件管理，不读取 U 盘，不静默安装。
3. 触发条件：发布或升级。动作：使用 `03桌面` 独立 release 证书，包名不变、versionCode 递增，证书仓库外双份备份。验证：签名摘要人工核对与升级安装 smoke。边界：未经明确指令不得生成、替换、提交或分发签名材料。
4. 触发条件：日志或测试证据。动作：只记录状态类别、计数和必要组件，不记录个人文件路径、APK 内容或密钥。验证：提交前敏感词和本机路径扫描。边界：ADB 原始日志、截图和录屏默认不提交。
5. 触发条件：修改全局返回。动作：保持既有 AccessibilityService 组件名，服务只连接 `GlobalBackActionGateway` 并调用 `GLOBAL_ACTION_BACK`。验证：网关单测、配置检查、Debug 构建和真机轻点/滑动分流。边界：不读取无障碍事件、窗口树或输入，不执行手势，不增加 HOME、RECENTS、媒体和自动点击。
6. 触发条件：构建或分发车机增强文件管理器。动作：固定 Fossify 上游提交、保留 GPLv3 许可证和完整改造补丁，并从 `vendor/fossify-file-manager-car/` 的脚本复现构建。验证：补丁 `git apply --check`、APK 包名/版本/签名和真机 U 盘入口。边界：Debug 签名只用于开发；官网正式发布必须单独建立长期 Release 签名并提供对应源码。
