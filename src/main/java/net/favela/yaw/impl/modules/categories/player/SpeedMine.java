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
import java.util.LinkedHashMap;
import java.util.Map;

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

    private final Map<BlockBreakingTask, Integer> silentOwners =
            new LinkedHashMap<>();

    public BlockBreakingTask currentTask;
    public BlockBreakingTask doubleMineTask;

    private int silentPreviousSlot = -1;
    private int silentServerSlot = -1;
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

        currentTask = null;
        doubleMineTask = null;

        silentOwners.clear();
        silentPreviousSlot = -1;
        silentServerSlot = -1;
        doubleMinePreviousSlot = -1;
    }

    @Override
    public void onDisable() {
        if (currentTask != null && currentTask.isStarted()) {
            abortMining(currentTask);
        }

        if (doubleMineTask != null && doubleMineTask.isStarted()) {
            abortMining(doubleMineTask);
        }

        currentTask = null;
        doubleMineTask = null;

        clearSilentSwaps();
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

        if (doubleMineTask != null) {
            handleDoubleMineTask(doubleMineTask);
        }

        if (currentTask != null) {
            handleCurrentTask(currentTask);
        }
    }

    @Override
    public void onUpdate(
            net.favela.yaw.impl.event.events.UpdateEvent event
    ) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (currentTask != null && rotate.get() == Rotate.HOLD) {
            rotateServer(currentTask.getBlockPos());
        }
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (MC.level == null) {
            return;
        }

        if (doubleMineTask != null) {
            renderTask(event, doubleMineTask);
        }

        if (currentTask != null) {
            renderTask(event, currentTask);
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

        if (currentTask != null
                && currentTask.getBlockPos().equals(pos)) {
            event.cancel();
            return;
        }

        if (doubleMineTask != null
                && doubleMineTask.getBlockPos().equals(pos)) {
            event.cancel();
            return;
        }

        event.cancel();

        BlockBreakingTask previousTask = currentTask;

        if (previousTask != null) {
            if (doubleMine.get()
                    && doubleMineTask == null
                    && previousTask.isStarted()
                    && !previousTask.isCompleted()) {

                doubleMineTask = new BlockBreakingTask(
                        previousTask.getBlockPos(),
                        previousTask.getFacing(),
                        previousTask.getTargetSpeed()
                );

                doubleMineTask.copyStateFrom(previousTask);
                doubleMineTask.setSecondary(true);
            }

            if (!previousTask.isCompleted()) {
                abortMining(previousTask);
            }
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

        if (task.getStartState().isAir()) {
            return;
        }

        int slot = getBestToolSlot(task.getStartState());
        task.setToolSlot(slot);

        if (swap.get() == Swap.NORMAL && slot >= 0) {
            MC.player.getInventory().setSelectedSlot(slot);
        }

        if (swap.get() == Swap.SILENT && slot >= 0) {
            acquireSilentSwap(task, slot);
        }

        if (rotate.get() == Rotate.NORMAL) {
            rotateServer(task.getBlockPos());
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

        sendDestroyPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                task
        );

        task.markStarted();
        task.setMiningStarted(true);
    }

    private void abortMining(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (!task.isStarted()) {
            releaseSilentSwap(task);
            return;
        }

        if (task.isCompleted()) {
            releaseSilentSwap(task);
            return;
        }

        if (task.isInstantRemine()) {
            return;
        }

        if (swing.get()) {
            MC.player.swing(InteractionHand.MAIN_HAND);
        }

        sendDestroyPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                task
        );

        task.setAborted(true);

        releaseSilentSwap(task);
    }

    private void handleCurrentTask(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        Vec3 eye = MC.player.getEyePosition();
        AABB box = new AABB(task.getBlockPos());

        if (eye.distanceTo(closestPoint(eye, box)) > range.getDouble()) {
            if (task == currentTask) {
                releaseSilentSwap(task);
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

        if (!task.isInstantRemine()) {
            float damage = calculateBlockDamage(
                    task.getStartState(),
                    task.getBlockPos(),
                    task
            );

            if (task.incrementProgress(damage) >= task.getTargetSpeed()) {
                finishMining(task);
            }
        } else {
            finishMining(task);
        }
    }

    private void handleDoubleMineTask(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (!doubleMine.get()) {
            cleanupDoubleMine();
            return;
        }

        if (!task.isStarted()) {
            cleanupDoubleMine();
            return;
        }

        if (task.getDoublemineHoldTicks() >= 3) {
            finishDoubleMine(task);
            return;
        }

        Vec3 eye = MC.player.getEyePosition();
        AABB blockBox = new AABB(task.getBlockPos());

        if (eye.distanceTo(closestPoint(eye, blockBox)) > range.getDouble()) {
            cleanupDoubleMine();
            return;
        }

        if (!multitask.get() && MC.player.isUsingItem()) {
            return;
        }

        if (task.isCompleted()) {
            cleanupDoubleMine();
            return;
        }

        if (task.isServerBroken()) {
            finishDoubleMine(task);
            return;
        }

        BlockState state = task.getBlockState();

        if (state.isAir()) {
            task.markServerBroken();
            finishDoubleMine(task);
            return;
        }

        if (!task.isSecondaryStarted()) {
            startDoubleMine(task);
        }

        float damage = calculateBlockDamage(
                task.getStartState(),
                task.getBlockPos(),
                task
        );

        if (task.incrementProgress(damage) >= task.getTargetSpeed()) {
            finishDoubleMine(task);
        }
    }

    private void startDoubleMine(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        int slot = getBestToolSlot(task.getStartState());
        task.setToolSlot(slot);

        if (slot >= 0) {
            if (swap.get() == Swap.NORMAL) {
                if (doubleMinePreviousSlot == -1) {
                    doubleMinePreviousSlot =
                            MC.player.getInventory().getSelectedSlot();
                }

                MC.player.getInventory().setSelectedSlot(slot);
            } else if (swap.get() == Swap.SILENT) {
                acquireSilentSwap(task, slot);
            }
        }

        if (rotate.get() != Rotate.NONE) {
            rotateServer(task.getBlockPos());
        }

        sendDestroyPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                task
        );

        task.setSecondaryStarted(true);
        task.markStarted();
    }

    private void finishDoubleMine(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (task.isCompleted()) {
            cleanupDoubleMine();
            return;
        }

        if (rotate.get() != Rotate.NONE) {
            rotateServer(task.getBlockPos());
        }

        int slot = getBestToolSlot(task.getStartState());
        task.setToolSlot(slot);

        if (slot >= 0) {
            if (swap.get() == Swap.NORMAL) {
                if (doubleMinePreviousSlot == -1) {
                    doubleMinePreviousSlot =
                            MC.player.getInventory().getSelectedSlot();
                }

                MC.player.getInventory().setSelectedSlot(slot);
            } else if (swap.get() == Swap.SILENT) {
                acquireSilentSwap(task, slot);
            }
        }

        if (!task.isSecondaryStarted()) {
            sendDestroyPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    task
            );

            task.setSecondaryStarted(true);
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

        task.markCompleted();
        task.markLastBroken();

        releaseSilentSwap(task);
        restoreDoubleMineSlot();

        doubleMineTask = null;
    }

    private void cleanupDoubleMine() {
        if (doubleMineTask != null) {
            releaseSilentSwap(doubleMineTask);
        }

        doubleMineTask = null;
        restoreDoubleMineSlot();
    }

    private void finishMining(BlockBreakingTask task) {
        if (MC.player == null || MC.level == null) {
            return;
        }

        if (!task.isStarted()) {
            return;
        }

        if (task.isCompleted()) {
            return;
        }

        if (!multitask.get() && MC.player.isUsingItem()) {
            return;
        }

        if (task.isInstantRemine()) {
            if (!instantRemineTimer.passedMs(instantDelay.getLong())) {
                return;
            }

            if (!task.isRemineStarted()) {
                if (rotate.get() == Rotate.NORMAL) {
                    rotateServer(task.getBlockPos());
                }

                int slot = getBestToolSlot(task.getStartState());
                task.setToolSlot(slot);

                if (slot >= 0) {
                    acquireSilentSwap(task, slot);
                }

                if (slot >= 0) {
                    sendDestroyPacket(
                            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                            task
                    );
                }

                task.setRemineStarted(true);
                task.setCompleted(false);
                task.setServerBroken(false);
                task.setProgress(0.0f);
                task.setPreviousProgress(0.0f);
            }

            if (task.getBlockState().isAir()) {
                if (!instantRemineResetTimer.passedMs(50L)) {
                    return;
                }
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

            task.markCompleted();
            task.markLastBroken();

            instantRemineTimer.reset();
            instantRemineResetTimer.reset();

            releaseSilentSwap(task);

            return;
        }

        if (task.getBrokenCount() != task.getLastBrokenCount()) {
            instantRemineResetTimer.reset();
        }

        if (rotate.get() == Rotate.NORMAL) {
            rotateServer(task.getBlockPos());
        }

        int slot = getBestToolSlot(task.getStartState());
        task.setToolSlot(slot);

        if (swap.get() == Swap.NORMAL && slot >= 0) {
            MC.player.getInventory().setSelectedSlot(slot);
        }

        if (swap.get() == Swap.SILENT && slot >= 0) {
            acquireSilentSwap(task, slot);
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

        task.markCompleted();
        task.markLastBroken();

        if (simulate.get() && !task.getBlockState().isAir()) {
            task.setClientSimulated(true);
        }

        releaseSilentSwap(task);
    }

    private void onPacketReceive(PacketEvent.Receive event) {
        if (MC.level == null || MC.player == null) {
            return;
        }

        if (!(event.packet() instanceof ClientboundBlockUpdatePacket packet)) {
            return;
        }

        BlockPos pos = packet.getPos();
        BlockState state = packet.getBlockState();

        if (currentTask != null
                && currentTask.getBlockPos().equals(pos)) {

            currentTask.markBroken();

            if (state.isAir()) {
                currentTask.markServerBroken();
                currentTask.markInstantRemine();
                currentTask.setProgress(
                        currentTask.getTargetSpeed()
                );
                instantRemineResetTimer.reset();
                currentTask.setCompleted(false);
            }
        }

        if (doubleMineTask != null
                && doubleMineTask.getBlockPos().equals(pos)) {

            doubleMineTask.markBroken();

            if (state.isAir()) {
                doubleMineTask.markServerBroken();
                doubleMineTask.setCompleted(false);
            }
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

        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(
                Math.atan2(dz, dx)
        ) - 90.0f;

        float pitch = (float) -Math.toDegrees(
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

    private void acquireSilentSwap(
            BlockBreakingTask task,
            int slot
    ) {
        if (MC.player == null || slot < 0 || slot > 8) {
            return;
        }

        if (silentPreviousSlot == -1) {
            silentPreviousSlot =
                    MC.player.getInventory().getSelectedSlot();

            silentServerSlot = silentPreviousSlot;
        }

        Integer previousOwnerSlot = silentOwners.put(task, slot);

        if (previousOwnerSlot != null
                && previousOwnerSlot == slot
                && silentServerSlot == slot) {
            return;
        }

        if (silentServerSlot != slot) {
            MC.player.connection.send(
                    new ServerboundSetCarriedItemPacket(slot)
            );

            silentServerSlot = slot;
        }
    }

    private void releaseSilentSwap(BlockBreakingTask task) {
        if (task == null) {
            return;
        }

        if (!silentOwners.containsKey(task)) {
            return;
        }

        silentOwners.remove(task);

        if (silentOwners.isEmpty()) {
            restoreSilentSlot();
            return;
        }

        int nextSlot = -1;

        for (Integer slot : silentOwners.values()) {
            nextSlot = slot;
        }

        if (nextSlot >= 0 && silentServerSlot != nextSlot) {
            MC.player.connection.send(
                    new ServerboundSetCarriedItemPacket(nextSlot)
            );

            silentServerSlot = nextSlot;
        }
    }

    private void restoreSilentSlot() {
        if (MC.player == null) {
            silentOwners.clear();
            silentPreviousSlot = -1;
            silentServerSlot = -1;
            return;
        }

        if (silentPreviousSlot == -1) {
            silentOwners.clear();
            silentServerSlot = -1;
            return;
        }

        int slot = silentPreviousSlot;

        if (silentServerSlot != slot) {
            MC.player.connection.send(
                    new ServerboundSetCarriedItemPacket(slot)
            );
        }

        silentOwners.clear();
        silentPreviousSlot = -1;
        silentServerSlot = -1;
    }

    private void clearSilentSwaps() {
        restoreSilentSlot();
    }

    private void restoreDoubleMineSlot() {
        if (MC.player == null) {
            doubleMinePreviousSlot = -1;
            return;
        }

        if (doubleMinePreviousSlot == -1) {
            return;
        }

        int slot = doubleMinePreviousSlot;

        MC.player.getInventory().setSelectedSlot(slot);

        if (silentOwners.isEmpty()) {
            MC.player.connection.send(
                    new ServerboundSetCarriedItemPacket(slot)
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
                    MC.player.getInventory().getItem(slot);

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

        float speedValue = stack.getDestroySpeed(state);

        if (speedValue <= 1.0f) {
            return speedValue;
        }

        int efficiency = getEfficiency(stack);

        if (efficiency > 0) {
            speedValue += efficiency * efficiency + 1;
        }

        return speedValue;
    }

    private int getEfficiency(ItemStack stack) {
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
            BlockPos pos,
            BlockBreakingTask task
    ) {
        if (MC.level == null || MC.player == null) {
            return 0.0f;
        }

        float hardness =
                state.getDestroySpeed(MC.level, pos);

        if (hardness < 0.0f) {
            return 0.0f;
        }

        int divisor =
                canHarvest(state, task) ? 30 : 100;

        float miningSpeed =
                getMiningSpeed(state, task);

        float damage =
                miningSpeed
                        / hardness
                        / divisor;

        if (task == currentTask) {
            damage *= speed.getFloat();
        }

        return damage;
    }

    private boolean canHarvest(
            BlockState state,
            BlockBreakingTask task
    ) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }

        int slot = getBestToolSlot(state);

        if (slot < 0) {
            return false;
        }

        return MC.player.getInventory()
                .getItem(slot)
                .isCorrectToolForDrops(state);
    }

    private float getMiningSpeed(
            BlockState state,
            BlockBreakingTask task
    ) {
        if (MC.player == null) {
            return 1.0f;
        }

        ItemStack stack;

        if (task.getToolSlot() >= 0) {
            stack = MC.player.getInventory()
                    .getItem(task.getToolSlot());
        } else {
            stack = MC.player.getMainHandItem();
        }

        if (stack.isEmpty()) {
            stack = MC.player.getMainHandItem();
        }

        float miningSpeed =
                stack.getDestroySpeed(state);

        if (miningSpeed < 1.0f) {
            miningSpeed = 1.0f;
        }

        if (miningSpeed > 1.0f) {
            int efficiency = getEfficiency(stack);

            if (efficiency > 0) {
                miningSpeed +=
                        efficiency * efficiency + 1;
            }
        }

        if (MC.player.hasEffect(MobEffects.HASTE)) {
            int amplifier =
                    MC.player.getEffect(
                            MobEffects.HASTE
                    ).getAmplifier() + 1;

            miningSpeed *=
                    1.0f + amplifier * 0.2f;
        }

        if (MC.player.hasEffect(
                MobEffects.MINING_FATIGUE
        )) {
            int amplifier =
                    MC.player.getEffect(
                            MobEffects.MINING_FATIGUE
                    ).getAmplifier();

            miningSpeed *= switch (amplifier) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 0.00081f;
            };
        }

        if (MC.player.isEyeInFluid(FluidTags.WATER)
                && !hasAquaAffinity(
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
                clamp(point.x, box.minX, box.maxX),
                clamp(point.y, box.minY, box.maxY),
                clamp(point.z, box.minZ, box.maxZ)
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

    private void renderTask(
            Render3DEvent event,
            BlockBreakingTask task
    ) {
        if (MC.level == null) {
            return;
        }

        BlockPos pos = task.getBlockPos();

        BlockState currentState =
                MC.level.getBlockState(pos);

        BlockState renderState =
                currentState.isAir()
                        ? task.getStartState()
                        : currentState;

        if (renderState == null || renderState.isAir()) {
            return;
        }

        VoxelShape shape =
                task.isInstantRemine()
                        ? Shapes.block()
                        : renderState.getShape(
                                MC.level,
                                pos
                        );

        if (shape.isEmpty()) {
            shape = Shapes.block();
        }

        AABB bounds = shape.bounds();

        AABB worldBox = new AABB(
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

        if (task.isServerBroken()) {
            scale = 1.0f;
        }

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

        int red =
                (int) (200.0f * (1.0f - scale));

        int green =
                (int) (200.0f * scale);

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

    public static class BlockBreakingTask {
        private final BlockPos blockPos;
        private final Direction facing;
        private final float targetSpeed;

        private BlockState startState;

        private float progress;
        private float previousProgress;

        private boolean instantRemine;
        private boolean started;
        private boolean aborted;
        private boolean completed;

        private boolean secondary;
        private boolean secondaryStarted;
        private boolean remineStarted;
        private boolean miningStarted;
        private boolean serverBroken;
        private boolean clientSimulated;

        private int toolSlot = -1;

        private int brokenCount;
        private int lastBrokenCount = -1;

        private int doublemineHoldTicks;

        public BlockBreakingTask(
                BlockPos blockPos,
                Direction facing,
                float targetSpeed
        ) {
            this.blockPos = blockPos.immutable();
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
            if (MC.level == null) {
                return startState;
            }

            return MC.level.getBlockState(blockPos);
        }

        public BlockState getStartState() {
            if (MC.level != null) {
                BlockState state =
                        MC.level.getBlockState(blockPos);

                if (!state.isAir()
                        && state.getBlock()
                        != startState.getBlock()) {
                    startState = state;
                }
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

        public void setProgress(float progress) {
            previousProgress = this.progress;
            this.progress = progress;
        }

        public void setPreviousProgress(float progress) {
            this.previousProgress = progress;
        }

        public void resetProgress() {
            previousProgress = 0.0f;
            progress = 0.0f;
        }

        public float incrementProgress(float amount) {
            previousProgress = progress;
            progress += amount;

            if (progress > targetSpeed) {
                progress = targetSpeed;
            }

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

        public void setDoublemineHoldTicks(int ticks) {
            doublemineHoldTicks = ticks;
        }

        public boolean isCompleted() {
            return completed;
        }

        public void markCompleted() {
            completed = true;
        }

        public void setCompleted(boolean completed) {
            this.completed = completed;
        }

        public boolean isAborted() {
            return aborted;
        }

        public void setAborted(boolean aborted) {
            this.aborted = aborted;
        }

        public boolean isSecondary() {
            return secondary;
        }

        public void setSecondary(boolean secondary) {
            this.secondary = secondary;
        }

        public boolean isSecondaryStarted() {
            return secondaryStarted;
        }

        public void setSecondaryStarted(boolean secondaryStarted) {
            this.secondaryStarted = secondaryStarted;
        }

        public boolean isRemineStarted() {
            return remineStarted;
        }

        public void setRemineStarted(boolean remineStarted) {
            this.remineStarted = remineStarted;
        }

        public boolean isMiningStarted() {
            return miningStarted;
        }

        public void setMiningStarted(boolean miningStarted) {
            this.miningStarted = miningStarted;
        }

        public boolean isServerBroken() {
            return serverBroken;
        }

        public void markServerBroken() {
            serverBroken = true;
        }

        public void setServerBroken(boolean serverBroken) {
            this.serverBroken = serverBroken;
        }

        public boolean isClientSimulated() {
            return clientSimulated;
        }

        public void setClientSimulated(boolean clientSimulated) {
            this.clientSimulated = clientSimulated;
        }

        public int getToolSlot() {
            return toolSlot;
        }

        public void setToolSlot(int toolSlot) {
            this.toolSlot = toolSlot;
        }

        public void copyStateFrom(BlockBreakingTask other) {
            this.startState = other.startState;
            this.progress = other.progress;
            this.previousProgress = other.previousProgress;
            this.instantRemine = other.instantRemine;
            this.started = other.started;
            this.aborted = false;
            this.completed = false;
            this.brokenCount = other.brokenCount;
            this.lastBrokenCount = other.lastBrokenCount;
            this.toolSlot = other.toolSlot;
            this.serverBroken = other.serverBroken;
            this.clientSimulated = false;
            this.remineStarted = false;
            this.miningStarted = other.miningStarted;
            this.secondaryStarted = false;
            this.doublemineHoldTicks = 0;
        }
    }
}
