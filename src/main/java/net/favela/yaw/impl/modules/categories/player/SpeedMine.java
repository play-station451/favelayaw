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
import net.favela.yaw.impl.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.tags.FluidTags;
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
            "Use the extra start/stop sequence",
            false
    );

    public final BooleanSetting doubleMine = bool(
            "DoubleMine",
            "Keep the previous block mining in the background",
            false
    );

    public final BooleanSetting instant = bool(
            "Instant",
            "Remine blocks as soon as the server confirms the break",
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
            "Update the local world immediately after finishing",
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

    private BlockBreakingTask currentTask;
    private BlockBreakingTask doubleMineTask;

    private long lastInstantBreak;
    private long lastServerBreak;

    private int silentSlot = -1;
    private int silentPreviousSlot = -1;

    private Events.Handler<StartBreakingBlockEvent> startHandler;
    private Events.Handler<PacketEvent.Receive> packetHandler;

    public SpeedMine() {
        super(
                "SpeedMine",
                "Increases mining speed.",
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
    }

    @Override
    public void onDisable() {
        if (startHandler != null) {
            Events.off(startHandler);
            startHandler = null;
        }

        if (packetHandler != null) {
            Events.off(packetHandler);
            packetHandler = null;
        }

        if (currentTask != null && currentTask.started) {
            abortMining(currentTask);
        }

        restoreSilentSlot();

        currentTask = null;
        doubleMineTask = null;

        silentSlot = -1;
        silentPreviousSlot = -1;
    }

    @Override
    public void onTick() {
        if (MC.level == null || MC.player == null) {
            return;
        }

        if (currentTask != null) {
            handleMiningTick(currentTask);
        }

        if (doubleMine.get() && doubleMineTask != null) {
            handleDoubleMine(doubleMineTask);
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

    @Override
    public void onUpdate(net.favela.yaw.impl.event.events.UpdateEvent event) {
        if (MC.level == null || MC.player == null || currentTask == null) {
            return;
        }

        if (rotate.get() == Rotate.HOLD) {
            rotateServer(currentTask.getBlockPos());
        }
    }

    public void onStartBreaking(StartBreakingBlockEvent event) {
        if (MC.level == null || MC.player == null) {
            return;
        }

        BlockPos pos = event.getBlockPos();

        if (MC.level.getBlockState(pos).isAir()) {
            return;
        }

        event.cancel();

        if (doubleMineTask != null
                && doubleMineTask.getBlockPos().equals(pos)) {
            return;
        }

        if (currentTask != null
                && currentTask.getBlockPos().equals(pos)) {
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

        lastInstantBreak = 0L;
        lastServerBreak = 0L;

        startMining(currentTask);

        if (swing.get()) {
            MC.player.swing(
                    net.minecraft.world.InteractionHand.MAIN_HAND
            );
        }
    }

    @Override
    public void onRenderBlockOutline(
            net.favela.yaw.impl.event.events.RenderBlockOutlineEvent event
    ) {
        if (currentTask != null
                && currentTask.getBlockPos() != null) {
            event.cancel();
        }
    }

    public void handleStart(StartBreakingBlockEvent event) {
        onStartBreaking(event);
    }

    private void handleMiningTick(BlockBreakingTask task) {
        if (MC.level == null || MC.player == null) {
            return;
        }

        BlockPos pos = task.getBlockPos();

        AABB box = new AABB(pos);

        Vec3 eye = MC.player.getEyePosition();

        if (eye.distanceTo(closestPoint(eye, box)) > range.getDouble()) {
            if (task == currentTask) {
                currentTask = null;
            }

            return;
        }

        if (task.getBlockState().isAir()) {
            if (instant.get()) {
                task.instantRemine = true;
                task.setProgress(1.0f);
            } else {
                task.resetProgress();
                return;
            }
        }

        if (swing.get()) {
            MC.player.swing(
                    net.minecraft.world.InteractionHand.MAIN_HAND
            );
        }

        if (rotate.get() == Rotate.HOLD) {
            rotateServer(pos);
        }

        float delta = calculateBlockDamage(
                task.getStartState(),
                pos
        );

        if (task.incrementProgress(delta) >= task.targetSpeed
                || task.instantRemine) {
            finishMining(task);
        }
    }

    private void handleDoubleMine(BlockBreakingTask task) {
        if (!doubleMine.get()
                || MC.player == null
                || MC.level == null) {
            return;
        }

        if (task.doubleMineHoldTicks >= 3) {
            doubleMineTask = null;
            restoreSilentSlot();
            return;
        }

        Vec3 eye = MC.player.getEyePosition();

        AABB box = new AABB(task.getBlockPos());

        if (eye.distanceTo(closestPoint(eye, box))
                > range.getDouble()) {
            doubleMineTask = null;
            return;
        }

        if (task.getBlockState().isAir()) {
            doubleMineTask = null;
            return;
        }

        if (!multitask.get()
                && MC.player.isUsingItem()) {
            return;
        }

        float delta = calculateBlockDamage(
                task.getStartState(),
                task.getBlockPos()
        );

        if (task.incrementProgress(delta)
                >= task.targetSpeed) {
            int slot = getBestToolSlot(
                    task.getStartState()
            );

            if (slot >= 0) {
                switchTo(slot, true);
                task.doubleMineHoldTicks++;
            }
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
            switchTo(slot, true);
        }

        if (grim.get()) {
            sendAction(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    task
            );

            sendAction(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    task
            );

            sendAction(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    task
            );
        }

        task.started = true;
    }

    private void abortMining(BlockBreakingTask task) {
        if (!task.started
                || task.getBlockState().isAir()
                || task.instantRemine
                || task.progress >= 1.0f) {
            return;
        }

        if (grim.get()) {
            sendAction(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    task
            );
        }

        if (swing.get()) {
            MC.player.swing(
                    net.minecraft.world.InteractionHand.MAIN_HAND
            );
        }

        sendAction(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                task
        );
    }

    private void finishMining(BlockBreakingTask task) {
        if (!task.started
                || MC.player == null
                || MC.level == null) {
            return;
        }

        if (!multitask.get()
                && MC.player.isUsingItem()) {
            return;
        }

        long now = System.currentTimeMillis();

        if (task.instantRemine
                && now - lastInstantBreak
                < instantDelay.getLong()) {
            return;
        }

        if (task.brokenCount != task.lastBrokenCount) {
            lastServerBreak = now;
            return;
        }

        if (task.instantRemine
                && task.getBlockState().isAir()
                && now - lastServerBreak > 250L) {
            return;
        }

        if (rotate.get() == Rotate.NORMAL) {
            rotateServer(task.getBlockPos());
        }

        int slot = getBestToolSlot(
                task.getStartState()
        );

        if (swap.get() == Swap.SILENT && slot >= 0) {
            switchTo(slot, true);
        }

        if (grim.get()) {
            sendAction(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    task
            );
        }

        if (swing.get()) {
            MC.player.swing(
                    net.minecraft.world.InteractionHand.MAIN_HAND
            );
        }

        sendAction(
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

        if (task.instantRemine) {
            lastInstantBreak = now;
        }
    }

    public void onPacketReceive(PacketEvent.Receive event) {
        if (currentTask == null) {
            return;
        }

        if (event.packet()
                instanceof ClientboundBlockUpdatePacket packet
                && packet.getPos().equals(
                        currentTask.getBlockPos()
                )) {
            currentTask.markBroken();
            lastServerBreak = System.currentTimeMillis();
        }
    }

    private void sendAction(
            ServerboundPlayerActionPacket.Action action,
            BlockBreakingTask task
    ) {
        MC.player.connection.send(
                new ServerboundPlayerActionPacket(
                        action,
                        task.getBlockPos(),
                        task.getFacing(),
                        0
                )
        );
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

        double horizontal = Math.sqrt(
                dx * dx + dz * dz
        );

        float yaw =
                (float) Math.toDegrees(
                        Math.atan2(dz, dx)
                ) - 90.0f;

        float pitch =
                (float) -Math.toDegrees(
                        Math.atan2(dy, horizontal)
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

    private void switchTo(
            int slot,
            boolean silent
    ) {
        if (MC.player == null
                || slot < 0
                || slot > 8) {
            return;
        }

        int current =
                MC.player.getInventory().getSelectedSlot();

        if (!silent) {
            if (current != slot) {
                MC.player.getInventory().setSelectedSlot(slot);
            }

            return;
        }

        if (current == slot) {
            return;
        }

        silentPreviousSlot = current;
        silentSlot = slot;

        MC.player.connection.send(
                new ServerboundSetCarriedItemPacket(slot)
        );
    }

    private void restoreSilentSlot() {
        if (MC.player == null
                || silentPreviousSlot < 0) {
            return;
        }

        MC.player.connection.send(
                new ServerboundSetCarriedItemPacket(
                        silentPreviousSlot
                )
        );

        silentSlot = -1;
        silentPreviousSlot = -1;
    }

    private int getBestToolSlot(
            net.minecraft.world.level.block.state.BlockState state
    ) {
        if (MC.player == null) {
            return -1;
        }

        int best =
                MC.player.getInventory().getSelectedSlot();

        float bestSpeed = 1.0f;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack =
                    MC.player.getInventory().getItem(slot);

            if (stack.isEmpty()) {
                continue;
            }

            float toolSpeed =
                    getToolSpeed(stack, state);

            if (toolSpeed > bestSpeed) {
                bestSpeed = toolSpeed;
                best = slot;
            }
        }

        return best;
    }

    private float getToolSpeed(
            ItemStack stack,
            net.minecraft.world.level.block.state.BlockState state
    ) {
        if (!stack.isCorrectToolForDrops(state)) {
            return 1.0f;
        }

        int efficiency = getEfficiency(stack);

        return stack.getDestroySpeed(state)
                * (1.0f + efficiency * 0.2f);
    }

    private int getEfficiency(ItemStack stack) {
        try {
            return stack.getEnchantments().getLevel(
                    MC.level.registryAccess()
                            .lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.EFFICIENCY)
            );
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean hasAquaAffinity(ItemStack stack) {
        try {
            return stack.getEnchantments().getLevel(
                    MC.level.registryAccess()
                            .lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.AQUA_AFFINITY)
            ) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private float calculateBlockDamage(
            net.minecraft.world.level.block.state.BlockState state,
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
            net.minecraft.world.level.block.state.BlockState state
    ) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }

        int slot = getBestToolSlot(state);

        if (slot < 0) {
            return false;
        }

        return MC.player
                .getInventory()
                .getItem(slot)
                .isCorrectToolForDrops(state);
    }

    private float getMiningSpeed(
            net.minecraft.world.level.block.state.BlockState state
    ) {
        ItemStack stack;

        if (swap.get() == Swap.SILENT) {
            int slot = getBestToolSlot(state);

            stack = slot >= 0
                    ? MC.player.getInventory().getItem(slot)
                    : MC.player.getMainHandItem();
        } else {
            stack = MC.player.getMainHandItem();
        }

        float value =
                stack.getDestroySpeed(state);

        if (value > 1.0f) {
            int efficiency =
                    getEfficiency(stack);

            if (efficiency > 0) {
                value +=
                        efficiency * efficiency + 1;
            }
        }

        MobEffectInstance haste =
                MC.player.getEffect(MobEffects.HASTE);

        if (haste != null) {
            value *=
                    1.0f
                    + (haste.getAmplifier() + 1)
                    * 0.2f;
        }

        MobEffectInstance fatigue =
                MC.player.getEffect(
                        MobEffects.MINING_FATIGUE
                );

        if (fatigue != null) {
            value *= switch (
                    fatigue.getAmplifier()
            ) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 0.00081f;
            };
        }

        ItemStack helmet =
                MC.player.getItemBySlot(
                        EquipmentSlot.HEAD
                );

        boolean aquaAffinity =
                hasAquaAffinity(helmet);

        if (MC.player.isEyeInFluid(FluidTags.WATER)
                && !aquaAffinity) {
            value /= 5.0f;
        }

        if (!MC.player.onGround()) {
            value /= 5.0f;
        }

        return value;
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
                Math.min(max, value)
        );
    }

    private void renderProgress(
            Render3DEvent event,
            BlockBreakingTask task
    ) {
        BlockPos pos =
                task.getBlockPos();

        if (MC.level
                .getBlockState(pos)
                .isAir()) {
            return;
        }

        VoxelShape shape =
                task.instantRemine
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
                task.previousProgress
                        + (
                        task.progress
                                - task.previousProgress
                ) * event.getDelta();

        float scale =
                Math.max(
                        0.0f,
                        Math.min(
                                1.0f,
                                progress
                                        / Math.max(
                                                task.targetSpeed,
                                                0.0001f
                                        )
                        )
                );

        double dx =
                (bounds.maxX - bounds.minX)
                        * 0.5;

        double dy =
                (bounds.maxY - bounds.minY)
                        * 0.5;

        double dz =
                (bounds.maxZ - bounds.minZ)
                        * 0.5;

        AABB renderBox =
                new AABB(
                        center,
                        center
                ).inflate(
                        dx * scale,
                        dy * scale,
                        dz * scale
                );

        int green =
                (int) (200.0f * scale);

        int red =
                200 - green;

        RenderUtil.drawBoxOutline(
                event.getMatrix(),
                renderBox,
                new Color(
                        red,
                        green,
                        0,
                        255
                ),
                1.5f
        );
    }

    public static final class BlockBreakingTask {
        private final BlockPos blockPos;
        private final Direction facing;
        private final float targetSpeed;

        private net.minecraft.world.level.block.state.BlockState startState;

        private float progress;
        private float previousProgress;

        private boolean instantRemine;
        private boolean started;

        private int brokenCount;
        private int lastBrokenCount = -1;

        private int doubleMineHoldTicks;

        public BlockBreakingTask(
                BlockPos pos,
                Direction facing,
                float speed
        ) {
            this.blockPos = pos;
            this.facing = facing;
            this.targetSpeed = speed;

            this.startState =
                    MC.level.getBlockState(pos);
        }

        public BlockPos getBlockPos() {
            return blockPos;
        }

        public Direction getFacing() {
            return facing;
        }

        public net.minecraft.world.level.block.state.BlockState getBlockState() {
            return MC.level.getBlockState(
                    blockPos
            );
        }

        public net.minecraft.world.level.block.state.BlockState getStartState() {
            net.minecraft.world.level.block.state.BlockState state =
                    getBlockState();

            if (!state.isAir()
                    && state.getBlock()
                    != startState.getBlock()) {
                startState = state;
            }

            return startState;
        }

        public float getProgress() {
            return progress;
        }

        public void setProgress(float value) {
            previousProgress = progress;
            progress = value;
        }

        public void resetProgress() {
            progress = 0.0f;
            previousProgress = 0.0f;
            instantRemine = false;
        }

        public float incrementProgress(float delta) {
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
    }
}
