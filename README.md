# Tint Guard

**让方块染色提供者不再把游戏打崩。**

一个小巧、零配置的客户端 NeoForge 补丁 mod：把所有注册进 `BlockColors` 的
方块染色提供者（`BlockColor`）包一层异常保护，从此一个写坏的染色处理器再也
不能把整个客户端带走。

> English version below. 英文说明见文末。

---

# 中文说明

## 这个 mod 解决什么问题

有些模组在注册方块染色处理器时，会把传进来的方块状态**无检查地强转**成自己
的方块类。比如 Waystones 里就是这么写的：

```java
PortstoneBlock block = (PortstoneBlock) state.getBlock();   // 没有类型检查
```

原版渲染下这没问题——因为这个处理器只会被它自己注册的那些方块调用到。
但只要有一个「远景渲染 mod」拿**不相干的方块**去问它，事情就炸了。

在 NeoForge 上，Voxy 会通过 Roxy 的 `RoxyModelTintBridge` 在烘焙模型时这么干：

```
Voxy ModelFactory.isBiomeDependentColour
  → Roxy RoxyModelTintBridge（反射动态代理）
    → Waystones ModRenderers.lambda$initialize$8   ← BlockColor
      → (PortstoneBlock) state.getBlock()
        → ClassCastException → 模型烘焙中断 → 客户端退出
```

实际复现到的两组崩溃：

| 被问的方块 | 强转目标 | 谁触发 |
|---|---|---|
| `create:CasingBlock` | Waystones 的 `SharestoneBlock` | 机械动力 |
| `quark:VariantLadderBlock` | Waystones 的 `PortstoneBlock` | Quark |

## 为什么不能靠升级上游解决

这两条路都堵死了，实测过：

- **升级 Waystones 没用**。`21.1.46` 与 `21.1.41` 的
  `net/blay09/mods/waystones/client/ModRenderers.class`
  **SHA-1 完全相同**（`e9510146e4f56aa0e2382d14d0c1f50677be71f9`，6689 字节），
  上游根本没改这段代码。
- **Roxy 也没有开关**。`0.4.0` 已是当前最新版，反编译 `RoxyModelTintBridge`
  找不到任何能关闭这个染色代理的配置项。

所以只能从**注册处统一兜底**，也就是这个 mod 干的事。

## 原理：注入点选在哪里

一个 mixin，一个注入点：

```
Waystones → Balm NeoForgeBalmRenderers.registerBlockColorHandler（只是存进 List）
  → NeoForge RegisterColorHandlersEvent.Block.register
    → BlockColors.register          ← 在这里拦
```

```java
@ModifyVariable(
        method = "register(Lnet/minecraft/client/color/block/BlockColor;[Lnet/minecraft/world/level/block/Block;)V",
        at = @At("HEAD"), argsOnly = true, index = 1)
private BlockColor tintguard$wrapBlockColor(BlockColor color) {
    return SafeBlockColor.wrap(color);
}
```

**为什么选这里安全**：反汇编确认 `BlockColors.register` 的实现只有一句循环
`Map.put`，**不对传入的 provider 做任何自省**。所以在参数位置把它换成一个包装
对象，完全不会影响染色属性（coloring states）等任何其他逻辑。

## 包装类做了什么

抛异常时返回 `-1`（表示「不染色」）。这里刻意**捕获 `Throwable` 而不是只捕获
`ClassCastException`**，因为 Roxy 是用反射调用这些 provider 的，异常可能被包成
`InvocationTargetException` 或 `UndeclaredThrowableException` 才浮上来。

每个出问题的 provider 类**只记一次日志**，把真凶留在日志里，而不是静默吞掉：

```
[Tint Guard] 方块染色提供者 <类名> 在渲染 <方块> 时抛出 <异常>，已降级为「不染色」。
```

因为是对**所有**注册进来的 provider 生效，所以它不止能修 Waystones——任何犯这个
毛病的模组都会被兜住。

## 代价

本来会让客户端崩掉的那一次查询，现在降级成**那个方块少一层染色**（比如被错问的
Quark 梯子）。其余方块颜色完全不受影响。没有配置项、不需要玩家做任何事、不改变
任何玩法。

## 支持环境

| | |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge 21.1.x |
| 侧 | **仅客户端**（装在服务端无效果） |
| 依赖 | 无 |

## 安装

把 `tintguard-1.0.0.jar` 丢进客户端的 `mods/` 文件夹，完事。

启动时应该能看到：

```
[Tint Guard] Tint Guard 已启用：所有方块染色提供者现已是异常安全的。
```

真正拦下崩溃时（每个出问题的类只打印一次）：

```
[Tint Guard] 方块染色提供者 net.blay09.mods.waystones.client.ModRenderers$$Lambda$xxx
             在渲染 minecraft:xxx 时抛出 ClassCastException，已降级为「不染色」。
```

## 构建

标准 NeoForge 工具链 —— Gradle 8.12.1 + ModDevGradle 2.x + JDK 21：

```bash
./gradlew build          # 产物：build/libs/tintguard-1.0.0.jar
```

### 离线兜底方案

`build.sh` 用纯 `javac` 编译，不需要联网，适合 Gradle 拉不到 Maven 的机器。
它需要一个本地 Minecraft 安装来取 NeoForge / Mixin 的 jar，**用之前先改脚本
顶部的路径**（或用 `MC_DIR=` / `JDK=` 覆盖）。

