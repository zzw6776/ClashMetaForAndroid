# 项目级规则

1. **回答与文档语言**：所有回答和文档输出必须使用中文。

2. **打包限制**：只允许构建 `release` 包，默认只构建 `-Plocal.install.abi=arm64-v8a`；禁止构建 `debug` 或其他非 `release` 包。用户明确要求“打包安装”时，可直接执行默认 release 安装命令 `.\gradlew.bat :app:installMetaRelease "-Plocal.install.abi=arm64-v8a"`，无需再次确认；其他打包命令或非默认参数仍需先输出拟执行命令并等待用户明确确认。

3. **修改确认**：进行任何代码、配置或文档修改前，必须先输出修改方案并等待用户明确确认。
