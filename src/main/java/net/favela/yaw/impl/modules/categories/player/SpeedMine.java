package net.favela.yaw.impl.modules.categories.player;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.event.events.Render3DEvent;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.BooleanSetting;
import net.favela.yaw.impl.setting.settings.ColorSetting;
import net.favela.yaw.impl.setting.settings.EnumSetting;
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

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class SpeedMine extends Module {

    public enum Mode { Single, Double, Quadruple }

    public static SpeedMine INSTANCE;

    public final EnumSetting<Mode> mode = enm("Mode", Mode.Double);
    public final BooleanSetting instantRebreak = register(new BooleanSetting("InstantRebreak", "Immediately starts the next block with no delay", true));
    public final BooleanSetting autoSwitch = register(new BooleanSetting("AutoSwitch", "Silently switches to the best pickaxe while mining", true));
    public final ColorSetting boxColor = color("Color", new Color(167, 123, 234, 120), true);

    private static class Target {
        BlockPos pos;
        Direction face;
        long startMs;
        long requiredMs;
        int cooldown;
    }

    private final List<Target> active = new ArrayList<>();
    private boolean wasAttackDown;
    private int originalSlot = -1;

    public SpeedMine() {
        super("SpeedMine", "Breaks multiple adjacent blocks at once", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onDisable() {
        for (Target t : active) {
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, t.pos, t.face);
        }
        active.clear();
        wasAttackDown = false;
        endPickaxeHold();
    }

    @Override
    public void onTick() {
        if (MC.player == null || MC.level == null) return;

        for (Target t : active) {
            if (t.cooldown > 0) t.cooldown--;
        }

        // edge-triggered: only START a new set of targets the instant the button goes down,
        // never require it to stay held for mining to continue
        boolean attackDown = MC.options.keyAttack.isDown();
        boolean justClicked = attackDown && !wasAttackDown;
        wasAttackDown = attackDown;

        if (justClicked && active.isEmpty()
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
            boolean any = false;
            for (BlockPos p : pattern) {
                if (!MC.level.getBlockState(p).isAir()) {
                    any = true;
                    break;
                }
            }

            if (any) {
                beginPickaxeHold();

                for (BlockPos p : pattern) {
                    BlockState state = MC.level.getBlockState(p);
                    if (state.isAir()) continue;

                    Target t = new Target();
                    t.pos = p.immutable();
                    t.face = face;
                    t.startMs = System.currentTimeMillis();
                    t.requiredMs = breakTimeMs(state, t.pos);
                    active.add(t);

                    send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, t.pos, t.face);
                    swing();
                }
            }
        }

        // out-of-range safety: abort anything the player has moved too far from
        active.removeIf(t -> {
            double dist = MC.player.position().distanceTo(
                    net.minecraft.world.phys.Vec3.atCenterOf(t.pos));
            if (dist <= 6.0) return false;
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, t.pos, t.face);
            return true;
        });

        for (int i = active.size() - 1; i >= 0; i--) {
            Target t = active.get(i);
            if (t.cooldown > 0) continue;

            BlockState state = MC.level.getBlockState(t.pos);
            if (state.isAir()) {
                active.remove(i);
                continue;
            }

            if (System.currentTimeMillis() - t.startMs < t.requiredMs) continue;

            send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, t.pos, t.face);
            swing();

            if (instantRebreak.get()) {
                active.remove(i);
            } else {
                t.cooldown = 2;
                t.startMs = System.currentTimeMillis() + 100000L;
            }
        }

        if (active.isEmpty()) {
            endPickaxeHold();
        }
    }

    /**
     * Approximates vanilla's real break-time formula: hardness * 30 ticks / tool speed,
     * assuming the correct tool (we auto-switch to the best pickaxe) and standing on ground.
     * This is what makes the timing fast AND accurate enough for the server to accept the break.
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

        int ticks = Math.max(1, (int) Math.ceil((hardness * 30f) / toolSpeed));
        return Math.max(50L, ticks * 50L);
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (MC.level == null || active.isEmpty()) return;

        Color base = boxColor.get();
        for (Target t : active) {
            AABB box = new AABB(t.pos);
            RenderUtil.drawBoxFilled(event.getMatrix(), box, base);
            RenderUtil.drawBoxOutline(event.getMatrix(), box, base, 1.0f);
        }
    }

    /**
     * Switches to the best pickaxe ONCE at the start of a mine and keeps it selected
     * for the whole duration — the server tracks your held item continuously while
     * digging, so swapping back mid-mine makes it think you never had a tool equipped.
     */
    private void beginPickaxeHold() {
        if (!autoSwitch.get() || originalSlot != -1) return;

        int current = MC.player.getInventory().getSelectedSlot();
        int pickaxeSlot = findBestPickaxe();

        if (pickaxeSlot < 0 || pickaxeSlot == current) return;

        originalSlot = current;
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
