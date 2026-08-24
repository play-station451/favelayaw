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
        int cooldown;
    }

    private final List<Target> active = new ArrayList<>();

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
    }

    @Override
    public void onTick() {
        if (MC.player == null || MC.level == null) return;

        for (Target t : active) {
            if (t.cooldown > 0) t.cooldown--;
        }

        boolean holding = MC.options.keyAttack.isDown();
        BlockHitResult blockHit = (holding && MC.hitResult instanceof BlockHitResult bh
                && bh.getType() == HitResult.Type.BLOCK) ? bh : null;

        if (blockHit == null) {
            for (Target t : active) {
                send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, t.pos, t.face);
            }
            active.clear();
            return;
        }

        BlockPos primary = blockHit.getBlockPos();
        Direction face = blockHit.getDirection();

        int wanted = switch (mode.get()) {
            case Single -> 1;
            case Double -> 2;
            case Quadruple -> 4;
        };

        List<BlockPos> desired = buildPattern(primary, face, wanted);

        active.removeIf(t -> {
            if (desired.contains(t.pos)) return false;
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, t.pos, t.face);
            return true;
        });

        for (BlockPos p : desired) {
            if (containsPos(p)) continue;
            if (active.size() >= wanted) break;

            BlockState state = MC.level.getBlockState(p);
            if (state.isAir()) continue;

            Target t = new Target();
            t.pos = p.immutable();
            t.face = face;
            t.startMs = System.currentTimeMillis();
            active.add(t);

            withPickaxe(() -> {
                send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, t.pos, t.face);
                swing();
            });
        }

        for (int i = active.size() - 1; i >= 0; i--) {
            Target t = active.get(i);
            if (t.cooldown > 0) continue;

            BlockState state = MC.level.getBlockState(t.pos);
            if (state.isAir()) {
                active.remove(i);
                continue;
            }

            float hardness = state.getDestroySpeed(MC.level, t.pos);
            if (hardness < 0) {
                active.remove(i);
                continue;
            }

            long requiredMs = Math.max(50L, (long) (hardness * 1000.0));
            if (System.currentTimeMillis() - t.startMs < requiredMs) continue;

            withPickaxe(() -> {
                send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, t.pos, t.face);
                swing();
            });

            if (instantRebreak.get()) {
                active.remove(i);
            } else {
                t.cooldown = 2;
                t.startMs = System.currentTimeMillis() + 100000L;
            }
        }
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

    private void withPickaxe(Runnable action) {
        if (!autoSwitch.get()) {
            action.run();
            return;
        }

        int original = MC.player.getInventory().getSelectedSlot();
        int pickaxeSlot = findBestPickaxe();

        if (pickaxeSlot < 0 || pickaxeSlot == original) {
            action.run();
            return;
        }

        MC.player.connection.send(new ServerboundSetCarriedItemPacket(pickaxeSlot));
        action.run();
        MC.player.connection.send(new ServerboundSetCarriedItemPacket(original));
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

    private boolean containsPos(BlockPos pos) {
        for (Target t : active) {
            if (t.pos.equals(pos)) return true;
        }
        return false;
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