# 03desktop 包名与签名迁移方案

## 1. 最终身份

本方案记录 `03桌面` 从历史开发身份迁移到 9.9 Studio 长期身份后的真值：

```text
productId = 03desktop
packageName = com.ninepointnine.desktop
displayName = 03桌面
```

历史包名 `com.tcrrry.desktop` 不再用于新构建。`com.tcrrry.icar.window.*` 与
`com.tcrrry.icar.surface.*` 是跨应用窗口协议常量，不属于产品包名，继续保持原值。

## 2. 物理迁移

1. Android `namespace`、`applicationId`、Kotlin 主源码、Debug instrumentation、JVM 测试、安装脚本和配置护栏统一使用 `com.ninepointnine.desktop`。
2. 源码根目录统一为 `app/src/<source-set>/java/com/ninepointnine/desktop/`，旧 `com/tcrrry/desktop/` 目录不再存在。
3. 自身过滤只精确匹配新包名；`03歌词` 继续作为普通第三方应用处理，不按共同品牌前缀提供特殊保护。
4. FileProvider authority 继续由 `${applicationId}.fileprovider` 生成，因此自动迁移到新包名，不写死第二份常量。

## 3. 签名身份

测试与正式版本各使用一套独立 RSA 4096 / SHA256withRSA 证书，材料只保存在仓库外：

| 环境 | alias | 证书 SHA-256 |
|---|---|---|
| staging | `03desktop-staging` | `BFB70DC15B54AD2F1B8ACD35FA26ECF552BF2EF21D416A44B7EEDA5E5E9EBAA9` |
| production | `03desktop-release` | `D5ED175F00BD64BE4DE15ECC03B3EB3D1019305A89637E0A1C6B4DF314AA2417` |

keystore、`signing.properties`、口令和私钥不得进入仓库、APK、日志或聊天。仓库只保存属性名、公开证书摘要和读取逻辑。

## 4. 构建注入

默认 Debug 保持开发机自动 Debug 证书，不读取 staging 或 production 材料。

测试包由云端或本机显式注入：

```text
./scripts/gradlew-jdk17.sh assembleDebug \
  -PdesktopSigningEnvironment=staging \
  -PdesktopStagingSigningPropertiesFile=<repository-external-staging-signing.properties>
```

正式包固定使用 production 签名：

```text
./scripts/gradlew-jdk17.sh assembleRelease \
  -PdesktopProductionSigningPropertiesFile=<repository-external-production-signing.properties>
```

staging 模式缺少测试属性文件时在配置阶段失败；Release 缺少正式属性文件时在预构建阶段失败，不允许静默回落到开发机 Debug 证书或产出未签名正式包。`storeFile` 相对路径以对应 `signing.properties` 所在目录为基准，便于云端在临时私密目录重建材料。

## 5. Cloud 边界

1. Cloud 产品资料记录新 Android 包名和两套公开证书摘要；云端仓库不保存 keystore、口令或本机路径。
2. 当前 `03desktop` 云端商品是无权益的自愿赞助，签名摘要不参与 Device Commerce 设备准入，因此本轮不新增 `DEVICE_COMMERCE_03DESKTOP_SIGNING_CERT_SHA256` 或许可证 trust bundle。
3. 云端构建应从密钥系统把环境对应的 JKS 与 `signing.properties` 写入同一临时私密目录，执行上节命令，校验 APK 后销毁临时目录。
4. 如果未来 `03desktop` 增加设备权益，再单独建立 productId、SKU、签名摘要和许可证公钥的一一对应契约，不复用 `03lyrics` 或 `03cast` 的商业身份。

## 6. 安装迁移影响

1. 新包名与旧包名是两个独立 Android 应用，可以同时存在，不能通过覆盖安装继承旧包数据。
2. SharedPreferences、应用排序、悬浮窗授权、Download 权限、未知来源安装 AppOp 和 AccessibilityService 启用状态不会自动迁移；用户安装新包后需要重新完成相关系统授权。
3. 包名变化后 AccessibilityService 组件为 `com.ninepointnine.desktop.debug.NavigationDemoAccessibilityService`。旧包已启用的服务授权不能转移；新包首次启用后，后续同包名升级必须保持该组件名稳定。
4. 同一新包名下，默认开发 Debug、staging 和 production 证书彼此不同，不能互相覆盖。长期测试设备固定使用 staging，正式用户固定使用 production。

## 7. 验证标准

1. 默认 Debug 构建不依赖仓库外签名材料。
2. staging / production 缺少各自属性文件时必须失败。
3. staging Debug 与 production Release 均能构建，APK 包名必须为 `com.ninepointnine.desktop`。
4. 两个 APK 都必须只有一个 signer、APK Signature Scheme v2 有效，证书摘要分别与上表一致。
5. APK 内容不得包含 `signing.properties`、JKS、keystore 或四个签名口令中的任意一个。
6. 正式分发前必须把两份本地待转移副本分别移动到两个独立离线介质，并完成一次逐字节校验。

## 8. 本轮边界

本轮只完成包名迁移、签名材料生成、构建接线和产物验证；不安装到车机、不卸载旧包、不清数据、不修改系统授权、不部署 cloud，也不对外分发 APK。
