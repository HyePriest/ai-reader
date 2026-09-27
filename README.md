# ai reader

**把 AI 放进阅读过程：导入时整理书籍，阅读时直接围绕原文提问。**

一款 Kotlin / Jetpack Compose 编写的 Android 中英文阅读器。支持本地书籍、英文与现成中文译本对照，以及使用自己的 DeepSeek API Key 查词和讨论选文。

> 首个公开预览版：v0.1.0。核心功能已经实现，仍欢迎真实阅读场景中的反馈。

[下载安装包](https://github.com/HyePriest/ai-reader/releases/tag/v0.1.0) · [导入提示词与 JSON 格式](docs/ai-book-preparation.md) · [反馈问题](https://github.com/HyePriest/ai-reader/issues)

## 能做什么

- **中英文阅读**：导入 EPUB、HTML、TXT 或书籍 JSON，连续滚动、章节跳转、保存阅读位置，调整字号、行距和配色。
- **现成译本对照**：让外部 AI 匹配英文原书与中文译本，生成一个 JSON 后导入；缺译可补译并标记为「AI 补译」。阅读时点英文查看本地中文，不需要请求翻译 API。
- **直接问 AI**：在阅读页按住音量减约 0.3 秒，拖动框选文字；松手后填写问题或选择常用问题，支持继续追问和停止请求。没有常驻 AI 图标，不震动，音量仍正常降低。
- **语境查词**：长按英文单词，查看语境义、常见义项，以及按考研、通用英语或原著阅读目标给出的记忆建议。
- **生词本**：最多 5000 条查词历史；同一语境合并，不同句子分别保留。收藏按词元归并，释义在前，原句、语境含义及出处在后，可导出 JSON。
- **星空书架**：查看书籍进度、阅读时长，自选封面。

## 开始使用

1. 从 [Releases](https://github.com/HyePriest/ai-reader/releases) 下载 APK，安装到 Android 7.0 或更高版本。仓库不提供共享 API Key。
2. 可以先阅读应用内的原创双语短篇，再导入自己的书。书架 → **导入 → 查看并复制提示词 · JSON 示例**，选择「双语书」或「纯中文书」。
3. 把提示词和原始文件交给你使用的 AI 工具，取得完整 JSON，再回到阅读器选择该文件。只有英文或中文的 EPUB、TXT、HTML 也可以直接导入。
4. 在阅读设置的 **DeepSeek** 区域填写自己的 API Key，选择模型。查词和选文提问会产生你自己账户的 API 用量；普通阅读和已有译文查看可离线使用。
5. 阅读时短按英文显示已有译文，长按单词查词；按住音量减进入框选，再按「发送」提问。返回键退出选文模式。

导入时的 AI 整理发生在阅读器之外。App 不会在手机上自动扫描两个原始文件并调用模型完成整书匹配。示例文件见 [examples](examples)。

## 当前边界

- 框选支持当前屏幕的连续文字与同屏追加；画圈识别、跨屏框选尚未实现。
- 问答面板关闭后不保存对话；请求附带选文、前面最多 3000 字符的上下文及最近六轮问答，不上传截图或后续正文。模型仍可能误解或剧透，回答应结合原文判断。
- 一次选文最多 12000 字符，问题最多 2000 字符。联网前需自行配置 DeepSeek。
- 目录目前只保留章节跳转；整书补中文和旧版补译入口暂时隐藏，相关实现保留。
- 长书分批补充、复杂图表、完整排版、脚注跳转和完整备份恢复界面尚未完善。请保留原始书籍文件。
- 已在 Redmi Note 7 Pro 使用与检查部分阅读流程；物理音量键、框选提问及旧查词数据迁移尚未完成全面真机验证。发布检查以构建、签名和新增核心测试为主，不代表所有功能已完整验证。

## 数据与隐私

书籍、阅读位置、译文、查词历史和生词本保存在应用本地。应用没有自建账户、统计服务或广告 SDK。联网请求直接发送到 DeepSeek；查词会发送单词和句子，选文提问会发送上面说明的文本范围。API Key 使用 Android Keystore 加密并放在不参与 Android 备份的目录，不写入书籍或导出文件。

Android 系统可能按设备设置备份其他应用数据。卸载或清除数据会丢失本地内容；同包名、同签名覆盖更新可保留数据。

## 开发与构建

使用 Android Studio 或命令行，准备 JDK 25、Android SDK Platform 37 与 Build Tools 36.0.0。Gradle Wrapper 随仓库提供；首次构建需要联网下载依赖。

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests com.jknl.englishreader.AiReaderTest
```

Windows 使用 `gradlew.bat`。SDK 路径通过本地 `local.properties` 或 `ANDROID_HOME` 设置。调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

[正式签名与更新说明](docs/releasing.md) · [双语 EPUB 格式](docs/bilingual-epub-format.md)

应用显示名称为 `ai reader`；为保持原数据兼容，包名仍为 `com.jknl.englishreader`，书籍 JSON 标识仍为 `englishreader.book.v1`。

## 开源与素材

项目源代码及原创示例按 [MIT License](LICENSE) 开源。依赖库保留各自许可证，见 [第三方说明](THIRD_PARTY_NOTICES.md)。仓库不包含个人书籍、密钥、阅读记录或查词数据；请使用自己有权处理的书籍文件。
