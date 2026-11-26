# Nascence 永恒新生

Nascence 是一个基于 [Termux](https://github.com/termux/termux-app) 的 Android 终端应用，进行了现代化改造和美化。

致力于制作一个无冗余功能的美化 Termux 分支

> **与 Termux 签名不兼容，需要重新安装 / 使用核心破解覆盖安装**

## 特性

### 现代化终端与渲染
- 内置 JetBrains Mono + Noto Sans CJK，中文不再拉伸
- 默认启用 `enforce-char-based-input`，Gboard 等输入法（含实体键盘）可正常组合输入中文/日文/韩文；需要旧的原始按键行为时可在 `termux.properties` 里设为 `false`
- App 跟随 `~/.termux/font.ttf`
- OSC 8 可点击超链接、OSC 7 当前目录、OSC 133 提示符标记
- OSC 9, 99, 777 通知，OSC 9;4 任务进度
- Kitty 键盘协议子集
- 连字 (在兼容的字体上) 、下划线样式、SGR 58 下划线颜色、DECSET 2026 同步输出
- 更好的查找历史文本
- 类 Neovide 光标动画、光标发光、平滑滚动、滚动到底部按钮

### 外观
- 无需 Termux:Styling 的 12 套内置主题
- 无需 Termux:Styling 自定义壁纸，放置在 `~/.termux/background.*` 或 `wallpaper.*`
- 侧栏半透明；状态栏与导航栏透明

### 操作
- 音量上：开关侧栏
- 音量下短按：开关软键盘；长按：Ctrl

### 下拉终端
- 入口：快捷设置磁贴、常驻通知
- 顶部半屏面板，背景模糊
- 也许可以拿来挂 Agent

### 更好的通知
- 显示最近会话列表
- 操作：新会话、悬浮终端、唤醒锁

## 与 Termux 的关系

- 基于 [termux/termux-app](https://github.com/termux/termux-app)，使用 `com.termux` 包名，与 Termux 插件完全兼容
- 上游的 F-Droid、Google Play 和官方发布流程不适用于本仓库

## 许可证

- 仓库整体遵循 [GPLv3](LICENSE.md)
- 终端模拟器部分沿用 Apache 2.0，见 [`terminal-view`](terminal-view) 和 [`terminal-emulator`](terminal-emulator)
- 字体：JetBrains Mono（OFL）、Noto Sans CJK（OFL），许可证见 [`LICENSES`](LICENSES)

## 致谢

- [Termux](https://github.com/termux/termux-app) 及其所有贡献者
- JetBrains Mono、Noto Sans CJK
