package net.favela.yaw.impl.modules.categories.player;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.event.Events;
import net.favela.yaw.impl.event.Priority;
import net.favela.yaw.impl.event.events.PacketEvent;
import net.favela.yaw.impl.event.events.Render3DEvent;
import net.favela.yaw.impl.event.events.StartBreakingBlockEvent;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.BooleanSetting;
import net.favela.yaw.impl.setting.settings.EnumSetting;
import net.favela.yaw.impl.setting.settings.NumberSetting;
import net.favela.yaw.impl.util.models.Timer;
import net.favela.yaw.impl.util.render.RenderUtil;
import net.favela.yaw.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class SpeedMine extends Module {
    public enum Swap {
        NONE,
        NORMAL,
        SILENT
    }

    public enum Rotate {
        NORMAL,
        HOLD,
        NONE
    }

    public final NumberSetting range = num(
            "Range",
            "Maximum mining distance",
            2.0,
            7.0,
            4.5,
            0.1
    );

    public final NumberSetting speed = num(
            "Speed",
            "Mining progress multiplier",
            0.7,
            1.0,
            1.0,
            0.01
    );

    public final EnumSetting<Swap> swap = enm(
            "Swap",
            "Tool swapping mode",
            Swap.NORMAL
    );

    public final EnumSetting<Rotate> rotate = enm(
            "Rotate",
            "Server rotation mode",
            Rotate.NORMAL
    );

    public final BooleanSetting grim = bool(
            "Grim",
            "Use the extra stop packet",
            false
    );

    public final BooleanSetting doubleMine = bool(
            "DoubleMine",
            "Mine the previous block while mining the new block",
            false
    );

    public final BooleanSetting instant = bool(
            "Instant",
            "Instantly remine broken blocks",
            true
    );

    public final NumberSetting instantDelay = register(
            new NumberSetting(
                    "InstantDelay",
                    "Delay between instant remine packets",
                    () -> instant.get(),
                    0,
                    1000,
                    0,
                    1
            )
    );

    public final BooleanSetting simulate = bool(
            "Simulate",
            "Simulate the block break client-side",
            true
    );

    public final BooleanSetting swing = bool(
            "Swing",
            "Swing while mining",
            true
    );

    public final BooleanSetting multitask = bool(
            "Multitask",
            "Allow mining while using an item",
            false
    );

    private final Timer instantRemineTimer = new Timer();
    private final Timer instantRemineResetTimer = new Timer();

    public BlockBreakingTask currentTask;
    public BlockBreakingTask doubleMineTask;

    private int silentPreviousSlot = -1;
    private int doubleMinePreviousSlot = -1;

    private Events.Handler<StartBreakingBlockEvent> startHandler;
    private Events.Handler<PacketEvent.Receive> packetHandler;

    public SpeedMine() {
        super(
                "SpeedMine",
                "Increases speed of mining.",
                Category.PLAYER
        );
    }

    @Override
    public void onEnable() {
        startHandler = Events.on(
                StartBreakingBlockEvent.class,
                Priority.HIGH,
                this::onStartBreaking
        );

        packetHandler = Events.on(
                PacketEvent.Receive.class,
                Priority.HIGH,
                this::onPacketReceive
        );

        instantRemineTimer.reset();
        instantRemineResetTimer.reset();
    }

    @Override
    public void onDisable() {
        if (currentTask != null && currentTask.isStarted()) {
            abortMining(currentTask);
        }

        currentTask = null;
        doubleMineTask = null;

        restoreSilentSlot();
        restoreDoubleMineSlot();

        if (startHandler != null) {
            Events.off(startHandler);
            startHandler = null;
        }

        if (packetHandler != null) {
            Events.off(packetHandler);
            packetHandler = null;
        }
    }

    @Override
    public void onTick() {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (currentTask != null) {
            handleCurrentTask(currentTask);
        }

        if (doubleMineTask != null) {
            handleDoubleMineTask(doubleMineTask);
        }
    }

    @Override
    public void onUpdate(
            net.favela.yaw.impl.event.events.UpdateEvent event
    ) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (currentTask != null
                && rotate.get() == Rotate.HOLD) {
            rotateServer(currentTask.getBlockPos());
        }
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (MC.level == null) {
            return;
        }

        if (currentTask != null) {
            renderTask(event, currentTask);
        }

        if (doubleMineTask != null) {
            renderTask(event, doubleMineTask);
        }
    }

    private void onStartBreaking(StartBreakingBlockEvent event) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        BlockPos pos = event.getBlockPos();
        BlockState state = MC.level.getBlockState(pos);

        if (state.isAir()) {
            return;
        }

        event.cancel();

        if (currentTask != null
                && currentTask.getBlockPos().equals(pos)) {
            return;
        }

        if (doubleMineTask != null
                && doubleMineTask.getBlockPos().equals(pos)) {
            return;
        }

        if (currentTask != null) {
            if (doubleMine.get() && doubleMineTask == null) {
                doubleMineTask = new BlockBreakingTask(
                        currentTask.getBlockPos(),
                        currentTask.getFacing(),
                        1.0f
                );

                doubleMineTask.setProgress(
                        currentTask.getProgress()
                );
            }

            abortMining(currentTask);
        }

        currentTask = new BlockBreakingTask(
                pos,
                event.getDirection(),
                speed.getFloat()
        );

        instantRemineTimer.reset();
        instantRemineResetTimer.reset();

        startMining(currentTask);

        if (swing.get()) {
            MC.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void startMining(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (task.getBlockState().isAir()) {
            return;
        }

        int slot = getBestToolSlot(task.getStartState());

        if (swap.get() == Swap.NORMAL && slot >= 0) {
            MC.player.getInventory().setSelectedSlot(slot);
        }

        if (swap.get() == Swap.SILENT && slot >= 0) {
            switchToSilent(slot);
        }

        if (grim.get()) {
            sendDestroyPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    task
            );
        }

        sendDestroyPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                task
        );

        sendDestroyPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                task
        );

        task.markStarted();
    }

    private void abortMining(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (!task.isStarted()) {
            return;
        }

        if (task.isInstantRemine()) {
            return;
        }

        if (task.getBlockState().isAir()) {
            return;
        }

        if (task.getProgress() >= task.getTargetSpeed()) {
            return;
        }

        if (swing.get()) {
            MC.player.swing(InteractionHand.MAIN_HAND);
        }

        sendDestroyPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                task
        );
    }

    private void handleCurrentTask(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        Vec3 eye = MC.player.getEyePosition();
        AABB box = new AABB(task.getBlockPos());

        if (eye.distanceTo(
                closestPoint(
                        eye,
                        box
                )
        ) > range.getDouble()) {
            if (task == currentTask) {
                currentTask = null;
            }

            return;
        }

        BlockState state = task.getBlockState();

        if (state.isAir()) {
            if (instant.get()) {
                task.markInstantRemine();
                task.setProgress(task.getTargetSpeed());
            } else {
                task.resetProgress();
                return;
            }
        }

        if (rotate.get() == Rotate.HOLD) {
            rotateServer(task.getBlockPos());
        }

        float damage = calculateBlockDamage(
                task.getStartState(),
                task.getBlockPos()
        );

        if (task.incrementProgress(damage)
                >= task.getTargetSpeed()
                || task.isInstantRemine()) {
            finishMining(task);
        }
    }

    private void handleDoubleMineTask(
            BlockBreakingTask task
    ) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (!doubleMine.get()) {
            doubleMineTask = null;
            restoreDoubleMineSlot();
            return;
        }

        if (task.getDoublemineHoldTicks() >= 3) {
            doubleMineTask = null;
            restoreDoubleMineSlot();
            return;
        }

        Vec3 eye = MC.player.getEyePosition();
        AABB box = new AABB(task.getBlockPos());

        if (eye.distanceTo(
                closestPoint(
                        eye,
                        box
                )
        ) > range.getDouble()) {
            doubleMineTask = null;
            restoreDoubleMineSlot();
            return;
        }

        if (task.getBlockState().isAir()) {
            doubleMineTask = null;
            restoreDoubleMineSlot();
            return;
        }

        if (!multitask.get()
                && MC.player.isUsingItem()) {
            return;
        }

        float damage = calculateBlockDamage(
                task.getStartState(),
                task.getBlockPos()
        );

        if (task.incrementProgress(damage)
                >= task.getTargetSpeed()) {

            int slot = getBestToolSlot(
                    task.getStartState()
            );

            if (slot < 0) {
                return;
            }

            if (doubleMinePreviousSlot == -1) {
                doubleMinePreviousSlot =
                        MC.player.getInventory()
                                .getSelectedSlot();
            }

            MC.player.getInventory()
                    .setSelectedSlot(slot);

            task.setDoublemineHoldTicks(
                    task.getDoublemineHoldTicks() + 1
            );
        }
    }

    private void finishMining(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (!task.isStarted()) {
            return;
        }

        if (!multitask.get()
                && MC.player.isUsingItem()) {
            return;
        }

        if (task.getBrokenCount()
                != task.getLastBrokenCount()) {
            instantRemineResetTimer.reset();
        }

        if (task.isInstantRemine()) {
            if (!instantRemineTimer.passedMs(
                    instantDelay.getLong()
            )) {
                return;
            }

            if (task.getBlockState().isAir()
                    && instantRemineResetTimer.passedMs(250L)) {
                return;
            }
        }

        if (rotate.get() == Rotate.NORMAL) {
            rotateServer(task.getBlockPos());
        }

        int slot = getBestToolSlot(
                task.getStartState()
        );

        if (swap.get() == Swap.NORMAL
                && slot >= 0) {
            MC.player.getInventory()
                    .setSelectedSlot(slot);
        }

        if (swap.get() == Swap.SILENT
                && slot >= 0) {
            switchToSilent(slot);
        }

        if (grim.get()) {
            sendDestroyPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    task
            );
        }

        if (swing.get()) {
            MC.player.swing(InteractionHand.MAIN_HAND);
        }

        sendDestroyPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                task
        );

        if (simulate.get()
                && !task.getBlockState().isAir()) {
            MC.level.destroyBlock(
                    task.getBlockPos(),
                    false,
                    MC.player,
                    512
            );
        }

        task.markLastBroken();

        if (task.isInstantRemine()) {
            instantRemineTimer.reset();
        }
    }

    private void sendDestroyPacket(
            ServerboundPlayerActionPacket.Action action,
            BlockBreakingTask task
    ) {
        if (MC.player == null
                || MC.level == null
                || MC.gameMode == null) {
            return;
        }

        ((MultiPlayerGameModeAccessor) MC.gameMode)
                .favelayaw$startPrediction(
                        MC.level,
                        sequence -> new ServerboundPlayerActionPacket(
                                action,
                                task.getBlockPos(),
                                task.getFacing(),
                                sequence
                        )
                );
    }

    private void onPacketReceive(PacketEvent.Receive event) {
        if (MC.level == null || MC.player == null) {
            return;
        }

        if (!(event.packet()
                instanceof ClientboundBlockUpdatePacket packet)) {
            return;
        }

        if (currentTask == null) {
            return;
        }

        if (!packet.getPos().equals(
                currentTask.getBlockPos()
        )) {
            return;
        }

        currentTask.markBroken();
    }

    private void rotateServer(BlockPos pos) {
        if (MC.player == null) {
            return;
        }

        Vec3 target = closestPoint(
                MC.player.getEyePosition(),
                new AABB(pos)
        );

        double dx = target.x - MC.player.getX();
        double dy = target.y - MC.player.getEyeY();
        double dz = target.z - MC.player.getZ();

        double horizontal =
                Math.sqrt(dx * dx + dz * dz);

        float yaw =
                (float) Math.toDegrees(
                        Math.atan2(dz, dx)
                ) - 90.0f;

        float pitch =
                (float) -Math.toDegrees(
                        Math.atan2(
                                dy,
                                horizontal
                        )
                );

        MC.player.connection.send(
                new ServerboundMovePlayerPacket.Rot(
                        yaw,
                        pitch,
                        MC.player.onGround(),
                        MC.player.horizontalCollision
                )
        );
    }

    private void switchToSilent(int slot) {
        if (MC.player == null) {
            return;
        }

        int current =
                MC.player.getInventory().getSelectedSlot();

        if (current == slot) {
            return;
        }

        if (silentPreviousSlot == -1) {
            silentPreviousSlot = current;
        }

        MC.player.connection.send(
                new ServerboundSetCarriedItemPacket(slot)
        );
    }

    private void restoreSilentSlot() {
        if (MC.player == null) {
            return;
        }

        if (silentPreviousSlot == -1) {
            return;
        }

        MC.player.connection.send(
                new ServerboundSetCarriedItemPacket(
                        silentPreviousSlot
                )
        );

        silentPreviousSlot = -1;
    }

    private void restoreDoubleMineSlot() {
        if (MC.player == null) {
            doubleMinePreviousSlot = -1;
            return;
        }

        if (doubleMinePreviousSlot == -1) {
            return;
        }

        MC.player.getInventory()
                .setSelectedSlot(
                        doubleMinePreviousSlot
                );

        doubleMinePreviousSlot = -1;
    }

    private int getBestToolSlot(
            BlockState state
    ) {
        if (MC.player == null) {
            return -1;
        }

        int bestSlot =
                MC.player.getInventory()
                        .getSelectedSlot();

        float bestSpeed = 1.0f;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack =
                    MC.player.getInventory()
                            .getItem(slot);

            if (stack.isEmpty()) {
                continue;
            }

            float toolSpeed =
                    getToolSpeed(
                            stack,
                            state
                    );

            if (toolSpeed > bestSpeed) {
                bestSpeed = toolSpeed;
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    private float getToolSpeed(
            ItemStack stack,
            BlockState state
    ) {
        if (!stack.isCorrectToolForDrops(state)) {
            return 1.0f;
        }

        return stack.getDestroySpeed(state)
                * (
                1.0f
                        + getEfficiency(stack) * 0.2f
        );
    }

    private int getEfficiency(
            ItemStack stack
    ) {
        if (MC.level == null) {
            return 0;
        }

        try {
            return stack.getEnchantments()
                    .getLevel(
                            MC.level
                                    .registryAccess()
                                    .lookupOrThrow(
                                            Registries.ENCHANTMENT
                                    )
                                    .getOrThrow(
                                            Enchantments.EFFICIENCY
                                    )
                    );
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean hasAquaAffinity(
            ItemStack stack
    ) {
        if (MC.level == null) {
            return false;
        }

        try {
            return stack.getEnchantments()
                    .getLevel(
                            MC.level
                                    .registryAccess()
                                    .lookupOrThrow(
                                            Registries.ENCHANTMENT
                                    )
                                    .getOrThrow(
                                            Enchantments.AQUA_AFFINITY
                                    )
                    ) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private float calculateBlockDamage(
            BlockState state,
            BlockPos pos
    ) {
        float hardness =
                state.getDestroySpeed(
                        MC.level,
                        pos
                );

        if (hardness < 0.0f) {
            return 0.0f;
        }

        int divisor =
                canHarvest(state)
                        ? 30
                        : 100;

        return getMiningSpeed(state)
                / hardness
                / divisor;
    }

    private boolean canHarvest(
            BlockState state
    ) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }

        int slot =
                getBestToolSlot(state);

        if (slot < 0) {
            return false;
        }

        return MC.player
                .getInventory()
                .getItem(slot)
                .isCorrectToolForDrops(state);
    }

    private float getMiningSpeed(
            BlockState state
    ) {
        ItemStack stack =
                MC.player.getMainHandItem();

        if (swap.get() == Swap.SILENT) {
            int slot =
                    getBestToolSlot(state);

            if (slot >= 0) {
                stack =
                        MC.player.getInventory()
                                .getItem(slot);
            }
        }

        float miningSpeed =
                stack.getDestroySpeed(state);

        if (miningSpeed > 1.0f) {
            int efficiency =
                    getEfficiency(stack);

            if (efficiency > 0) {
                miningSpeed +=
                        efficiency * efficiency + 1;
            }
        }

        if (MC.player.hasEffect(
                MobEffects.HASTE
        )) {
            int amplifier =
                    MC.player.getEffect(
                                    MobEffects.HASTE
                            )
                            .getAmplifier() + 1;

            miningSpeed *=
                    1.0f + amplifier * 0.2f;
        }

        if (MC.player.hasEffect(
                MobEffects.MINING_FATIGUE
        )) {
            int amplifier =
                    MC.player.getEffect(
                                    MobEffects.MINING_FATIGUE
                            )
                            .getAmplifier();

            miningSpeed *= switch (amplifier) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 0.00081f;
            };
        }

        if (MC.player.isEyeInFluid(
                FluidTags.WATER
        ) && !hasAquaAffinity(
                MC.player.getItemBySlot(
                        EquipmentSlot.HEAD
                )
        )) {
            miningSpeed /= 5.0f;
        }

        if (!MC.player.onGround()) {
            miningSpeed /= 5.0f;
        }

        return miningSpeed;
    }

    private Vec3 closestPoint(
            Vec3 point,
            AABB box
    ) {
        return new Vec3(
                clamp(
                        point.x,
                        box.minX,
                        box.maxX
                ),
                clamp(
                        point.y,
                        box.minY,
                        box.maxY
                ),
                clamp(
                        point.z,
                        box.minZ,
                        box.maxZ
                )
        );
    }

    private double clamp(
            double value,
            double min,
            double max
    ) {
        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    private void renderTask(
            Render3DEvent event,
            BlockBreakingTask task
    ) {
        if (MC.level == null) {
            return;
        }

        BlockPos pos = task.getBlockPos();

        if (MC.level.getBlockState(pos).isAir()) {
            return;
        }

        VoxelShape shape =
                task.isInstantRemine()
                        ? Shapes.block()
                        : task.getStartState().getShape(
                                MC.level,
                                pos
                        );

        if (shape.isEmpty()) {
            shape = Shapes.block();
        }

        AABB bounds = shape.bounds();

        AABB worldBox =
                new AABB(
                        pos.getX() + bounds.minX,
                        pos.getY() + bounds.minY,
                        pos.getZ() + bounds.minZ,
                        pos.getX() + bounds.maxX,
                        pos.getY() + bounds.maxY,
                        pos.getZ() + bounds.maxZ
                );

        Vec3 center = worldBox.getCenter();

        float progress =
                task.getPreviousProgress()
                        + (
                        task.getProgress()
                                - task.getPreviousProgress()
                ) * event.getDelta();

        float scale =
                Math.max(
                        0.0f,
                        Math.min(
                                1.0f,
                                progress
                                        / Math.max(
                                                task.getTargetSpeed(),
                                                0.0001f
                                        )
                        )
                );

        double dx =
                (bounds.maxX - bounds.minX) * 0.5;

        double dy =
                (bounds.maxY - bounds.minY) * 0.5;

        double dz =
                (bounds.maxZ - bounds.minZ) * 0.5;

        AABB renderBox =
                new AABB(
                        center,
                        center
                ).inflate(
                        dx * scale,
                        dy * scale,
                        dz * scale
                );

        int r = (int) (200.0f * (1.0f - scale));
        int g = (int) (200.0f * scale);

        RenderUtil.drawBoxOutline(
                event.getMatrix(),
                renderBox,
                new Color(
                        r,
                        g,
                        0,
                        255
                ),
                1.5f
        );
    }

    public static class BlockBreakingTask {
        private final BlockPos blockPos;
        private final Direction facing;
        private final float targetSpeed;

        private BlockState startState;

        private float progress;
        private float previousProgress;

        private boolean instantRemine;
        private boolean started;

        private int brokenCount;
        private int lastBrokenCount = -1;

        private int doublemineHoldTicks;

        public BlockBreakingTask(
                BlockPos blockPos,
                Direction facing,
                float targetSpeed
        ) {
            this.blockPos = blockPos;
            this.facing = facing;
            this.targetSpeed = targetSpeed;
            this.startState =
                    MC.level.getBlockState(blockPos);
        }

        public BlockPos getBlockPos() {
            return blockPos;
        }

        public Direction getFacing() {
            return facing;
        }

        public float getTargetSpeed() {
            return targetSpeed;
        }

        public BlockState getBlockState() {
            return MC.level.getBlockState(blockPos);
        }

        public BlockState getStartState() {
            BlockState state = getBlockState();

            if (!state.isAir()
                    && state.getBlock()
                    != startState.getBlock()) {
                startState = state;
            }

            return startState;
        }

        public boolean isStarted() {
            return started;
        }

        public void markStarted() {
            started = true;
        }

        public boolean isInstantRemine() {
            return instantRemine;
        }

        public void markInstantRemine() {
            instantRemine = true;
        }

        public float getProgress() {
            return progress;
        }

        public float getPreviousProgress() {
            return previousProgress;
        }

        public void setProgress(
                float progress
        ) {
            previousProgress = this.progress;
            this.progress = progress;
        }

        public void resetProgress() {
            previousProgress = 0.0f;
            progress = 0.0f;
        }

        public float incrementProgress(
                float amount
        ) {
            previousProgress = progress;
            progress += amount;
            return progress;
        }

        public void markBroken() {
            brokenCount++;
        }

        public void markLastBroken() {
            lastBrokenCount = brokenCount;
        }

        public int getBrokenCount() {
            return brokenCount;
        }

        public int getLastBrokenCount() {
            return lastBrokenCount;
        }

        public int getDoublemineHoldTicks() {
            return doublemineHoldTicks;
        }

        public void setDoublemineHoldTicks(
                int ticks
        ) {
            doublemineHoldTicks = ticks;
        }
    }
}
