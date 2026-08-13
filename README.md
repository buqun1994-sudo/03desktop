# 03桌面

`03桌面` 是面向 Android 9 车机的轻量第三方应用抽屉，用于补齐原厂应用中心无法展示全部用户应用的问题。

## 当前状态

1. 项目已从 `NewProject` 模板完成独立初始化，不再处于 `INIT_REQUIRED`。
2. V1 业务基线已经实现，当前按用户体验反馈进行小切片调整和修复。
3. 默认节奏是“实现最小切片 -> Debug 构建 -> 立即交给用户手测”；完整测试、Lint 和真机验收只在里程碑、提交/发布准备或明确要求时执行。

## 开发入口

1. 环境说明：`docs/operations/开发环境.md`。
2. 产品基线：`docs/product/产品需求基线.md`。
3. 系统主链：`docs/architecture/项目长期总纲.md`。
4. V1 方案：`docs/plans/V1架构施工方案.md`。
5. 验证矩阵：`docs/testing/验证矩阵.md`。

```bash
./scripts/gradlew-jdk17.sh assembleDebug
```

本机路径、签名材料、Gradle 缓存和 Android SDK 不属于仓库内容。
