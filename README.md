# 葱伴英语 · English Book

一个原生 Android 英语学习应用：从初中基础衔接大学英语四级。Kotlin + Jetpack Compose，独立背词、阅读和听写页面；傲娇学习搭档「凛」使用用户自己配置的 OpenAI 兼容接口。

**这是 0.1.0 验证版，不是 WebView 套壳，也不是完整四级题库。**

## 当前实现

- 原生对话首页、抽屉导航、独立学习页面、系统深浅主题、原创矢量角色头像。
- 100 个起步词、3 篇原创分级阅读、40 个例句听写；真实答题、SQLite 持久化和间隔复习。每日最多安排 20 个新词，到期词优先。
- 120 分钟学习目标，前期词汇 70 分钟。只计算前台练习页且最近 90 秒内有互动的时间。
- 自填 HTTPS 基础地址、密钥、聊天模型，可选教学与记忆模型；限定上下文长度，结合真实学习统计与可编辑记忆。
- 系统英语 TTS 和兼容 OpenAI `/audio/speech` 的 MP3 语音 API，音频缓存约 64 MiB 上限。
- 本地 WorkManager 随机提醒 4 次及计划在 21:30 的回顾，09:00–22:00 之外不显示；完成目标后取消随机催学。
- 可选自建服务器：同步汇总进度、生成个性化文案、持久化提醒收件箱、手机后台拉取。
- Android Keystore 加密接口配置；学习备份不含接口密钥。关闭系统自动备份。

## 尚需完成 / 真机验证

- MOSS-TTS-Nano **尚未集成**。目前不要下载模型，使用系统 TTS 或语音 API。魅族 21 / Android 16 上的 MOSS 速度、内存和发热还需单独实验。
- 服务器尚未部署，缺少实际 SSH/域名配置。不是魅族厂商推送，强行停止应用后无法保证通知；后台和 21:30 时间可能受省电策略延迟。
- 内置内容为原创教学样例，不含历年四级试卷、听力原录音或完整四级词库。未提供发音评分或语音识别。
- 还没有生产签名发布。云端生成的是测试 APK；系统英语语音包是否可用取决于设备。

## 下载 / 构建 APK

仓库的 **Actions → Android APK** 会在 `main` 和 `codex/**` 分支提交时运行，也可手动触发。构建成功后在该次运行的 Artifacts 下载 **EnglishBook-debug-apk**，解压得到 `app-debug.apk`，传到安卓手机安装。

工作流使用 JDK 17、Gradle 8.13、AGP 8.11.1、Android SDK 36；执行单元测试、lint、APK 打包，以及 API 35 模拟器原生操作和截图检查。无需在自己的电脑安装 Android Studio。

云端缓存开发签名，缓存失效后可能重新生成并导致旧测试版无法覆盖安装；卸载前先导出学习备份。正式分发前必须配置固定的私有发布签名，不能把签名私钥提交进仓库。

如本地已有 JDK 17 与 Android SDK，可以用 `./gradlew testDebugUnitTest lintDebug assembleDebug`（Windows 使用 `gradlew.bat`）。

## 初次使用

1. 不填接口也可以直接背词、阅读和听写（需可用的系统英语 TTS）。
2. 设置 → 填写中转基础地址（例如 `https://example.com/v1`）、API Key、准确模型 ID → 保存并测试 AI。
3. 教学和记忆模型留空时复用聊天模型。每条消息不会同时调用所有模型。
4. 要使用语音 API，单独填写支持 `/audio/speech` 的服务与音色；API 调用由对应供应商计费。
5. 主动开启通知权限并保存设置。在魅族系统中检查此应用的通知与后台省电设置。
6. 「它记住了什么」可以编辑、清空或让 AI 整理记忆；学习记录页查看程序实际记录的进度。

当前问题、近几轮聊天、学习汇总和文字记忆会发送给用户配置的 AI 服务。服务器模式只上传学习汇总、日期、时区、易错词和提醒开关；手机的 AI 密钥不会自动上传。普通备份可能含个人聊天文字，请自行保管。

## 服务器

见 [部署说明](docs/server-deployment.md) 和 [服务器 API](server/README.md)。Python 标准库 + SQLite，无模型驻留，适合先在小型服务器试运行；具体性能尚未在用户的 2 核 2GB 服务器测量。

## 参考与许可

- 界面方向参考 [Gemini 官方展示](https://blog.google/innovation-and-ai/products/gemini-app/next-evolution-gemini-app/)，没有使用 Google 品牌资产。
- 架构参考 Android 官方 [Compose](https://developer.android.com/develop/ui/compose)、[WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) 文档；调研了 [GPT Mobile](https://github.com/egorpariy/gpt-mobile) 的原生多接口思路，没有复制其源码。
- MOSS 后续评估参考 [OpenMOSS/MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano)。
- Gradle Wrapper 来自 Gradle 官方 v8.13.0，Apache-2.0；AndroidX / Material 为 Apache-2.0，OkHttp 为 Apache-2.0，Kotlin / kotlinx.coroutines 为 Apache-2.0。项目自有代码按仓库 MIT 许可。

详见 [设计与验证记录](docs/implementation.md)。
