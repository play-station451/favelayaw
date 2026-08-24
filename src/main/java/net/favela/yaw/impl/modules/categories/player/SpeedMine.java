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
import net.minecraft.util.Mth;
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

/**
 * Ported from Homovore's SpeedMineModule (dev.leonetic, github.com/leonetics/homovore-public).
 * The rate-limit balance (canBegin/trackStarts), demote-based chaining, decoy, rebreak, and
 * break-ahead logic are carried over from that design. SwapManager's per-action silent swap
 * is replaced with a sustained pickaxe hold since favelayaw has no equivalent swap manager.
 */
@AutoService(Module.class)
public class SpeedMine extends Module {

    public enum Mode { Single, Double, Quadruple }

    public static SpeedMine INSTANCE;

    private static final double GRIM_MIN_EYE = 0.4;
    private static final double GRIM_MAX_EYE = 1.62;
    private static final double BREAK_AHEAD_EDGE = 0.05;
    private static final int SECONDARY_TIMEOUT = 10;

    public final EnumSetting<Mode> mode = enm("Mode", Mode.Double);
    public final NumberSetting threshold = num("Threshold", 0.5f, 1.0f, 0.7f);
    public final NumberSetting range = num("Range", 1.0f, 7.0f, 5.0f);
    public final BooleanSetting decoy = register(new BooleanSetting("GrimDecoy", "Sends a decoy dig packet far below you alongside the real one", true));
    public final BooleanSetting breakAhead = register(new BooleanSetting("BreakAhead", "Also mines straight through into the block behind, if your Mode allows it", false));
    public final BooleanSetting rebreak = register(new BooleanSetting("Rebreak", "Keeps re-mining the same spot after it breaks, for gravel/sand tunnels", true));
    public final BooleanSetting instantRebreak = register(new BooleanSetting("InstantRebreak", "Automatically continues mining new blocks while held, with no delay", true));
    public final BooleanSetting autoSwitch = register(new BooleanSetting("AutoSwitch", "Silently switches to the best pickaxe while mining", true));
    public final ColorSetting primaryColor = color("PrimaryColor", new Color(167, 123, 234, 60), true);
    public final ColorSetting sideColor = color("SideColor", new Color(255, 255, 255, 40), true);
    public final ColorSetting lineColor = color("LineColor", new Color(255, 255, 255, 150), true);

    private static class Target {
        BlockPos pos;
        Direction face;
        long startMs;
        long requiredMs;
    }

    private Target primary;
    private final Deque<Target> secondaries = new ArrayDeque<>();

    private long lastStopMs;
    private double delayBalance;
    private int originalSlot = -1;

