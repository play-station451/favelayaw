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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.tags.FluidTags;
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

    public BlockBreakingTask currentTask;
    public BlockBreakingTask doubleMineTask;

    private final Timer instantRemineTimer = new Timer();
    private final Timer instantRemineResetTimer = new Timer();

    private int doubleMinePreviousSlot = -1;
    private int silentPreviousSlot = -1;

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
                this::onBlockStartBreak
        );

        packetHandler = Events.on(
                PacketEvent.Receive.class,
                Priority.HIGH,
                this::onPacketReceive
        );
    }

    @Override
    public void onDisable() {
        if (currentTask != null && currentTask.isStarted()) {
            abortMining(currentTask);
        }

        currentTask = null;
        doubleMineTask = null;

        restoreDoubleMineSlot();
        restoreSilentSlot();

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
        if (MC.level == null || MC.player == null) {
            return;
        }

        if (currentTask != null) {
            handleMiningTick(currentTask);
        }

        if (doubleMineTask != null) {
            handleDoubleMine(doubleMineTask);
        }
    }

    @Override
    public void onUpdate(
            net.favela.yaw.impl.event.events.UpdateEvent event
    ) {
        if (MC.level == null || MC.player == null) {
            return;
        }

        if (currentTask == null) {
            return;
        }

        if (rotate.get() == Rotate.HOLD) {
            rotateServer(currentTask.getBlockPos());
        }
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (MC.level == null) {
            return;
        }

        if (currentTask != null) {
            renderProgress(event, currentTask);
        }

        if (doubleMine.get() && doubleMineTask != null) {
            renderProgress(event, doubleMineTask);
        }
    }

    private void onBlockStartBreak(StartBreakingBlockEvent event) {
        if (MC.level == null || MC.player == null) {
            return;
        }

        BlockPos pos = event.getBlockPos();
        BlockState state = MC.level.getBlockState(pos);

        event.cancel();

        if (state.isAir()) {
            return;
        }

        if (state.getBlock().defaultDestroyTime() == -1.0f) {
            return;
        }

        if (doubleMineTask != null
                && doubleMineTask.getBlockPos().equals(pos)) {
            return;
        }

        if (currentTask != null
                && currentTask.getBlockPos().equals(pos)) {
            return;
        }

        if (swing.get()) {
            MC.player.swing(InteractionHand.MAIN_HAND);
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
    }

    private void handleMiningTick(BlockBreakingTask task) {
        if (MC.level == null || MC.player == null) {
            return;
        }

        Vec3 eyePos = MC.player.getEyePosition();
        AABB blockBox = new AABB(task.getBlockPos());

        if (eyePos.distanceTo(
                getClampClosestPoint(eyePos, blockBox)
        ) > range.getDouble()) {
            if (task == currentTask) {
                currentTask = null;
            }

            if (task == doubleMineTask) {
                doubleMineTask = null;
            }

            return;
        }

        if (task.getBlockState().isAir()) {
            if (instant.get()) {
                task.markInstantRemine();
                task.setProgress(1.0f);
            } else {
                task.resetProgress();
                return;
            }
        }

        if (swing.get()) {
            MC.player.swing(InteractionHand.MAIN_HAND);
        }

        if (rotate.get() == Rotate.HOLD) {
            rotateServer(task.getBlockPos());
        }

        float damageDelta = calculateBlockDamage(
                task.getStartState(),
                task.getBlockPos()
        );

        if (task.incrementProgress(damageDelta)
                >= task.getTargetSpeed()
                || task.isInstantRemine()) {
            finishMining(task);
        }
    }

    private void handleDoubleMine(BlockBreakingTask task) {
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

        Vec3 eyePos = MC.player.getEyePosition();
        AABB blockBox = new AABB(task.getBlockPos());

        if (eyePos.distanceTo(
                getClampClosestPoint(eyePos, blockBox)
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

        float damageDelta = calculateBlockDamage(
                task.getStartState(),
                task.getBlockPos()
        );

        if (task.incrementProgress(damageDelta)
                >= task.getTargetSpeed()) {

            if (!multitask.get()
                    && MC.player.isUsingItem()) {
                return;
            }

            int slot = getBestToolSlot(
                    task.getStartState()
            );

            if (slot < 0) {
                return;
            }

            if (doubleMinePreviousSlot == -1) {
                doubleMinePreviousSlot =
                        MC.player.getInventory().getSelectedSlot();
            }

            if (MC.player.getInventory().getSelectedSlot() != slot) {
                MC.player.getInventory().setSelectedSlot(slot);
            }

            task.setDoublemineHoldTicks(
                    task.getDoublemineHoldTicks() + 1
            );
        }
    }

    private void startMining(BlockBreakingTask task) {
        if (task.getBlockState().isAir()) {
            return;
        }

        int slot = getBestToolSlot(
                task.getStartState()
        );

        if (swap.get() == Swap.NORMAL && slot >= 0) {
            MC.player.getInventory().setSelectedSlot(slot);
        } else if (swap.get() == Swap.SILENT && slot >= 0) {
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
        if (!task.isStarted()
                || task.getBlockState().isAir()
                || task.isInstantRemine()
                || task.getProgress() >= 1.0f) {
            return;
        }

        if (grim.get()) {
            sendDestroyPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    task
            );
        }

        if (swing.get()) {
            MC.player.swing(
                    InteractionHand.MAIN_HAND
            );
        }

        sendDestroyPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                task
        );
    }

    private void finishMining(BlockBreakingTask task) {
        if (!task.isStarted()) {
            return;
        }

        if (MC.player == null || MC.level == null) {
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

        if (task.isInstantRemine()
                && !instantRemineTimer.passedMs(
                instantDelay.getLong()
        )) {
            return;
        }

        if (task.isInstantRemine()
                && task.getBlockState().isAir()
                && instantRemineResetTimer.passedMs(250)) {
            return;
        }

        if (rotate.get() == Rotate.NORMAL) {
            rotateServer(task.getBlockPos());
        }

        if (swap.get() == Swap.SILENT
                && !task.isInstantRemine()) {
            int slot = getBestToolSlot(
                    task.getStartState()
            );

            if (slot >= 0) {
                switchToSilent(slot);
            }
        }

        if (grim.get()) {
            sendDestroyPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    task
            );
        }

        if (swing.get()) {
            MC.player.swing(
                    InteractionHand.MAIN_HAND
            );
        }

        sendDestroyPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                task
        );

        if (simulate.get()) {
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

    private void onPacketReceive(PacketEvent.Receive event) {
        if (MC.level == null || MC.player == null) {
            return;
        }

        if (!(event.packet()
                instanceof ClientboundBlockUpdatePacket blockPacket)) {
            return;
        }

        if (currentTask == null) {
            return;
        }

        if (!blockPacket.getPos().equals(
                currentTask.getBlockPos()
        )) {
            return;
        }

        currentTask.markBroken();
    }

    private void sendDestroyPacket(
            ServerboundPlayerActionPacket.Action action,
            BlockBreakingTask task
    ) {
        if (MC.level == null || MC.player == null) {
            return;
        }

        try (var prediction =
                     MC.level
                             .getPendingUpdateManager()
                             .incrementSequence()) {

            int sequence =
                    prediction.getSequence();

            MC.player.connection.send(
                    new ServerboundPlayerActionPacket(
                            action,
                            task.getBlockPos(),
                            task.getFacing(),
                            sequence
                    )
            );
        }
    }

    private void rotateServer(BlockPos pos) {
        if (MC.player == null) {
            return;
        }

        Vec3 target = getClosestPointToEye(
                MC.player.getEyePosition(),
                new AABB(pos)
        );

        double dx =
                target.x - MC.player.getX();

        double dy =
                target.y - MC.player.getEyeY();

        double dz =
                target.z - MC.player.getZ();

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

        int currentSlot =
                MC.player.getInventory().getSelectedSlot();

        if (currentSlot == slot) {
            return;
        }

        if (silentPreviousSlot == -1) {
            silentPreviousSlot = currentSlot;
        }

        MC.player.connection.send(
                new ServerboundSetCarriedItemPacket(
                        slot
                )
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

        if (MC.player.getInventory().getSelectedSlot()
                != doubleMinePreviousSlot) {
            MC.player.getInventory().setSelectedSlot(
                    doubleMinePreviousSlot
            );
        }

        doubleMinePreviousSlot = -1;
    }

    private int getBestToolSlot(BlockState state) {
        if (MC.player == null) {
            return -1;
        }

        int bestSlot =
                MC.player.getInventory().getSelectedSlot();

        float bestSpeed = 1.0f;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack =
                    MC.player.getInventory()
                            .getItem(slot);

            if (stack.isEmpty()) {
                continue;
            }

            float toolSpeed =
                    getToolSpeed(stack, state);

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

        float efficiency =
                getEfficiency(stack);

        return stack.getDestroySpeed(state)
                * (1.0f + efficiency * 0.2f);
    }

    private float getEfficiency(ItemStack stack) {
        if (MC.level == null) {
            return 0.0f;
        }

        try {
            int level =
                    stack.getEnchantments()
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

            return level;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    private boolean hasAquaAffinity(ItemStack stack) {
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

        if (hardness == -1.0f) {
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

        ItemStack held =
                MC.player.getMainHandItem();

        if (swap.get() == Swap.SILENT) {
            int slot =
                    getBestToolSlot(state);

            if (slot >= 0) {
                held =
                        MC.player.getInventory()
                                .getItem(slot);
            }
        }

        return held.isCorrectToolForDrops(state);
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

        float speed =
                stack.getDestroySpeed(state);

        if (speed > 1.0f) {
            int level =
                    (int) getEfficiency(stack);

            if (level > 0 && !stack.isEmpty()) {
                speed +=
                        level * level + 1;
            }
        }

        if (MobEffectUtil.hasDigSpeed(
                MC.player
        )) {
            int amplifier =
                    MobEffectUtil
                            .getDigSpeedAmplification(
                                    MC.player
                            ) + 1;

            speed *=
                    1.0f + amplifier * 0.2f;
        }

        if (MC.player.hasEffect(
                MobEffects.MINING_FATIGUE
        )) {
            int amplifier =
                    MC.player
                            .getEffect(
                                    MobEffects.MINING_FATIGUE
                            )
                            .getAmplifier();

            float multiplier =
                    switch (amplifier) {
                        case 0 -> 0.3f;
                        case 1 -> 0.09f;
                        case 2 -> 0.0027f;
                        default -> 0.00081f;
                    };

            speed *= multiplier;
        }

        boolean noAquaAffinity =
                !hasAquaAffinity(
                        MC.player.getItemBySlot(
                                EquipmentSlot.HEAD
                        )
                );

        if (MC.player.isEyeInFluid(
                FluidTags.WATER
        ) && noAquaAffinity) {
            speed /= 5.0f;
        }

        if (!MC.player.onGround()) {
            speed /= 5.0f;
        }

        return speed;
    }

    private Vec3 getClampClosestPoint(
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

    private Vec3 getClosestPointToEye(
            Vec3 eye,
            AABB box
    ) {
        return getClampClosestPoint(
                eye,
                box
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

    private void renderProgress(
            Render3DEvent event,
            BlockBreakingTask task
    ) {
        if (MC.level == null) {
            return;
        }

        BlockPos pos =
                task.getBlockPos();

        if (MC.level
                .getBlockState(pos)
                .isAir()) {
            return;
        }

        VoxelShape shape =
                task.isInstantRemine()
                        ? Shapes.block()
                        : task.getStartState()
                                .getShape(
                                        MC.level,
                                        pos
                                );

        if (shape.isEmpty()) {
            shape = Shapes.block();
        }

        AABB bounds =
                shape.bounds();

        AABB worldBox =
                new AABB(
                        pos.getX() + bounds.minX,
                        pos.getY() + bounds.minY,
                        pos.getZ() + bounds.minZ,
                        pos.getX() + bounds.maxX,
                        pos.getY() + bounds.maxY,
                        pos.getZ() + bounds.maxZ
                );

        Vec3 center =
                worldBox.getCenter();

        float progress =
                task.getPreviousProgress()
                        + (
                        task.getProgress()
                                - task.getPreviousProgress()
                ) * event.getDelta();

        float scale =
                Math.clamp(
                        progress
                                / Math.max(
                                        task.getTargetSpeed(),
                                        0.0001f
                                ),
                        0.0f,
                        1.0f
                );

        double dx =
                (bounds.maxX - bounds.minX)
                        / 2.0;

        double dy =
                (bounds.maxY - bounds.minY)
                        / 2.0;

        double dz =
                (bounds.maxZ - bounds.minZ)
                        / 2.0;

        AABB box =
                new AABB(
                        center,
                        center
                ).inflate(
                        dx * scale,
                        dy * scale,
                        dz * scale
                );

        float t =
                Math.clamp(
                        (scale - 0.5f) * 2.0f,
                        0.0f,
                        1.0f
                );

        int maxColor = 200;

        int r =
                (int) (
                        maxColor
                                * (1.0f - t)
                );

        int g =
                (int) (
                        maxColor * t
                );

        RenderUtil.drawBoxOutline(
                event.getMatrix(),
                box,
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
        private int lastBrokenCount;

        private int doublemineHoldTicks;

        public BlockBreakingTask(
                BlockPos pos,
                Direction face,
                float speed
        ) {
            this.blockPos = pos;
            this.facing = face;
            this.targetSpeed = speed;

            this.startState =
                    MC.level.getBlockState(pos);

            brokenCount = 0;
            lastBrokenCount = -1;
            doublemineHoldTicks = 0;
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
            return MC.level.getBlockState(
                    blockPos
            );
        }

        public BlockState getStartState() {
            BlockState current =
                    getBlockState();

            if (!current.isAir()
                    && current.getBlock()
                    != startState.getBlock()) {
                startState = current;
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
                float value
        ) {
            previousProgress = progress;
            progress = value;
        }

        public void resetProgress() {
            previousProgress = 0.0f;
            progress = 0.0f;
        }

        public float incrementProgress(
                float delta
        ) {
            previousProgress = progress;
            progress += delta;
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
