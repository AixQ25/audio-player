# Vox 项目复盘

日期：2026-05-23（更新于 2026-05-27）

## 项目概述

一个专门用来听英文电影音频的 Android 播放器。核心功能是导入 MP3 和字幕、后台播放、字幕跟随、倍速和偏移调节。不做通用音乐播放器。

## 核心决策

先做 HTML UI 预览，确认设计后再迁入原生 Java。这个流程很有效——在 HTML 里调配色、布局、交互相比在 Android XML/Java 里改动快得多。后续 Spark 也复用了这个模式。

纯 Java 原生实现而不走 WebView 壳。Vox 的场景和密码管理器不同：播放器需要后台 Service、通知栏控制、MediaSession，这些在 WebView 壳里做不到或者做起来很别扭。这个判断是对的——Vox 的原生体验明显比密码管理器好。

复用密码管理器的手动 APK 构建脚本模式，只改了项目名和包名。这是密码管理器项目最有价值的产出之一——后面所有 Android 项目都用同一套 aapt2+javac+d8+zipalign+apksigner 流水线。

## 踩过的坑

字幕解析的坑最多。SRT 有不同编码（UTF-8、GBK、带 BOM 等），VTT 和 ASS 的格式又完全不一样。最初只处理了标准 UTF-8 SRT，导入实际文件后各种解析失败。后来加了编码检测和多格式兼容，才稳定下来。

系统主题字体会影响 UI。手机用的主题字体是手写体，应用到播放器界面上看起来很奇怪。加了字体优先级处理（MiSansVF → NotoSansCJK → Roboto）才解决。

退出重进后进度条归零但音频实际在正确位置。原因是 UI 初始化和 Service 广播之间的时序问题——UI 创建时用默认值渲染，Service 的广播回来后才更新。修这个花了不少时间，最后是两个方向同时改：初始化时读持久化值，收到广播再做一次同步。

## 学到了什么

用 HTML 做 UI 预览再迁入原生是个好模式，后面 Spark 直接照搬了。这个模式省掉了在原生代码里反复调整布局的苦工。

手动构建脚本（build-apk.ps1）的复用性比预想的高。密码管理器、Vox、Spark 三个项目用的本质上是同一套脚本，只改了几个变量。这是值得持续维护的基础设施。

需求收敛这一步不能省。一开始就想清楚"只听英文电影、不做通用播放器"，后续所有功能自然围绕这个核心展开，没有跑偏。

## ExoPlayer 迁移（2026-05-25 ~ 2026-05-27）

当初决定把 MediaPlayer 换成 ExoPlayer 是为了解决 VBR MP3 的时间偏差。MediaPlayer 用字节位置除以平均比特率估算时间，VBR 各段比特率差异大，累计偏差能到 37 秒。ExoPlayer 用帧级 PTS，理论上应该准确。

迁移过程踩了五个坑：

D8 构建参数在 PowerShell 里嵌套数组会被合并成单个字符串，导致 D8 报路径错误。用 `+` 扁平拼接解决。

Guava 缺失导致 6 个 desugaring 警告。Media3 内部用了 Guava 的函数式接口（Function、Predicate 等），不加 Guava 能编译但行为不稳定。

NoClassDefFoundError: CircularIntArray 是最隐蔽的——Media3 exoplayer 依赖 AndroidX Collection 的 CircularIntArray 类，但 Media3 POM 声明的是运行时依赖，手动下载时很容易漏掉。当时我盯着 `.aar` 扩展名去 Google Maven 试了好几个版本都 404，最后才发现 collection 和 annotation 就是纯 JAR 而不是 AAR。

最麻烦的是暂停后播放无响应。Player 卡在 STATE_BUFFERING，playWhenReady 是 true 但永远到不了 STATE_READY。排查了很久才找到根因：setAudioAttributes(attrs, true) 让 ExoPlayer 自动管音频焦点，但 app 自己也在手动管理。两边打架，当 app 放弃焦点时 ExoPlayer 内部暂停了播放器，之后 resume 就冲突了。改成 setAudioAttributes(attrs, false) 就好了。

这些都修完后，用户测试发现时间偏差还在，38 秒的漂移没消失。深入分析 MP3 文件的内部结构才发现：问题不在播放引擎，在 MP3 文件的元数据。Bilibili XCoder 处理过的 MP3 文件 LAME tag 错误地把 VBR 标为 CBR，Xing TOC 在后半段偏差超过 120 秒。PC 播放器（VLC）用的是这个错误 TOC 来显示时间，用户手动调的字幕对齐的是 VLC 的错误时间轴。ExoPlayer 的帧级 PTS 其实是准确的，反而是“正确的那个”。

最终用户把 VBR MP3 转了 CBR，问题直接消失。这个教训很重要：当两个播放器显示不同时间时，不一定是哪个引擎更好，大概率是文件本身的元数据有问题。花了好几个小时分析引擎差异，最后发现修文件比修代码简单得多。

## 后续改进

UI 还有些细节可以打磨，但不是急事。更值得做的是字幕文件自动关联——目前每次导入音频后要手动导入字幕，如果音频和字幕文件名一致就自动匹配，会顺手很多。

ExoPlayer 迁移值得总结的经验：手动管理依赖是无 Gradle 项目的软肋。Media3 一个模块拖了 4 个 AndroidX 运行时依赖加 Guava，光靠试错补完需要很长时间。如果有下次，应该先写个脚本解析 POM 文件自动拉依赖树。
