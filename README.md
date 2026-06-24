# 摸鱼浏览器 / Fish Browser

一个 IntelliJ IDEA 插件：在 IDE 上浮一个**半透明、可调透明度、置顶**的浏览器窗口。
一个快捷键就能在「网页交互」和「代码交互」之间切换，外加「老板键」一键隐藏。

> 灵感来自老的「背景看小说/视频摸鱼插件」，但把内容换成了**完整可交互的浏览器**（基于 IDE 内置的 JCEF / Chromium）。

## 功能

| 功能 | 说明 |
|------|------|
| 两种显示方式 | **浮窗** / **背景**（盖住代码编辑区），一键互转（见下） |
| 半透明置顶浏览器 | 无边框悬浮窗，始终置顶，整窗透明度可调（离屏渲染 OSR 实现，代码会透出来） |
| 网页 / 代码模式切换 | `Ctrl + \`` 一键切换。**代码模式**下窗口变暗 + **鼠标穿透**，点击/输入直接落到你的编辑器上；**网页模式**下正常浏览 |
| 老板键 | `Ctrl + Shift + \`` 一键显示 / 隐藏 |
| 设置页 | `Settings → Tools → 摸鱼浏览器`：默认显示方式、各档透明度、主页等 |
| 工具栏 | 两行：前进/后退/刷新/主页 + 满宽地址栏；透明度滑块、模式、置顶、隐藏 |
| 记忆 | 记住上次的网址、透明度、显示方式、窗口位置和大小 |

> 注：`\`` 是键盘左上角 Esc 下方的**反引号**键。

## 显示方式（浮窗 ⇄ 背景）

| 方式 | 观感 |
|------|------|
| **浮窗** | 可拖动/缩放的悬浮窗。`Ctrl+\`` 切网页/代码交互；不够透挡住代码时就「变背景」 |
| **背景** | 由浮窗最小化而来：**只盖住当前代码编辑区**（不盖项目树/标签/工具窗），变暗、默认鼠标穿透，在上面照常写代码；`Ctrl+\`` 开关穿透 |

切换：工具栏 **`变背景` / `还原`** 按钮，或 **`Ctrl + Alt + \``**。背景边界在切换那一刻按当前编辑器计算（改了布局就再切一次重新贴合）。

## 快捷键

| 快捷键 | 作用 |
|--------|------|
| `Ctrl + \`` | 切换交互：网页 ⇄ 代码（代码=变暗+鼠标穿透）。背景模式下也用它开关穿透 |
| `Ctrl + Alt + \`` | 浮窗 ⇄ 背景（最小化为背景 / 还原为浮窗） |
| `Ctrl + Shift + \`` | 老板键：显示 / 隐藏 |

快捷键由插件用一个 JVM 级 `KeyEventDispatcher` 实现，所以无论焦点在编辑器还是浏览器里都能触发
（仅在 IDE 进程持有系统焦点时生效；真正的 OS 全局热键需要原生钩子，本版本未做）。
菜单 `Tools → 摸鱼浏览器` 里也有对应入口，可直接点。

> 浮窗里现在用的是**可获得键盘焦点的无边框窗口（utility JFrame）**，所以地址栏能正常输入；
> 早期用 `JWindow` 时点了地址栏也输入不进去（焦点抢不过编辑器）。

## 环境要求

- IntelliJ IDEA **2024.2+**（`since-build = 242`，并对更高版本开放；已在 build 261 / 2026.1 上目标编译）。
- 运行时必须是 **JetBrains Runtime (JBR)**，因为浏览器用的是 JBR 自带的 JCEF。用官方安装包/Toolbox 装的 IDEA 默认就是 JBR，无需额外配置。
- **鼠标穿透是 Windows 专属**（用 JNA 调 `WS_EX_TRANSPARENT`）。非 Windows 上「代码模式」仍会变暗，但不会穿透（会优雅降级，不报错）。

## 构建 & 运行

项目用 **Gradle + IntelliJ Platform Gradle Plugin 2.x**，并通过 `local(...)` 指向**本机已安装的 IDEA**（省掉 ~1GB SDK 下载）。

> ⚠️ 换机器时，请把 `build.gradle.kts` 里的
> `local("D:/idea/IntelliJ IDEA 2024.2")`
> 改成你自己的 IDEA 安装路径。

```bash
# 必须用 JDK 21 构建：IDEA 2026.1(build 261) 的 jar 是 Java 21 字节码，JDK 17 读不了
# PowerShell:
$env:JAVA_HOME="D:\jdk21"
# 网络若走 TLS 拦截代理(本机情况)，加这句让 JVM 用 Windows 证书库，否则下载报 PKIX 错：
$env:GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"

# 在沙箱 IDE 里跑起来看效果（最直观）
gradlew runIde

# 只编译
gradlew compileJava

# 打包成可安装的 zip（产物在 build/distributions/）
gradlew buildPlugin
```

安装：`gradlew buildPlugin` 后，在 IDEA 里
`Settings → Plugins → ⚙ → Install Plugin from Disk...` 选 `build/distributions/*.zip`。

## 使用

1. `Tools → 摸鱼浏览器 → 显示 / 隐藏`，或按 `Ctrl + Shift + \``。
2. 地址栏输入网址回车（不带 `http` 也行，纯文字会走必应搜索）。
3. 拖工具栏左侧的 `⣿` 移动窗口，拖右下角 `◢` 调整大小。
4. 用透明度滑块调到合适的「若隐若现」。
5. 老板来了？`Ctrl + Shift + \`` 秒隐；想边看边写代码？`Ctrl + \`` 切到代码模式。

## 已知限制 / 后续可做

- **真·编辑器背景**（网页画在代码*下面*、代码 100% 清晰）试过一版（靠 `IdeBackgroundUtil` 定时刷背景图），不够稳定已移除。现在的「背景」是「浮在代码*上面* + 半透明 + 穿透」，效果等价且可交互。
- 背景边界在切换那一刻计算，不会自动跟随之后的布局变化（开工具窗/分屏后再切一次即可重新贴合）。
- 鼠标穿透仅 Windows。
- OSR 模式对受 DRM 保护的视频（如某些流媒体）可能无法播放；普通网页 / B 站 / 大多数视频站没问题。
- 快捷键写死为 `Ctrl+\`` / `Ctrl+Alt+\`` / `Ctrl+Shift+\``，后续可加自定义。

## 代码结构

```
src/main/java/com/moyu/fishbrowser/
├── DisplayMode.java              # 显示方式枚举：FLOATING / COVER(背景)
├── FishBrowserAppListener.java   # 启动时安装全局快捷键
├── FishBrowserService.java       # 应用级服务：浮窗/背景切换、算编辑器边界
├── FishBrowserOverlay.java       # 窗口本体：JCEF(OSR) + 两行工具栏 + 拖动/缩放 + 网页/代码模式
├── FishBrowserSettings.java      # 持久化设置（网址/各档透明度/窗口位置/显示方式）
├── GlobalHotkeys.java            # KeyEventDispatcher 实现的三个热键
├── win/Win32ClickThrough.java    # Windows 鼠标穿透（JNA，优雅降级）
├── settings/FishBrowserConfigurable.java  # 设置页（Settings → Tools → 摸鱼浏览器）
└── actions/                      # Tools 菜单：显示隐藏 / 交互切换 / 浮窗背景 / 设置
```
