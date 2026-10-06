# 发布与签名

公开预览版使用维护者专用的正式签名。私钥、密码不放入仓库或 Release。

## 本地构建正式包

设置下面的环境变量（通过本地终端或 CI Secrets，不要写进源码）：

- `AI_READER_KEYSTORE`：签名文件绝对路径。
- `AI_READER_STORE_PASSWORD`：签名库密码。
- `AI_READER_KEY_ALIAS`：密钥别名。
- `AI_READER_KEY_PASSWORD`：密钥密码。

随后运行 `./gradlew :app:assembleRelease`（Windows 为 `gradlew.bat`）。输出位于 `app/build/outputs/apk/release/`。没有配置签名时生成未签名包，不能作为可安装的正式发布包上传。

发布时提供正式签名的 APK，确保 Release 对应同一个源码标签。以后沿用同一私钥并增加 versionCode，才能覆盖更新。不附加哈希文件或证书指纹。

## 调试版与公开版

自行构建的 debug APK 和公开 Release APK 使用不同签名，即使包名相同也不能直接互相覆盖。已经使用开发调试版的用户应先保留原书文件并导出生词本；应用尚无完整阅读进度备份恢复界面，不要为了试装公开版直接卸载现有版本。维护者可以继续在原设备使用调试构建。

## 发布内容

发布仓库仅收录应用源码、公开测试、构建配置、格式文档和原创示例。个人书籍导入脚本、本机 IDE 设置、内部验证笔记、APK 构建缓存、签名文件不发布。APK 作为 Release 附件提供。
