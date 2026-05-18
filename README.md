# Native Movie Audio Listener

这是音频播放器的原生 Android MVP。它刻意保持很窄的范围：

- 手动导入 MP3 文件。
- 给当前 MP3 导入 `.srt` / `.vtt` 字幕文件。
- 后台播放，锁屏和切出应用后继续播放。
- 保存当前文件播放进度。
- 统计当前文件收听时长和总收听时长。
- 通知栏提供播放、暂停、前后跳转控制。

当前版本不做音乐库扫描、账号、云同步、歌词库、推荐、复杂歌单和均衡器。

## 构建

运行：

```powershell
.\build-apk.ps1
```

APK 输出到：

```text
dist\movie-audio-listener-debug.apk
```

构建脚本沿用本机 Android SDK 和 Android Studio JBR，不依赖 Gradle。
