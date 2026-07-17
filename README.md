## Clash Meta for Android

A Graphical user interface of [Clash.Meta](https://github.com/MetaCubeX/Clash.Meta) for Android

### Feature

Feature of [Clash.Meta](https://github.com/MetaCubeX/Clash.Meta)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.github.metacubex.clash.meta/)

### Requirement

- Android 5.0+ (minimum)
- Android 7.0+ (recommend)
- `armeabi-v7a` , `arm64-v8a`, `x86` or `x86_64` Architecture

### Build

1. Update submodules

   ```bash
   git submodule update --init --recursive
   ```

2. Install **OpenJDK 11**, **Android SDK**, **CMake** and **Golang**

3. Create `local.properties` in project root with

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

4. (Optional) Custom app package name. Add the following configuration to `local.properties`.

   ```properties
   # config your ownn applicationId, or it will be 'com.github.metacubex.clash'
   custom.application.id=com.my.compile.clash
   # remove application id suffix, or the applicaion id will be 'com.github.metacubex.clash.alpha'
   remove.suffix=true

5. Create `signing.properties` in project root with

   ```properties
   keystore.path=/path/to/keystore/file
   keystore.password=<key store password>
   key.alias=<key alias>
   key.password=<key password>
   ```

6. Build

   ```bash
   ./gradlew app:assembleAlphaRelease
   ```

### Automation

APP package name is `com.github.metacubex.clash.meta`

- Toggle Clash.Meta service status
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.TOGGLE_CLASH`
- Start Clash.Meta service
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.START_CLASH`
- Stop Clash.Meta service
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.STOP_CLASH`
- Import a profile
  - URL Scheme `clash://install-config?url=<encoded URI>` or `clashmeta://install-config?url=<encoded URI>`

### Contribution and Project Maintenance

#### Meta Kernel

- CMFA uses the kernel from `android-real` branch under `MetaCubeX/Clash.Meta`, which is a merge of the main `Alpha` branch and `android-open`.
  - If you want to contribute to the kernel, make PRs to `Alpha` branch of the Meta kernel repository.
  - If you want to contribute Android-specific patches to the kernel, make PRs to  `android-open` branch of the Meta kernel repository.

#### Maintenance

- When `MetaCubeX/Clash.Meta` kernel is updated to a new version, the `Update Dependencies` actions in this repo will be triggered automatically.
  - It will pull the new version of the meta kernel, update all the golang dependencies, and create a PR without manual intervention.
  - If there is any compile error in PR, you need to fix it before merging. Alternatively, you may merge the PR directly.
- 每次推送 `main` 分支都会自动触发 `Build Release` Action：
  - 构建 `MetaRelease` 的 universal、arm64-v8a、armeabi-v7a、x86 和 x86_64 全部 APK。
  - 从 APK 构建元数据读取 `versionName` 和 `versionCode`，自动创建带版本号和运行编号的 GitHub Release。
  - 将全部 APK 附加到 Draft Release；草稿仅对拥有仓库写权限的协作者可见，不会公开展示。
