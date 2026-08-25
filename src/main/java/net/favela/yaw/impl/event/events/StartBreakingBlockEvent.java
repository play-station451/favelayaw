package net.favela.yaw.impl.event.events;

import net.favela.yaw.impl.event.CancellableEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class StartBreakingBlockEvent extends CancellableEvent {
    private final BlockPos blockPos;
    private final Direction direction;

    public StartBreakingBlockEvent(BlockPos blockPos, Direction direction) {
        this.blockPos = blockPos;
        this.direction = direction;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public Direction getDirection() {
        return direction;
    }
}
