package net.favela.yaw.impl.modules.categories.player;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.event.events.Render3DEvent;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.BooleanSetting;
import net.favela.yaw.impl.setting.settings.ColorSetting;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class SpeedMine extends Module {

    public enum Mode { Single, Double, Quadruple }

    public static SpeedMine INSTANCE;

    public final EnumSetting<Mode> mode = enm("Mode", Mode.Double);
    public final BooleanSetting instantRebreak = register(new BooleanSetting("InstantRebreak", "Keeps mining new blocks automatically while held, with no delay between them", true));
    public final BooleanSetting autoSwitch = register(new BooleanSetting("AutoSwitch", "Silently switches to the best pickaxe while mining", true));
    public final NumberSetting threshold = num("Threshold", 0.5f, 1.0f, 0.7f);
    public final ColorSetting boxColor = color("Color", new Color(167, 123, 234, 200), true);

    private static class Target {
        BlockPos pos;
        Direction face;
        long startMs;
        long requiredMs;
    }

    private Target current;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final List<BlockPos> queuedOutline = new ArrayList<>();

    private int originalSlot = -1;

    public SpeedMine() {
        super("SpeedMine", "Breaks multiple adjacent blocks in quick succession", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onDisable() {
        if (current != null) {
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, current.pos, current.face);
        }
        current = null;
        queue.clear();
        queuedOutline.clear();
        endPickaxeHold();
    }

    @Override
    public void onTick() {
        if (MC.player == null || MC.level == null) return;

        boolean attackDown = MC.options.keyAttack.isDown();

        // start a fresh batch when idle: either you just clicked, or (with InstantRebreak)
        // you're still holding the button and nothing is queued/mining right now
        if (current == null && queue.isEmpty() && attackDown
                && MC.hitResult instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK) {

            BlockPos primary = blockHit.getBlockPos();
            Direction face = blockHit.getDirection();

            int wanted = switch (mode.get()) {
                case Single -> 1;
                case Double -> 2;
                case Quadruple -> 4;
            };

            List<BlockPos> pattern = buildPattern(primary, face, wanted);
            for (BlockPos p : pattern) {
                if (!MC.level.getBlockState(p).isAir()) {
                    queue.add(p);
                }
            }

            if (!queue.isEmpty()) {
                beginPickaxeHold();
                startNext(face);
            }
        }

        if (current != null) {
            BlockState state = MC.level.getBlockState(current.pos);

            if (state.isAir()
                    || MC.player.position().distanceTo(Vec3.atCenterOf(current.pos)) > 6.0) {
                current = null;
            } else {
                long elapsed = System.currentTimeMillis() - current.startMs;
                if (elapsed >= current.requiredMs) {
                    send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, current.pos, current.face);
                    swing();
                    Direction lastFace = current.face;
                    current = null;

                    if (!queue.isEmpty()) {
                        startNext(lastFace);
                    }
                }
            }
        }

        // once fully idle: only keep chaining automatically if InstantRebreak is on
        // and the button is still held, otherwise release the pickaxe hold
        if (current == null && queue.isEmpty()) {
            if (!(instantRebreak.get() && attackDown)) {
                endPickaxeHold();
            }
        }

        queuedOutline.clear();
        queuedOutline.addAll(queue);
    }

    private void startNext(Direction face) {
        BlockPos pos = queue.poll();
        if (pos == null) return;

        BlockState state = MC.level.getBlockState(pos);
        if (state.isAir()) {
            if (!queue.isEmpty()) startNext(face);
            return;
        }

        Target t = new Target();
        t.pos = pos;
        t.face = face;
        t.startMs = System.currentTimeMillis();
        t.requiredMs = breakTimeMs(state, pos);
        current = t;

        send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, t.pos, t.face);
        swing();
    }

    /**
     * Real vanilla break time (hardness * 30 ticks / tool speed, correct-tool assumed),
     * scaled down by Threshold — the server's own completion check only requires
     * elapsed-time-based progress to reach roughly that fraction, not a full 100%,
     * so stopping early still gets accepted as a completed break.
     */
    private long breakTimeMs(BlockState state, BlockPos pos) {
        float hardness = state.getDestroySpeed(MC.level, pos);
        if (hardness < 0) return Long.MAX_VALUE;
        if (hardness == 0) return 50L;

        float toolSpeed = 1.0f;
        if (autoSwitch.get()) {
            int slot = findBestPickaxe();
            if (slot >= 0) {
                ItemStack stack = MC.player.getInventory().getItem(slot);
                toolSpeed = stack.getDestroySpeed(state);
                if (toolSpeed <= 0) toolSpeed = 1.0f;
            }
        }

        float fraction = threshold.getFloat();
        int ticks = Math.max(1, (int) Math.ceil((hardness * 30f * fraction) / toolSpeed));
        return Math.max(50L, ticks * 50L);
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (MC.level == null) return;

        Color base = boxColor.get();

        for (BlockPos pos : queuedOutline) {
            AABB box = new AABB(pos);
            RenderUtil.drawBoxOutline(event.getMatrix(), box, base, 1.0f);
        }

        if (current != null) {
            AABB fullBox = new AABB(current.pos);
            RenderUtil.drawBoxOutline(event.getMatrix(), fullBox, base, 1.5f);

            long elapsed = System.currentTimeMillis() - current.startMs;
            float progress = Math.max(0f, Math.min(1f, (float) elapsed / current.requiredMs));

            double cx = current.pos.getX() + 0.5;
            double cy = current.pos.getY() + 0.5;
            double cz = current.pos.getZ() + 0.5;
            double half = 0.5 * progress;
            AABB growingBox = new AABB(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);

            RenderUtil.drawBoxFilled(event.getMatrix(), growingBox, base);
        }
    }

    private void beginPickaxeHold() {
        if (!autoSwitch.get() || originalSlot != -1) return;

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
            float speed = stack.getDestroySpeed(Blocks.STONE.defaultBlockState());
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    private List<BlockPos> buildPattern(BlockPos primary, Direction face, int count) {
        List<BlockPos> result = new ArrayList<>();
        result.add(primary);
        if (count == 1) return result;

        Direction.Axis u;
        Direction.Axis v;
        switch (face.getAxis()) {
            case Y -> { u = Direction.Axis.X; v = Direction.Axis.Z; }
            case X -> { u = Direction.Axis.Y; v = Direction.Axis.Z; }
            default -> { u = Direction.Axis.X; v = Direction.Axis.Y; }
        }

        BlockPos next = offset(primary, u, 1);
        result.add(next);

        if (count == 4) {
            result.add(offset(primary, v, 1));
            result.add(offset(next, v, 1));
        }

        return result;
    }

    private BlockPos offset(BlockPos pos, Direction.Axis axis, int amount) {
        return switch (axis) {
            case X -> pos.offset(amount, 0, 0);
            case Y -> pos.offset(0, amount, 0);
            case Z -> pos.offset(0, 0, amount);
        };
    }

    private void send(ServerboundPlayerActionPacket.Action action, BlockPos target, Direction face) {
        MC.player.connection.send(new ServerboundPlayerActionPacket(action, target, face));
    }

    private void swing() {
        MC.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }
}
