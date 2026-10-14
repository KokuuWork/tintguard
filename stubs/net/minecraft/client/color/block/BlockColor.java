package net.minecraft.client.color.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/** 编译期桩：仅用于 javac，不打包进 jar。 */
public interface BlockColor {
    int getColor(BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex);
}