    public SpeedMine() {
        super("SpeedMine", "Ported from Homovore: breaks blocks fast via demote-chained digging", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onDisable() {
        if (primary != null) {
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, primary.pos, primary.face);
        }
        primary = null;
        secondaries.clear();
        endPickaxeHold();
    }

    @Override
    public void onTick() {
        if (MC.player == null || MC.level == null) return;

        boolean attackDown = MC.options.keyAttack.isDown();

        if (primary == null && attackDown
                && MC.hitResult instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK) {
            tryStart(blockHit.getBlockPos(), blockHit.getDirection());
        }

        tickPrimary();
        tickSecondaries();

        if (primary == null && secondaries.isEmpty() && !(instantRebreak.get() && attackDown)) {
            endPickaxeHold();
        }
    }

    private void tryStart(BlockPos pos, Direction face) {
        if (!inRange(pos)) return;
        BlockState state = MC.level.getBlockState(pos);
        if (state.isAir()) return;

        int wanted = switch (mode.get()) {
            case Single -> 1;
            case Double -> 2;
            case Quadruple -> 4;
        };

        if (primary != null) {
            if (wanted <= 1 || secondaries.size() >= wanted - 1) return;
            demote();
        }

        beginPickaxeHold();
        beginPrimary(pos, face);

        if (breakAhead.get() && wanted > 1 && secondaries.size() < wanted - 1) {
            BlockPos ahead = breakAheadPos(pos, face);
            if (ahead != null) {
                Target t = buildTarget(ahead, face);
                if (t != null) secondaries.add(t);
            }
        }
    }

    /** Ported from Homovore: throttles restart rate so rapid START/STOP cycling doesn't look inhuman. */
    private boolean canBegin() {
        long delay = System.currentTimeMillis() - lastStopMs;
        if (delay >= 275) return true;
        double cost = (300 - delay) * (decoy.get() ? 2 : 1);
        return delayBalance + cost <= 900;
    }

    private void trackStart() {
        long delay = System.currentTimeMillis() - lastStopMs;
        if (delay >= 275) delayBalance *= 0.9;
        else delayBalance += 300 - delay;
        delayBalance = Mth.clamp(delayBalance, -1000, 1000);
    }

    private void beginPrimary(BlockPos pos, Direction face) {
        if (!canBegin()) return;

        BlockState state = MC.level.getBlockState(pos);
        Target t = buildTarget(pos, face);
        if (t == null) return;

        trackStart();
        primary = t;

        send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, t.pos, t.face);
        swing();

        if (decoy.get()) {
            BlockPos decoyPos = pos.below(2000);
            send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, decoyPos, Direction.DOWN);
        }
    }

    /** Ported from Homovore's demote(): hands the current primary off into the secondary queue
     *  instead of aborting it, so a newly clicked block can become the primary without losing
     *  progress tracking on the old one. */
    private void demote() {
        if (primary == null) return;
        secondaries.addLast(primary);
        primary = null;
    }

    private void tickPrimary() {
        if (primary == null) return;

        BlockState state = MC.level.getBlockState(primary.pos);
        if (state.isAir() || !inRange(primary.pos)) {
            primary = null;
            return;
        }

        if (System.currentTimeMillis() - primary.startMs < primary.requiredMs) return;

        stop(primary);

        if (rebreak.get()) {
            // re-arm on the same spot in case a new block fell/appeared there
            Target next = buildTarget(primary.pos, primary.face);
            primary = next;
        } else {
            primary = null;
        }
    }

    private void tickSecondaries() {
        secondaries.removeIf(t -> {
            BlockState state = MC.level.getBlockState(t.pos);
            if (state.isAir() || !inRange(t.pos)) return true;

            if (System.currentTimeMillis() - t.startMs >= t.requiredMs + (SECONDARY_TIMEOUT * 50L)) {
                return true;
            }
            if (System.currentTimeMillis() - t.startMs >= t.requiredMs) {
                stop(t);
                return true;
            }
            return false;
        });

        // promote the oldest secondary to primary once the slot frees up
        if (primary == null && !secondaries.isEmpty()) {
            primary = secondaries.pollFirst();
        }
    }

    private void stop(Target t) {
        send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, t.pos, t.face);
        swing();
        lastStopMs = System.currentTimeMillis();
    }

    private Target buildTarget(BlockPos pos, Direction face) {
        BlockState state = MC.level.getBlockState(pos);
        if (state.isAir()) return null;

        Target t = new Target();
        t.pos = pos.immutable();
        t.face = face;
        t.startMs = System.currentTimeMillis();
        t.requiredMs = breakTimeMs(state, pos);
        return t;
    }

    /**
     * Ported from Homovore's breakAheadPos(): traces the look ray through the clicked block
     * and returns the block it exits into, or null if it leaves through an edge/corner rather
     * than cleanly through a face.
     */
    private BlockPos breakAheadPos(BlockPos target, Direction clickedFace) {
        Vec3 eye = MC.player.getEyePosition();
        Vec3 dir = MC.player.getLookAngle();
        AABB box = new AABB(target);

        double tEnter = Double.NEGATIVE_INFINITY;
        double tExit = Double.POSITIVE_INFINITY;
        Direction exitFace = null;

        for (Direction.Axis axis : Direction.Axis.values()) {
            double d = axis.choose(dir.x, dir.y, dir.z);
            double o = axis.choose(eye.x, eye.y, eye.z);
            double min = axis.choose(box.minX, box.minY, box.minZ);
            double max = axis.choose(box.maxX, box.maxY, box.maxZ);
            if (Math.abs(d) < 1.0E-7) {
                if (o < min || o > max) return null;
                continue;
            }
            double t1 = (min - o) / d;
            double t2 = (max - o) / d;
            double near = Math.min(t1, t2);
            double far = Math.max(t1, t2);
            if (near > tEnter) tEnter = near;
            if (far < tExit) {
                tExit = far;
                exitFace = Direction.fromAxisAndDirection(axis,
                        d > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
            }
        }
        if (exitFace == null || tEnter > tExit || tExit <= 0) return null;

        Vec3 exit = eye.add(dir.scale(tExit));
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis == exitFace.getAxis()) continue;
            double p = axis.choose(exit.x, exit.y, exit.z) - axis.choose(target.getX(), target.getY(), target.getZ());
            if (p < BREAK_AHEAD_EDGE || p > 1 - BREAK_AHEAD_EDGE) return null;
        }

        BlockPos ahead = target.relative(exitFace);
        if (!inRange(ahead)) return null;
        BlockState state = MC.level.getBlockState(ahead);
        if (state.isAir()) return null;
        return ahead;
    }

    private boolean inRange(BlockPos pos) {
        return MC.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= range.getFloat();
    }

    /**
     * Real vanilla break time (hardness * 30 ticks / tool speed, correct-tool assumed),
     * scaled by Threshold to exploit the server's lenient elapsed-time completion check.
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

        if (primary != null) {
            drawTarget(event, primary, primaryColor.get());
        }
        for (Target t : secondaries) {
            drawTarget(event, t, sideColor.get());
        }
    }

    private void drawTarget(Render3DEvent event, Target t, Color base) {
        AABB fullBox = new AABB(t.pos);
        RenderUtil.drawBoxOutline(event.getMatrix(), fullBox, lineColor.get(), 1.5f);

        long elapsed = System.currentTimeMillis() - t.startMs;
        float progress = Math.max(0f, Math.min(1f, (float) elapsed / t.requiredMs));

        double cx = t.pos.getX() + 0.5;
        double cy = t.pos.getY() + 0.5;
        double cz = t.pos.getZ() + 0.5;
        double half = 0.5 * progress;
        AABB growingBox = new AABB(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);

        RenderUtil.drawBoxFilled(event.getMatrix(), growingBox, base);
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

    private void send(ServerboundPlayerActionPacket.Action action, BlockPos target, Direction face) {
        MC.player.connection.send(new ServerboundPlayerActionPacket(action, target, face));
    }

    private void swing() {
        MC.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }
}
