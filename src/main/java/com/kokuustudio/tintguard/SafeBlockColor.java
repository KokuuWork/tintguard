package com.kokuustudio.tintguard;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把任意 BlockColor 包成"不抛异常"的版本。
 * 只加一层方法调用 + 一个 try 块，无异常时几乎零开销。
 */
public final class SafeBlockColor implements BlockColor {

    /** MC 约定：-1 表示该方块没有染色。 */
    private static final int NO_TINT = -1;

    /** 每个出问题的提供者类只报告一次，避免刷屏。 */
    private static final Set<Class<?>> REPORTED = ConcurrentHashMap.newKeySet();

    private final BlockColor delegate;

    private SafeBlockColor(BlockColor delegate) {
        this.delegate = delegate;
    }

    public static BlockColor wrap(BlockColor original) {
        if (original == null || original instanceof SafeBlockColor) {
            return original;
        }
        return new SafeBlockColor(original);
    }

    @Override
    public int getColor(BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
        try {
            return delegate.getColor(state, level, pos, tintIndex);
        } catch (Throwable t) {
            // Voxy/Roxy 有时经反射/动态代理调用，异常可能被包成
            // InvocationTargetException 或 UndeclaredThrowableException，
            // 所以这里兜 Throwable，确保任何形式都不会漏出去。
            if (REPORTED.add(delegate.getClass())) {
                LOGGER.warn(
                        "方块染色提供者 {} 在渲染 {} 时抛出 {}，已降级为「不染色」。"
                                + "（本次崩溃被 Tint Guard 拦下，游戏不会退出）",
                        delegate.getClass().getName(),
                        state == null ? "<null>" : String.valueOf(state.getBlock()),
                        t.getClass().getSimpleName(),
                        t);
            }
            return NO_TINT;
        }
    }

    private static final org.slf4j.Logger LOGGER = TintGuard.LOGGER;
}
