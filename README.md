# ManJiao

快手功能性 Xposed 模块。

## 功能

- **内容过滤** — 广告 / AI 生成内容 / 直播 / 图文 / 短剧等信息流净化
- **界面隐藏** — 顶栏 / 侧边栏 / 底栏 / 昵称 / 金币浮窗 / 合集等元素隐藏
- **性能优化** — native hook 拦截刷屏日志，减少无意义 I/O 与上下文切换
- **播放控制** — 双击点赞 / 双击打开菜单 / 手势控制

## 技术栈

- Kotlin + Xposed API
- Native Hook: [Dobby](https://github.com/jmpews/Dobby)（源码内联，arm64-v8a）
- CMake + NDK 构建 `libloghook.so`
- 液态玻璃质感菜单 UI

## 构建

```bash
gradlew assembleDebug
```

需 Android SDK + NDK 26+

## 使用

1. 安装至 LSPosed / Xposed 环境
2. 启用模块，作用域勾选目标应用
3. 重启目标应用，通过模块菜单或应用内设置页配置各开关
