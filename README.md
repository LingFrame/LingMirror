# LingMirror

[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
LingMirror is a static diagnostic tool for Java ClassLoader leaks.

**灵镜 LingMirror** — 专注 Java 类加载器泄漏的静态诊断工具

点一下，60 秒找出让你 ClassLoader 永远卸载不掉的那条代码引用链。

### 为什么需要灵镜

在插件化系统、热部署、SPI 动态加载等场景中，ClassLoader 泄漏是最隐蔽也最致命的问题之一：

- 应用/模块卸载后 ClassLoader 仍被 GC Root 间接引用，Metaspace 持续增长
- 线上表现为神秘的 `java.lang.OutOfMemoryError: Metaspace`，重启后消失、过段时间复现
- 传统内存分析工具只能看到结果（哪些 ClassLoader 活着），无法定位根因（哪条引用链锁死了它）

灵镜从**源码层面**静态分析泄漏根因，直接定位到具体的代码行和引用链。

### 检测规则（18 条）

#### CR 系列 — ClassLoader 锁死根因

| 规则 | 名称 | 级别 | 检测内容 |
|------|------|------|----------|
| CR-001 | 静态字段持有 Class 锁死 | 🔴 严重 | `static Class<?>` / `static ClassLoader` 直接锁死 ClassLoader |
| CR-002 | JDBC Driver 注册未释放 | 🔴 严重 | `DriverManager.register()` 后未在 destroy 中 `deregister()` |
| CR-003 | 静态集合持有自定义类型实例 | 🔴 严重 | `static Map<XxxType, ...>` 持有业务类实例，隐式锁死 ClassLoader |
| CR-004 | 静态单例持有内部集合 | 🔴 严重 | 静态单例的内部集合动态增长，间接持有 ClassLoader |
| CR-005 | 静态集合持有通用包装类型 | 🟡 中 | `static Map<String, Object>` 等"垃圾场"集合，无界堆积 |
| CR-006 | 静态集合持有匿名内部类/lambda | 🔴 严重 | 匿名内部类隐式持有外部类引用，放入静态集合后 ClassLoader 永不释放 |
| CR-007 | 实例字段环形引用链 | 🟡 中 | A→B→A 环形引用，被静态集合持有时整条对象图无法 GC；任一端为单例时降级为低风险 |

#### HI 系列 — 隐式引用泄漏

| 规则 | 名称 | 级别 | 检测内容 |
|------|------|------|----------|
| HI-001 | ThreadLocal 逃逸未清理 | 🟠 高 | ThreadLocal 赋值后未在 finally 中 remove，线程复用时泄漏 |
| HI-002 | ThreadLocal 不完整清理 | 🟠 高 | ThreadLocal 在部分分支清理，异常路径遗漏 remove |
| HI-003 | 监听器注册未反注册 | 🟠 高 | `addListener()` 后未在 destroy 中 `removeListener()` |
| HI-004 | Shutdown Hook 捕获外部引用 | 🟠 高 | `Runtime.addShutdownHook()` 捕获了外部类引用，进程级锁死 |
| HI-006 | ExecutorService 未 shutdown | 🟠 高 | 持有 ExecutorService 但类无 close/shutdown 方法，线程池无法正确关闭 |
| HI-007 | 反射加载类持有 ClassLoader | 🟠 高 | `Class.forName()` / `ClassLoader.loadClass()` 加载的 Class 隐式持有 ClassLoader |

#### LO 系列 — 低风险提示

| 规则 | 名称 | 级别 | 检测内容 |
|------|------|------|----------|
| LO-001 | 静态单例无清理机制 | ⚪ 低 | `static final Xxx INSTANCE` 无 destroy/close/cleanup 方法，热部署场景钉住 ClassLoader |
| LO-002 | 静态字段持有外部库类型 | ⚪ 低 | `static ObjectMapper MAPPER` 等，热部署场景钉住库 ClassLoader；外部库非 final 类型不视为不可变 |
| LO-003 | 枚举单例持有可变状态 | ⚪ 低 | 枚举实例持有 Map/List 等可变集合，JVM 级单例无法释放 |
| LO-004 | 静态缓存 ServiceLoader 结果 | ⚪ 低 | `ServiceLoader.load()` 加载的 SPI 实例缓存到静态上下文，钉住 ClassLoader |
| LO-005 | 静态字段持有 Thread 子类 | ⚪ 低 | `static Thread` 子类实例，线程生命周期可能超出预期 |

### 功能特性

- 📌 **一键跳转** — 点击结果直接定位到问题代码行
- 🔗 **引用链可视化** — 从 GC Root 到泄漏点的完整引用路径
- 📊 **扫描范围选择** — 支持整个项目 / 当前模块 / 当前文件
- 📄 **报告导出** — 导出文本报告，方便团队协作和归档
- 🧠 **智能降噪** — 自动过滤不可变常量、框架内部集合、shade 重定位库等误报
- ⚙️ **规则配置** — 可单独开关每条规则，自定义风险等级覆盖
- 🔍 **结果过滤排序** — 按规则、风险等级、位置过滤，按严重度排序
- 🚫 **误报抑制** — 右键标记误报，后续扫描自动跳过

### 适用场景

- 插件化系统 / 热部署架构（OSGi、SPI、自定义 ClassLoader）
- 重度使用动态类加载的项目（规则引擎、脚本引擎、模板引擎）
- 遇到神秘 Metaspace OOM 的项目
- 长期运行的服务端应用（连接池、缓存、注册表等静态集合堆积）
- 需要保证模块可卸载的微服务框架

### 关于灵珑 LingFrame

灵镜是灵珑（LingFrame）生态的诊断工具。灵珑提供运行时安全弹性治理，正在 Apache SeaTunnel 等项目中验证。

👉 https://gitee.com/LingFrame/LingFrame
<!-- Plugin description end -->

## 系统要求

| 项目 | 最低版本 |
|------|---------|
| IntelliJ IDEA | 2023.2+ (build 231+) |
| JDK | 17+ |

## 安装

- **IDE 内安装**：

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>搜索 "LingMirror"</kbd> >
  <kbd>Install</kbd>

- **JetBrains Marketplace**：

  访问 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)，点击 <kbd>Install to ...</kbd> 安装。

  也可以从 [最新版本](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) 下载，然后通过
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd> 手动安装。

- **手动安装**：

  从 [GitHub Releases](https://github.com/LingFrame/LingMirror/releases/latest) 下载，通过
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd> 安装。

## 使用方法

1. 打开 Java 项目
2. 点击工具栏上的 🔍 灵镜图标，或使用快捷键 `Alt+L`
3. 选择扫描范围（整个项目 / 当前模块 / 当前文件）
4. 点击「开始扫描」
5. 查看结果，点击条目跳转到对应代码行
6. 可选：点击「导出报告」保存文本报告

## 从源码构建

```bash
# 克隆仓库
git clone https://github.com/LingFrame/LingMirror.git
cd LingMirror

# 构建
./gradlew buildPlugin

# 构建产物位于 build/distributions/
```

## 许可证

[Apache License 2.0](LICENSE)

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
