package net.favela.yaw.impl.modules.categories.player;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.event.events.Render3DEvent;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.BooleanSetting;
import net.favela.yaw.impl.setting.settings.EnumSetting;
import net.favela.yaw.impl.setting.settings.NumberSetting;
import net.favela.yaw.impl.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

/**
 * Design adapted from NamiDevelopment/nami-public's SpeedMineFeature (MIT licensed,
 * github.com/NamiDevelopment/nami-public). The core mechanism ported over: fire a decoy
 * START immediately followed by ABORT rather than holding a real dig session open, track
 * break progress with an accurate client-side vanilla mining-speed simulation each tick,
 * and only send a bare STOP once that simulated progress reaches the target fraction.
 * Settings not portable without Nami's rotation/inventory-swap subsystems (Rotate, the
 * 1.21 offhand-eating check, silent-swap timing modes) are omitted; a straightforward
 * sustained pickaxe hold is used instead of Nami's finish-time silent swap.
 */
@AutoService(Module.class)
public class SpeedMine extends Module {

    public enum SwapMode { None, Normal, Silent }

    public static SpeedMine INSTANCE;

    public final NumberSetting range = num("Range", 2.0f, 7.0f, 4.5f);
    public final NumberSetting speed = num("Speed", 0.7f, 1.0f, 1.0f);
    public final EnumSetting<SwapMode> swap = enm("Swap", SwapMode.Normal);
    public final BooleanSetting doubleMine = register(new BooleanSetting("DoubleMine", "Keeps a second block's progress alive when you look at a new one before finishing", false));
    public final BooleanSetting instant = register(new BooleanSetting("Instant", "Immediately re-arms the same spot if a new block appears there", true));
    public final NumberSetting instantDelay = num("InstantDelay", 0, 1000, 0);
    public final BooleanSetting swing = register(new BooleanSetting("Swing", "Sends an arm swing packet each tick while mining", true));
    public final BooleanSetting multitask = register(new BooleanSetting("Multitask", "Allows finishing a break while eating/using an item", false));

    private static class Task {
        BlockPos pos;
        Direction face;
        BlockState startState;
        float progress;
        float previousProgress;
        boolean started;
        boolean instantRemine;
        long lastFinishMs;
    }

    private Task current;
    private Task secondary;
    private int originalSlot = -1;

    public SpeedMine() {
        super("SpeedMine", "Breaks blocks faster using an accurate client-side dig simulation", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onDisable() {
        if (current != null && current.started) abort(current);
        current = null;
        secondary = null;
        endPickaxeHold();
    }

    @Override
    public void onTick() {
        if (MC.player == null || MC.level == null) return;

        boolean attackDown = MC.options.keyAttack.isDown();

        if (attackDown && MC.hitResult instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            if (current == null || !current.pos.equals(pos)) {
                onNewTarget(pos, blockHit.getDirection());
            }
        }

        if (current != null) tickCurrent();
        if (secondary != null) tickSecondary();

        if (current == null && secondary == null) {
            endPickaxeHold();
        }
    }

    private void onNewTarget(BlockPos pos, Direction face) {
        BlockState state = MC.level.getBlockState(pos);
        if (state.isAir() || !inRange(pos)) return;

        if (current != null && !current.instantRemine) {
            if (doubleMine.get() && secondary == null) {
                secondary = current;
            } else {
                abort(current);
            }
        }

        beginPickaxeHold();

        Task t = new Task();
        t.pos = pos.immutable();
        t.face = face;
        t.startState = state;
        current = t;

        if (swap.get() == SwapMode.Normal) {
            // visible swap: leave whatever the sustained pickaxe hold already selected
        }

        send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, t.pos, t.face);
        send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, t.pos, t.face);
        t.started = true;
    }

    private void tickCurrent() {
        Task t = current;

        if (!inRange(t.pos)) {
            current = null;
            return;
        }

        BlockState state = MC.level.getBlockState(t.pos);
        if (state.isAir()) {
            if (instant.get()) {
                t.instantRemine = true;
                t.progress = 1.0f;
            } else {
                current = null;
                return;
            }
        }

        if (swing.get()) {
            MC.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }

        float delta = damagePerTick(state, t.pos);
        t.previousProgress = t.progress;
        t.progress += delta;

        if (t.progress >= speed.getFloat() || t.instantRemine) {
            finish(t);
        }
    }

    private void tickSecondary() {
        Task t = secondary;

        if (!inRange(t.pos)) {
            secondary = null;
            return;
        }

        BlockState state = MC.level.getBlockState(t.pos);
        if (state.isAir()) {
            secondary = null;
            return;
        }

        float delta = damagePerTick(state, t.pos);
        t.previousProgress = t.progress;
        t.progress += delta;

        if (t.progress >= speed.getFloat()) {
            finish(t);
            secondary = null;
        }
    }

