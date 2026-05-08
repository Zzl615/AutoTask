# 13 · 待办

本文件只记录尚未完成的后续优化项；已完成的构建体验、CI、lint error 修复和低风险 warning 清理不再列入。

## 高优先级

1. **密钥与服务配置外置**
   - 范围：`app/src/main/java/top/xjunz/tasker/api/Client.kt`、`app/src/main/java/top/xjunz/tasker/App.kt`。
   - 目标：更新接口 token、AppCenter secret 等不再硬编码在源码中，改为 `BuildConfig`、未提交的本机配置或 CI 注入。

2. **收敛明文网络访问**
   - 范围：`app/src/main/AndroidManifest.xml`、`api/Client.kt`。
   - 目标：默认关闭全局 `usesCleartextTraffic`，确有 HTTP 需求时用 `networkSecurityConfig` 仅放行指定域名。

## 中优先级

3. **处理 native 16KB page size 兼容**
   - 范围：`ssl` 模块和 APK 中的 native library。
   - 目标：确认 NDK/CMake 构建产物满足 Android 未来 16KB page size 设备要求。

4. **建立 lint warning baseline 或分批清理**
   - 范围：`make lint` 生成的 lint 报告。
   - 目标：先建立现有 warning 的 baseline，再让 CI 阻止新增 warning；`UnusedResources` 需要逐项确认，不直接批量删除。

5. **依赖升级评估**
   - 范围：`build.gradle.kts`、各模块 `build.gradle.kts`。
   - 目标：对 AndroidX、Material、Lifecycle、Coroutines、HiddenApiBypass 等依赖做兼容性测试后再升级。

## 低优先级

6. **统一 JVM 目标版本**
   - 范围：`app/build.gradle.kts` 与各 library 模块。
   - 目标：明确项目统一使用的 Java/Kotlin target，减少模块间工具链差异。

7. **重新设计崩溃处理委托**
   - 范围：`app/src/main/java/top/xjunz/tasker/ui/outer/GlobalCrashHandler.kt`。
   - 目标：评估是否保留自定义崩溃页，同时正确委托或替代系统默认 uncaught exception handler。
