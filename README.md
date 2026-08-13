# 03桌面

`03桌面` 是面向 Android 9 车机的轻量第三方应用抽屉，用于补齐原厂应用中心无法展示全部用户应用的问题。

## 当前状态

1. 项目已从 `NewProject` 模板完成独立初始化，不再处于 `INIT_REQUIRED`。
2. 当前只落地 Android 工程底座、项目约束和 V1 架构方案，尚未施工悬浮抽屉业务。
3. V1 架构方案与“隐藏 `03桌面` 自身”决策已经确认；后续按方案分阶段施工、构建、安装到车机并执行最短相关冒烟。

## 开发入口

1. 环境说明：`docs/operations/开发环境.md`。
2. 产品基线：`docs/product/产品需求基线.md`。
3. 系统主链：`docs/architecture/项目长期总纲.md`。
4. V1 方案：`docs/plans/V1架构施工方案.md`。
5. 验证矩阵：`docs/testing/验证矩阵.md`。

```bash
./scripts/check-android-env.sh
./scripts/gradlew-jdk17.sh testDebugUnitTest lintDebug assembleDebug
```

本机路径、签名材料、Gradle 缓存和 Android SDK 不属于仓库内容。