    private void finish(Task t) {
        if (!t.started) return;
        if (!multitask.get() && MC.player.isUsingItem()) return;
        if (System.currentTimeMillis() - t.lastFinishMs < instantDelay.getInt()) return;

        if (swap.get() == SwapMode.Silent) {
            int slot = findBestPickaxe();
            if (slot >= 0 && slot != MC.player.getInventory().getSelectedSlot()) {
                MC.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            }
        }

        send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, t.pos, t.face);
        if (swing.get()) {
            MC.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }

        t.lastFinishMs = System.currentTimeMillis();
        t.progress = 0f;
        t.previousProgress = 0f;
        t.instantRemine = false;

        if (t == current && !instant.get()) {
            current = null;
        }
    }

    private void abort(Task t) {
        if (!t.started) return;
        send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, t.pos, t.face);
    }

    private boolean inRange(BlockPos pos) {
        return MC.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= range.getFloat();
    }

    private float damagePerTick(BlockState state, BlockPos pos) {
        float hardness = state.getDestroySpeed(MC.level, pos);
        if (hardness < 0) return 0f;

        ItemStack stack = MC.player.getMainHandItem();
        boolean correctTool = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);

        float toolSpeed = stack.getDestroySpeed(state);
        if (toolSpeed <= 0) toolSpeed = 1.0f;

        if (MobEffectUtil.hasDigSpeed(MC.player)) {
            int amplifier = MobEffectUtil.getDigSpeedAmplification(MC.player) + 1;
            toolSpeed *= 1.0f + amplifier * 0.2f;
        }

        if (MC.player.hasEffect(MobEffects.MINING_FATIGUE)) {
            int amp = MC.player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier();
            float multiplier = switch (amp) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 0.00081f;
            };
            toolSpeed *= multiplier;
        }

        if (MC.player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)
                && !MC.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                        .is(net.minecraft.world.item.enchantment.Enchantments.AQUA_AFFINITY.location())) {
            toolSpeed /= 5.0f;
        }

        if (!MC.player.onGround()) toolSpeed /= 5.0f;

        int divisor = correctTool ? 30 : 100;
        return toolSpeed / hardness / divisor;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (MC.level == null) return;
        if (current != null) drawProgress(event, current, new Color(102, 200, 102, 220));
        if (secondary != null) drawProgress(event, secondary, new Color(200, 160, 90, 200));
    }

    private void drawProgress(Render3DEvent event, Task t, Color color) {
        BlockState state = MC.level.getBlockState(t.pos);
        if (state.isAir() && !t.instantRemine) return;

        AABB fullBox = new AABB(t.pos);
        float scale = Math.max(0f, Math.min(1f, t.progress / speed.getFloat()));

        double cx = t.pos.getX() + 0.5;
        double cy = t.pos.getY() + 0.5;
        double cz = t.pos.getZ() + 0.5;
        double half = 0.5 * scale;
        AABB growingBox = new AABB(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);

        RenderUtil.drawBoxOutline(event.getMatrix(), fullBox, new Color(255, 255, 255, 150), 1.0f);
        RenderUtil.drawBoxFilled(event.getMatrix(), growingBox, color);
    }

    private void beginPickaxeHold() {
        if (swap.get() == SwapMode.None || originalSlot != -1) return;

        int currentSlot = MC.player.getInventory().getSelectedSlot();
        int pickaxeSlot = findBestPickaxe();

        if (pickaxeSlot < 0 || pickaxeSlot == currentSlot) return;

        originalSlot = currentSlot;
        MC.player.connection.send(new ServerboundSetCarriedItemPacket(pickaxeSlot));
    }

    private void endPickaxeHold() {
        if (originalSlot == -1) return;
        MC.player.connection.send(new ServerboundSetCarriedItemPacket(originalSlot));
        originalSlot = -1;
    }

    private int findBestPickaxe() {
        int best = -1;
        float bestSpeed = -1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (!stack.is(ItemTags.PICKAXES)) continue;
            float sp = stack.getDestroySpeed(Blocks.STONE.defaultBlockState());
            if (sp > bestSpeed) {
                bestSpeed = sp;
                best = i;
            }
        }
        return best;
    }

    private void send(ServerboundPlayerActionPacket.Action action, BlockPos target, Direction face) {
        MC.player.connection.send(new ServerboundPlayerActionPacket(action, target, face));
    }
}