`stubs/` 目录只服务于这条离线路径，**不会被打进 jar**。原因：那台机器上只有
**SRG 命名**的 Minecraft jar（成员名被改成 `m_xxxx_`），而 NeoForge 1.21.1
运行时用的是**官方（Mojang）命名**。`stubs/` 里放的是只有「类名 + 签名」的
桩类，让 `javac` 能对着官方名编译；运行时全部解析到真实类。

## 一些实现细节

- 注入在 `HEAD`，并且**显式指定局部变量槽位 `index = 1`**
  （实例方法布局：`this = 0`、`color = 1`、`blocks = 2`）。
  这样就不依赖局部变量表——生产环境的 Minecraft jar 是不带 LVT 的。
- 包装的开销 = 每次染色查询多一次方法调用 + 一个 try 块；不发生异常时几乎为零。
- 如果 mixin 没挂上，游戏会**在启动时响亮地失败**（`defaultRequire = 1`），
  而不是静默无效。删掉 jar 即可完全回到原状，不留任何残留。

## 许可

MIT，详见 [LICENSE](LICENSE)。

---

# English

**Stops block colour providers from crashing your game.**

A tiny, zero-config, client-side NeoForge mod that makes every registered
`BlockColor` exception-safe, so one badly written tint handler can no longer
take down the whole client.

## The problem

Some mods register a block colour provider that blindly casts the queried block
state to their own block class, with no `instanceof` check. Waystones, for
example:

```java
PortstoneBlock block = (PortstoneBlock) state.getBlock();   // no type check
```

Harmless in vanilla rendering, because the provider is only ever called for the
blocks it was registered for. It stops being harmless the moment a far-render
mod asks about an unrelated block — which Voxy does, through Roxy's
`RoxyModelTintBridge` on NeoForge, while baking models:

```
Voxy ModelFactory.isBiomeDependentColour
  -> Roxy RoxyModelTintBridge (reflective proxy)
    -> Waystones ModRenderers.lambda$initialize$8   <- BlockColor
      -> (PortstoneBlock) state.getBlock()
        -> ClassCastException -> model baking dies -> client exits
```

Reproduced crashing pairs:

| Queried block | Cast target | Bystander |
|---|---|---|
| `create:CasingBlock` | Waystones `SharestoneBlock` | Create |
| `quark:VariantLadderBlock` | Waystones `PortstoneBlock` | Quark |

## Why upstream cannot fix it

Both routes are dead ends, verified:

- **Upgrading Waystones does nothing.** `21.1.46` ships a byte-identical
  `ModRenderers.class` to `21.1.41` — same SHA-1
  (`e9510146e4f56aa0e2382d14d0c1f50677be71f9`, 6689 bytes). The code was never
  touched.
- **Roxy has no switch.** `0.4.0` is current, and `RoxyModelTintBridge` exposes
  nothing that turns the tint bridge off.

So the guard lives at the registration point instead.

## How it works

One mixin, one injection point:

```
Waystones -> Balm NeoForgeBalmRenderers.registerBlockColorHandler (stores a list)
  -> NeoForge RegisterColorHandlersEvent.Block.register
    -> BlockColors.register          <-- wrapped here
```

```java
@ModifyVariable(
        method = "register(Lnet/minecraft/client/color/block/BlockColor;[Lnet/minecraft/world/level/block/Block;)V",
        at = @At("HEAD"), argsOnly = true, index = 1)
private BlockColor tintguard$wrapBlockColor(BlockColor color) {
    return SafeBlockColor.wrap(color);
}
```

`BlockColors.register` is a pure `Map.put` loop that never introspects the
provider it is handed (confirmed by disassembly), so swapping the argument for a
wrapper changes nothing else about how tints are resolved.

The wrapper catches `Throwable` and returns `-1` ("no tint"). It catches
everything rather than just `ClassCastException` deliberately: Roxy invokes
providers reflectively, so failures can arrive wrapped in
`InvocationTargetException` or `UndeclaredThrowableException`. Each offending
provider class is logged once, so the real culprit stays discoverable.

Because the guard covers *every* registered provider, it fixes any mod with this
bug, not just Waystones.

## Trade-off

A query that would have crashed the client now degrades to **no tint for that
block** — for instance a Quark ladder that was asked about by mistake. Every
other block keeps its colour. No config, no player-side install, no gameplay
changes.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.x |
| Side | **Client only** (no-op on a dedicated server) |
| Dependencies | none |

## Install

Drop `tintguard-1.0.0.jar` into your client's `mods/` folder. That is the whole
setup.

Expected at startup:

```
[Tint Guard] Tint Guard 已启用：所有方块染色提供者现已是异常安全的。
```

## Building

```bash
./gradlew build          # output: build/libs/tintguard-1.0.0.jar
```

`build.sh` is an offline `javac` fallback (no Gradle, no network); point
`MC_DIR=` / `JDK=` at your own installation first. `stubs/` only serves that
path and is never packaged — that machine only has SRG-named Minecraft jars,
while NeoForge 1.21.1 runs on official Mojang names.

## Notes

- Injected at `HEAD` with an explicit local slot index `1`
  (`this = 0`, `color = 1`, `blocks = 2`), so no local variable table is
  required — production Minecraft jars ship without one.
- The wrapper costs one extra call plus a try block per colour query, and is
  effectively free when nothing throws.
- If the mixin fails to apply, the game fails loudly at startup rather than
  silently doing nothing (`defaultRequire = 1`). Delete the jar to restore the
  previous state; nothing is left behind.

## License

MIT — see [LICENSE](LICENSE).
