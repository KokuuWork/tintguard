package com.kokuustudio.tintguard;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tint Guard - 让所有方块染色提供者（BlockColor）变成"异常安全"的。
 *
 * 背景：
 * Voxy/Roxy 在烘焙模型时会拿方块状态去问染色提供者"这个方块什么颜色"，
 * 而部分 mod（实测：Waystones）在自己的 BlockColor 里写了无检查的强转：
 *     PortstoneBlock block = (PortstoneBlock) state.getBlock();
 * 一旦被问到不是自己的方块，直接 ClassCastException → 模型烘焙炸掉 → 客户端崩溃。
 *
 * 本 mod 在 BlockColors.register 处把每个 BlockColor 包一层 try/catch，
 * 异常时返回 -1（不染色），把崩溃降级成"该方块少一层染色"。
 */
@Mod("tintguard")
public final class TintGuard {

    public static final Logger LOGGER = LoggerFactory.getLogger("Tint Guard");

    public TintGuard() {
        LOGGER.info("Tint Guard 已启用：所有方块染色提供者现已是异常安全的。");
    }
}
