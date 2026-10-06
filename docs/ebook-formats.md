# 电子书导入

书架选择「导入书籍 → 选择书籍文件」，直接选原书。普通书不需要转成自定义 JSON，也不需要先交给 AI 处理。导入先预览，确认后进入书架。

| 格式 | 当前读取方式 | 边界 |
| --- | --- | --- |
| EPUB 2 / 3 | 按 OPF spine 保留前言、正文及文后注释；读取 nav / NCX 目录层级、文件内锚点；提取声明的封面；正文注释标记打开注释框 | 正文仍为文字阅读，内文插图、复杂 CSS、音视频未完整还原；SVG 封面不能直接解码 |
| PDF | 提取文字，按页分节；读取书签目录，首页生成封面；书内「原页」查看原排版和图片 | 多栏、公式的正文提取可能不准确，可切到原页；页面脚注不保证自动建立弹框关联 |
| 扫描 / 图片 PDF | 保存原文件、显示原页，点击后双指缩放 | 尚无 OCR，图片上的文字不能查词、选句或配中文 |
| MOBI / AZW / AZW3 / PRC | libmobi 重建正文，支持 PalmDOC、HUFF/CDIC、KF8；提取封面及 NCX，按重建文件的链接关联注释框 | 未加密的文字书；插图、复杂样式未完整还原；无有效链接的纯文本脚注不能自动关联；不包含 KFX、Topaz 和 Print Replica |
| TXT | 按非空行提取段落，识别常见章节标题 | 支持 UTF-8、UTF-16、GBK / GB18030；无声明的其他传统编码不能保证自动判断 |
| FB2 / FB2.ZIP / FB2ZIP | 提取书名、作者、分节、诗行、内嵌封面及 notes 正文；type=note 引用打开注释框 | 正文为文字阅读，内文插图未还原 |
| DOCX | 提取标题、正文、表格、换行、制表符及 footnotes / endnotes；正文标记打开注释框，注释正文保留在文后 | 不还原 Word 页面排版、插图、复杂修订和样式；不包含旧 DOC；不把任意图片猜成封面 |
| RTF | 提取正文、Unicode、代码页、分段及转义字符 | 忽略字体表、图片、对象和页眉页脚，不完整还原样式 |
| HTML / 书籍 JSON | 保留原有导入能力 | HTML 属于辅助兼容格式 |

正文中夹有另一种语言、但不能可靠生成双语对应时，保留完整正文，仍能导入阅读。已有明确的双语引用若缺失或重叠，仍会报错。

DRM 加密书无法直接读取；需要密码的 PDF 尚无密码输入界面。KFX、Topaz、DjVu、CHM、旧 DOC、CBZ/CBR 等尚未实现，不能宣称支持所有类型。

当前文件上限为 32 MB；EPUB 解压总上限为 100 MB、单条目 16 MB，Kindle 重建正文上限为 64 MB。大幅画册和超大扫描件仍是后续工作。

目录显示原书标题和层级，不额外给前言、版权页、目录、注释等条目编号成第几章。目录位置保存到书籍数据，重新打开后保留。缺失的目录锚点不伪造为正文开头；对应文件仍保留可阅读入口。

注释关联来自 EPUB noteref / footnote、FB2 note、DOCX 脚注尾注关系，以及常见上标数字和命名脚注锚点。正文标记带下划线，点击只打开可滚动的注释框，不改变阅读位置；普通章节链接、外部链接和注释返回链接不会被当作注释。无链接关系的纯文字注释不能保证自动关联。旧书架缓存需要从原文件重新导入才能获得新增的封面、目录和注释信息。

## 验证

- JVM：编码、EPUB 实体 / 不规范标签 / 混合容器 / 诗行、EPUB 2/3 目录锚点与封面资源、跨文件注释、FB2 封面与注释、DOCX 脚注尾注、Kindle 重建目录、RTF Unicode / GBK、已有双语格式。
- Android：EPUB 封面保存、目录和注释保存重开、点击注释框且阅读位置不变、搜索跨章跳转、单字点选与短拖选；PDF 书签层级、封面和原页；libmobi 六份真实样本覆盖 CP1252、PalmDOC、未压缩、HUFF/CDIC、KF8/AZW3、NCX 嵌套目录，另验证 DRM 提示。
- Kindle 测试文件只通过 adb 放入设备 `qa-ebook-formats` 目录，不随应用分发，也不写入用户书架。

运行：`:app:testDebugUnitTest`；设备测试类：`EbookFormatsDeviceTest`、`ReaderSearchSelectionTest`。设备测试使用 `adb install -r` 安装主 APK 和测试 APK，再通过 `adb shell am instrument -w -e class ... com.jknl.englishreader.test/androidx.test.runner.AndroidJUnitRunner` 直接运行。不要在日常使用的手机上运行当前配置的 `connectedDebugAndroidTest`，它会在测试结束后卸载应用。代码和编译通过不等于任意实际电子书都已验证。

2026-10-06 本轮结果：74 项 JVM 测试通过；直接运行的 7 项设备测试通过（阅读体验 3 项、导入格式 4 项），主应用和测试包构建通过，主应用已安装到连接的 Redmi Note 7 Pro。`lintDebug` 仍因既有 `MainActivity.dispatchKeyEvent` 的 2 处 `RestrictedApi` 报错失败，本轮未修改该音量键实现。
