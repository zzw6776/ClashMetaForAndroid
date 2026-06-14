# 项目级规则

1. **回答与文档语言**：所有回答和文档输出必须使用中文。

2. **打包限制**：只允许构建 `release` 包，默认只构建 `-Plocal.install.abi=arm64-v8a`；禁止构建 `debug` 或其他非 `release` 包。用户明确要求“打包安装”或“安装”时，默认先执行 `.\gradlew.bat :app:assembleMetaRelease "-Plocal.install.abi=arm64-v8a"` 生成 release APK，再使用 `adb install -r <生成的 arm64-v8a meta release apk>` 安装；如有多台设备，默认复用同一个 APK 并分别执行 `adb -s <设备序列号> install -r <apk>`。该默认流程无需再次确认；其他打包命令或非默认参数仍需先输出拟执行命令并等待用户明确确认。

3. **验证策略**：代码修改后的默认验证应使用最小必要 `release` 任务，避免无必要完整打包。Kotlin/Java 改动优先运行相关模块的 `compileMetaReleaseKotlin` / `compileMetaReleaseJavaWithJavac`；资源或 XML 改动优先运行相关 `processMetaReleaseResources`；只有需要生成 APK 产物、用户明确要求打包或按第 2 条默认安装流程生成 APK 时才运行 `:app:assembleMetaRelease`；只有用户明确指定使用 Gradle install 任务时才运行 `:app:installMetaRelease`。所有 Gradle 验证命令默认附加 `"-Plocal.install.abi=arm64-v8a"`。

4. **修改确认**：进行任何代码、配置或文档修改前，必须先输出修改方案并等待用户明确确认。
