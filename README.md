# Tint Guard

**Stops block colour providers from crashing your game.**

A tiny, zero-config, client-side NeoForge mod that makes every registered
`BlockColor` exception-safe, so one badly written tint handler can no longer
take down the whole client.

---

## The problem

Some mods register a block colour provider that blindly casts the queried block
state to their own block class, with no `instanceof` check. For example
Waystones does:

```java
PortstoneBlock block = (PortstoneBlock) state.getBlock();   // no type check
```

That is harmless in vanilla rendering, because the provider is only ever called
for the blocks it was registered for. It stops being harmless as soon as a
far-render mod asks about an unrelated block.

Voxy (through Roxy's `RoxyModelTintBridge` on NeoForge) does exactly that while
baking models:

```
Voxy ModelFactory.isBiomeDependentColour
  -> Roxy RoxyModelTintBridge (reflective proxy)
    -> Waystones ModRenderers.lambda$initialize$8   <- BlockColor
      -> (PortstoneBlock) state.getBlock()
        -> ClassCastException -> model baking dies -> client exits
```

Reproduced crashing pairs:

| Queried block | Cast target | Blamed mod |
|---|---|---|
| `create:CasingBlock` | Waystones `SharestoneBlock` | Create |
| `quark:VariantLadderBlock` | Waystones `PortstoneBlock` | Quark |

Both upstreams are dead ends: Waystones `21.1.46` ships a byte-identical
`ModRenderers.class` to `21.1.41` (verified by SHA-1), and Roxy `0.4.0` has no
switch to turn the tint bridge off. So the guard lives here instead.

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
provider it is handed (verified by disassembly), so swapping the argument for a
wrapper is completely safe and changes nothing else about how tints resolve.

The wrapper catches `Throwable` and returns `-1` ("no tint"). It catches
everything rather than just `ClassCastException` on purpose: Roxy invokes
providers reflectively, so the failure can arrive wrapped in
`InvocationTargetException` or `UndeclaredThrowableException`. Each offending
provider class is logged once, so the real culprit stays discoverable instead of
being silently swallowed.

Because the guard is applied to *every* registered provider, it covers any mod
with this bug, not just Waystones.

## Trade-off

A query that would have crashed the client instead degrades to **no tint for
that block** — for example a Quark ladder that was asked about by mistake. Every
other block keeps its normal colour. No configuration, no player-side install,
no gameplay changes.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.x |
| Side | **Client only** (no-op on a dedicated server) |
| Dependencies | none — nothing to install alongside it |

## Install

Drop `tintguard-1.0.0.jar` into your client's `mods/` folder. That is the whole
setup.

You should see this once at startup:

```
[Tint Guard] Tint Guard 已启用：所有方块染色提供者现已是异常安全的。
```

And when a crash is actually suppressed (once per offending provider class):

```
[Tint Guard] 方块染色提供者 <class> 在渲染 <block> 时抛出 <Exception>，已降级为「不染色」。
```

## Building

Standard NeoForge toolchain — Gradle 8.12.1, ModDevGradle 2.x, JDK 21:

```bash
./gradlew build          # output: build/libs/tintguard-1.0.0.jar
```

### Offline fallback

`build.sh` compiles with plain `javac` and no network access, which is useful
when Gradle cannot reach Maven. It relies on a local Minecraft install to source
the NeoForge/Mixin jars, so edit the paths at the top of the script first.

The `stubs/` directory exists only for that offline path. That machine only has
SRG-named Minecraft jars (members renamed to `m_xxxx_`), while NeoForge 1.21.1
runs on official Mojang names. `stubs/` holds class-name-and-signature-only
stand-ins so `javac` can compile against official names; **they are never
packaged into the jar** — at runtime everything resolves to the real classes.

## Notes

- Injected at `HEAD` with an explicit local slot index `1`
  (`this = 0`, `color = 1`, `blocks = 2`), so no local variable table is needed —
  production Minecraft jars ship without one.
- Wrapping costs one extra call plus a try block per colour query, and is
  effectively free when nothing throws.
- If the mixin fails to apply, the game fails loudly at startup rather than
  silently doing nothing (`defaultRequire = 1`). Delete the jar to restore the
  previous state; it leaves nothing behind.

## 中文说明

`Tint Guard` 是一个零配置的客户端 NeoForge 小补丁：把所有注册进
`BlockColors` 的方块染色提供者（`BlockColor`）包一层异常保护。

起因是部分模组（实测：Waystones）的染色处理器里写了**无检查的强制类型转换**
（`(PortstoneBlock) state.getBlock()`），而 Voxy / Roxy 在烘焙模型时会拿**不相干的
方块**去问它，于是 `ClassCastException` 直接把客户端打崩。

升级 Waystones 无效（`21.1.46` 与 `21.1.41` 的 `ModRenderers.class` 字节完全相同），
Roxy 也没有关闭该代理的开关，所以用这个 mod 从注册处统一兜底：
**染色提供者抛异常时返回「不染色」，而不是让整个游戏崩掉**，并把真凶每个类只记一次日志。

仅客户端，直接丢进 `mods/` 即可。

## License

MIT — see [LICENSE](LICENSE).
