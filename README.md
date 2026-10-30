# BinoXR

PICO 头显本地空间图片浏览与 AI 立体转换应用。当前工程为 Kotlin / Jetpack Compose + PICO Spatial SDK 的 Android Gradle 工程。

## PICO Creator Center 申报说明

- 平台技术栈分类：Android Spatial；实际开发技术为 Kotlin、Jetpack Compose 和 PICO Spatial SDK。
- 交互能力：手势追踪（捏合唤回 Dock）、手柄交互（摇杆左右切图）。
- 本次 V1.0.1 为申报资料修订，补充技术栈和交互说明，应用功能、APK 版本号保持不变。

## 当前功能

- Waterfall 瀑布流与 Files 文件夹浏览。
- 2D、180° 双目鱼眼、360° 单目与 360° 双目图片识别及观看。
- Shared Space 预览和 Full Space 沉浸观看，Dock 缩略图及手柄摇杆切图。
- 当前 2D 图片或文件夹批量转换为 180° 双目鱼眼；默认单眼 2048、双目 4096×2048。
- 本地 Depth Anything V2 Small ONNX 推理、PCV2 合成、进度反馈和已生成结果复用。
- 当前图转换完成后自动切换，新图与原图相邻排列。

## 在另一台电脑上拉取

先安装 Git、Git LFS 和 Android Studio。本仓库已公开，可直接克隆。

```sh
git lfs install
git clone https://github.com/pengfei2028-arch/BinoXR.git
cd BinoXR
git lfs pull
git lfs fsck
```

应用构建和运行所需的图标、ONNX 模型及图片直接存储在 Git 中，普通克隆即可获取完整内容。宣传资料、历史设计图、Blender 文件和 USDZ 仍使用 Git LFS；上述 LFS 命令用于获取这些非当前应用构建必需的资料。

## 构建

1. 用 Android Studio 打开仓库根目录。
2. 安装 Android SDK Platform 35，配置能运行 AGP 8.13.2 / Gradle Wrapper 的 JDK（推荐 Android Studio 内置 JBR）。
3. 通过 Android Studio 配置 SDK 路径；`local.properties` 仅保留在各电脑本地，不提交。
4. Gradle 同步需要访问 Google、Maven Central 和 `settings.gradle.kts` 指定的 PICO/Volcengine 制品源。当前应用构建不需要安装 Spatial Editor，也不需要配置 `spatial.tools.dir` 或 `PICO_HOME`。
5. 在项目根目录执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS/Linux 使用 `./gradlew`。APK 输出位置为 `app/build/outputs/apk/debug/app-debug.apk`。

### PICO Lab / CI Release 构建

配置 JDK 和 Android SDK（API 35）后运行（当前 APK 构建不依赖 Git LFS 下载）：

```sh
./gradlew --no-daemon --console=plain assembleRelease --stacktrace
```

Release 输出位于 `app/build/outputs/apk/release/`。仓库不包含发布签名配置，签名由发布平台或发布者提供。

部分云端构建器只检出 Git 文件，不会下载 Git LFS 对象。若将 Android 图标交给 LFS，检出的 `.png` 可能是文本指针，导致 `mergeReleaseResources` / AAPT 编译失败；模型指针还可能被原样打入 APK，导致运行时推理失败。因此 `.gitattributes` 将 `app/src/main/` 下的 PNG、JPG 和 ONNX 排除出 LFS，确保应用资源随源码完整交付。新增大型运行资源时，应检查 Git 平台的单文件大小限制。

早期 `editor-asset` 模块使用 Spatial Tools 插件 6.1.0，即使没有执行场景打包任务，也会在 Gradle 配置阶段要求安装 Spatial Editor。当前 `FullSpacePage` 使用 `app/src/main/assets/stereo/` 中的 OBJ 网格，并通过代码创建实体，不再加载该模块的 `MyScene` 或 `editor-asset.bundle`。因此该历史模块保留源码，但已从当前构建依赖中移除；PICO Spatial SDK 6.1.9 的运行时依赖保持不变。

应用当前只配置 `arm64-v8a`，最低 Android API 35。XR 交互、双目效果和流畅性应在支持的 PICO Swan 真机验证，桌面构建通过不代表已完成真机验收。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | 应用源码、测试、图片、模型和观看网格 |
| `editor-asset/` | 历史 PICO Spatial 示例场景，保留源码，不参与当前应用构建 |
| `tools/` | 网格生成脚本、Blender 源文件 |
| `design/` | 历史 Logo 概念资料 |
| `screenshots/` | 已有截图 |
| `BinoXR-company-intro/` | 当前宣传网页及资源 |
| `MILESTONE_0_VALIDATION.md` | 历史阶段验证记录 |
| `STEREO_FORMAT_RULES.md` | 格式说明（部分内容为设计目标，以实际代码为准） |
| `THIRD_PARTY_NOTICES.md` | 已有第三方说明 |

旧 APK、构建缓存、IDE 配置、签名文件、重复交接包不纳入版本控制；原有本地文件不会因此删除。

本仓库为当前工程的首次 Git 快照，不包含此前开发过程的提交历史。
