package com.kokuustudio.tintguard.mixin;

import com.kokuustudio.tintguard.SafeBlockColor;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 在所有 BlockColor 注册进来的那一刻，把它们包成异常安全版本。
 *
 * 为什么选这里：
 *   原版 BlockColors.register 的实现只有一句循环 Map.put，
 *   /! 不对传入的 BlockColor 做任何自省（反汇编确认），
 *   所以在参数位置替换成包装对象是完全安全的，不影响任何其他逻辑。
 *
 * 调用链（实测确认）：
 *   Waystones -> Balm NeoForgeBalmRenderers.registerBlockColorHandler（只入 List）
 *   -> NeoForge RegisterColorHandlersEvent.Block.register
 *   -> BlockColors.register   <<== 这里
 */
@Mixin(BlockColors.class)
public abstract class BlockColorsMixin {

    @ModifyVariable(
            method = "register(Lnet/minecraft/client/color/block/BlockColor;[Lnet/minecraft/world/level/block/Block;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1
    )
    private BlockColor tintguard$wrapBlockColor(BlockColor color) {
        return SafeBlockColor.wrap(color);
    }
}
