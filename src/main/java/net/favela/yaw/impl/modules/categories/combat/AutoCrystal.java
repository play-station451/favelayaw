package net.favela.yaw.impl.modules.categories.combat;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.BooleanSetting;
import net.favela.yaw.impl.setting.settings.EnumSetting;
import net.favela.yaw.impl.setting.settings.NumberSetting;
import net.favela.yaw.impl.util.wrapper.Wrapper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.EndCrystalItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class AutoCrystal extends Module {

    public final EnumSetting<Swap> swapMode = enm("Swap", Swap.OFF);
    public final EnumSetting<Placements> placements = enm("Placements", Placements.NATIVE);
    public final NumberSetting placeRange = num("PlaceRange", 0.1f, 6f, 4.5f);
    public final NumberSetting placeWallRange = num("PlaceWallRange", 0.1f, 6f, 4.5f);
    public final NumberSetting breakRange = num("BreakRange", 0.1f, 6f, 4.5f);
    public final NumberSetting breakWallRange = num("BreakWallRange", 0.1f, 6f, 4.5f);
    public final NumberSetting minDamage = num("MinDamage", 0.1f, 10f, 4f);
    public final NumberSetting maxSelfDamage = num("MaxSelfDamage", 0.1f, 20f, 10f);
    public final BooleanSetting safety = bool("Safety", true);
    public final BooleanSetting rotate = bool("Rotate", true);
    public final BooleanSetting render = bool("Render", true);
    public final NumberSetting fadeTime = num("FadeTime", 0, 1000, 250);
    public final BooleanSetting breakThroughWalls = bool("BreakThroughWalls", true);
    public final BooleanSetting placeThroughWalls = bool("PlaceThroughWalls", true);
    public final BooleanSetting swing = bool("Swing", true);

    public enum Swap { NORMAL, SILENT, SILENT_ALT, OFF }
    public enum Placements { NATIVE, PROTOCOL }

    private BlockPos renderPos;
    private double renderDamage;
    private EndCrystalEntity attackTarget;
    private BlockPos placeTarget;
    private Vec3d rotationTarget;
    private long lastAttackTime, lastPlaceTime, lastSwapTime;
    private final Map<Integer, Long> attackPackets = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> placePackets = new ConcurrentHashMap<>();
    private final Deque<Long> attackLatency = new EvictingQueue<>(20);
    private final Map<BlockPos, Animation> fadeList = new HashMap<>();
    private final PerSecondCounter crystalCounter = new PerSecondCounter();
    private final Timer attackTimer = new Timer();
    private final Timer placeTimer = new Timer();

    public AutoCrystal() {
        super("AutoCrystal", "Attacks entities with end crystals", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        super.onEnable();
        reset();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        reset();
        fadeList.clear();
        attackPackets.clear();
        placePackets.clear();
        attackLatency.clear();
        renderPos = null;
    }

    private void reset() {
        attackTarget = null;
        placeTarget = null;
        rotationTarget = null;
        renderPos = null;
        renderDamage = 0;
    }

    @Override
    public void onTick() {
        if (MC.player == null || MC.level == null || MC.gameMode == null) return;
        if (MC.player.isSpectator()) return;

        attackTarget = findBestCrystalToBreak();
        placeTarget = findBestBlockToPlace();

        if (rotate.get() && rotationTarget != null) {
            float[] rotations = getRotationsTo(MC.player.getEyePos(), rotationTarget);
            MC.player.setYaw(rotations[0]);
            MC.player.setPitch(rotations[1]);
        }

        if (attackTarget != null && attackTimer.passed(200)) {
            attackCrystal(attackTarget);
            attackTimer.reset();
            attackPackets.put(attackTarget.getId(), System.currentTimeMillis());
            crystalCounter.updateCounter();
        }

        if (placeTarget != null && placeTimer.passed(200)) {
            placeCrystal(placeTarget);
            placeTimer.reset();
            placePackets.put(placeTarget, System.currentTimeMillis());
            renderPos = placeTarget;
            renderDamage = getBestDamageAt(placeTarget);
            fadeList.put(placeTarget, new Animation(true, fadeTime.get()));
        }
    }

    private EndCrystalEntity findBestCrystalToBreak() {
        EndCrystalEntity best = null;
        double bestDamage = -1;
        double range = breakRange.get();
        double wallRange = breakWallRange.get();

        for (Entity e : MC.level.getEntities()) {
            if (!(e instanceof EndCrystalEntity crystal) || !e.isAlive()) continue;
            if (attackPackets.containsKey(crystal.getId())) continue;

            Vec3d pos = crystal.getPos();
            double dist = MC.player.distanceTo(e);
            if (dist > range && dist > wallRange) continue;
            if (!breakThroughWalls.get() && !isVisible(pos)) continue;
            if (dist > wallRange && !isVisible(pos)) continue;

            double selfDmg = getExplosionDamage(MC.player, pos);
            if (safety.get() && selfDmg > maxSelfDamage.get()) continue;

            for (Entity target : MC.level.getEntities()) {
                if (!isValidTarget(target)) continue;
                double dmg = getExplosionDamage(target, pos);
                if (dmg > bestDamage && dmg >= minDamage.get()) {
                    bestDamage = dmg;
                    best = crystal;
                    rotationTarget = pos;
                }
            }
        }
        return best;
    }

    private BlockPos findBestBlockToPlace() {
        BlockPos best = null;
        double bestDamage = -1;
        double range = placeRange.get();
        double wallRange = placeWallRange.get();

        for (BlockPos pos : getCrystalBlocks()) {
            if (!canPlaceCrystalOn(pos)) continue;
            Vec3d crystalPos = pos.toCenterPos().add(0, 1, 0);
            double dist = MC.player.distanceTo(crystalPos);
            if (dist > range && dist > wallRange) continue;
            if (!placeThroughWalls.get() && !isVisible(crystalPos)) continue;
            if (dist > wallRange && !isVisible(crystalPos)) continue;

            double selfDmg = getExplosionDamage(MC.player, crystalPos);
            if (safety.get() && selfDmg > maxSelfDamage.get()) continue;

            for (Entity target : MC.level.getEntities()) {
                if (!isValidTarget(target)) continue;
                double dmg = getExplosionDamage(target, crystalPos);
                if (dmg > bestDamage && dmg >= minDamage.get()) {
                    bestDamage = dmg;
                    best = pos;
                    rotationTarget = crystalPos;
                }
            }
        }
        return best;
    }

    private void attackCrystal(EndCrystalEntity crystal) {
        Hand hand = getCrystalHand();
        if (hand == null) hand = Hand.MAIN_HAND;
        MC.gameMode.attack(MC.player, crystal);
        if (swing.get()) MC.player.swingHand(hand);
        else MC.player.networkHandler.sendPacket(new HandSwingC2SPacket(hand));
    }

    private void placeCrystal(BlockPos pos) {
        Hand hand = getCrystalHand();
        if (hand == null) {
            int slot = getCrystalSlot();
            if (slot == -1) return;
            if (swapMode.get() != Swap.OFF) {
                if (swapMode.get() == Swap.SILENT_ALT) {
                    MC.interactionManager.clickSlot(MC.player.playerScreenHandler.syncId,
                            slot + 36, MC.player.getInventory().selectedSlot, SlotActionType.SWAP, MC.player);
                } else if (swapMode.get() == Swap.SILENT) {
                    MC.player.getInventory().selectedSlot = slot;
                } else {
                    MC.player.getInventory().selectedSlot = slot;
                }
                lastSwapTime = System.currentTimeMillis();
            } else {
                return;
            }
        }
        Direction side = getPlaceDirection(pos);
        BlockHitResult result = new BlockHitResult(pos.toCenterPos(), side, pos, false);
        MC.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, result));
        if (swing.get()) MC.player.swingHand(Hand.MAIN_HAND);
        else MC.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    private Hand getCrystalHand() {
        if (MC.player.getOffHandStack().getItem() instanceof EndCrystalItem) return Hand.OFF_HAND;
        if (MC.player.getMainHandStack().getItem() instanceof EndCrystalItem) return Hand.MAIN_HAND;
        return null;
    }

    private int getCrystalSlot() {
        for (int i = 0; i < 9; i++) {
            if (MC.player.getInventory().getStack(i).getItem() instanceof EndCrystalItem) return i;
        }
        return -1;
    }

    private boolean canPlaceCrystalOn(BlockPos pos) {
        BlockState state = MC.level.getBlockState(pos);
        if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) return false;
        BlockPos up = pos.up();
        if (placements.get() == Placements.PROTOCOL && !MC.level.isAir(up.up())) return false;
        if (!MC.level.isAir(up) && !MC.level.getBlockState(up).isOf(Blocks.FIRE)) return false;
        Box bb = new Box(up.getX(), up.getY(), up.getZ(), up.getX()+1, up.getY()+2, up.getZ()+1);
        return MC.level.getEntities(null, bb).stream().noneMatch(e -> e instanceof EndCrystalEntity || !(e instanceof LivingEntity));
    }

    private List<BlockPos> getCrystalBlocks() {
        List<BlockPos> list = new ArrayList<>();
        double r = Math.ceil(placeRange.get());
        Vec3d origin = MC.player.getPos();
        for (int x = (int)-r; x <= r; x++) {
            for (int y = (int)-r; y <= r; y++) {
                for (int z = (int)-r; z <= r; z++) {
                    list.add(new BlockPos((int)origin.x + x, (int)origin.y + y, (int)origin.z + z));
                }
            }
        }
        return list;
    }

    private Direction getPlaceDirection(BlockPos pos) {
        return Direction.UP;
    }

    private boolean isVisible(Vec3d target) {
        BlockHitResult result = MC.level.raycast(new RaycastContext(
                MC.player.getEyePos(), target,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, MC.player));
        return result.getType() == HitResult.Type.MISS || result.getPos().squaredDistanceTo(target) < 0.1;
    }

    private boolean isValidTarget(Entity e) {
        return e instanceof PlayerEntity || e instanceof LivingEntity;
    }

    private double getBestDamageAt(BlockPos pos) {
        Vec3d crystalPos = pos.toCenterPos().add(0, 1, 0);
        double best = 0;
        for (Entity e : MC.level.getEntities()) {
            if (isValidTarget(e)) {
                double dmg = getExplosionDamage(e, crystalPos);
                if (dmg > best) best = dmg;
            }
        }
        return best;
    }

    private double getExplosionDamage(Entity entity, Vec3d explosionPos) {
        return getExplosionDamage(entity, explosionPos, false, false);
    }

    private double getExplosionDamage(Entity entity, Vec3d explosionPos, boolean ignoreTerrain, boolean assumeBestArmor) {
        double exposure = getExposure(explosionPos, entity.getBoundingBox(), ignoreTerrain);
        double distance = entity.getPos().distanceTo(explosionPos);
        double power = 12.0f;
        double w = distance / power;
        double ac = (1.0 - w) * exposure;
        double rawDamage = (float) ((int) ((ac * ac + ac) / 2.0 * 7.0 * 12.0 + 1.0));
        return applyArmorReduction(entity, rawDamage, assumeBestArmor);
    }

    private float getExposure(Vec3d source, Box box, boolean ignoreTerrain) {
        double xStep = 1.0 / (box.maxX - box.minX + 1);
        double yStep = 1.0 / (box.maxY - box.minY + 1);
        double zStep = 1.0 / (box.maxZ - box.minZ + 1);
        int hits = 0, misses = 0;
        for (double x = box.minX; x <= box.maxX; x += xStep) {
            for (double y = box.minY; y <= box.maxY; y += yStep) {
                for (double z = box.minZ; z <= box.maxZ; z += zStep) {
                    Vec3d pos = new Vec3d(x, y, z);
                    if (raycast(pos, source, ignoreTerrain)) misses++;
                    hits++;
                }
            }
        }
        return (float) misses / hits;
    }

    private boolean raycast(Vec3d start, Vec3d end, boolean ignoreTerrain) {
        BlockHitResult result = MC.level.raycast(new RaycastContext(
                start, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, MC.player));
        if (ignoreTerrain) return false;
        return result.getType() != HitResult.Type.MISS;
    }

    private double applyArmorReduction(Entity entity, double damage, boolean assumeBestArmor) {
        if (!(entity instanceof LivingEntity living)) return damage;
        float armor = (float) living.getAttributeValue(EntityAttributes.GENERIC_ARMOR);
        float toughness = (float) living.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
        damage = DamageUtil.getDamageLeft(living, (float) damage, MC.world.getDamageSources().explosion(null), armor, toughness);
        if (living.hasStatusEffect(StatusEffects.RESISTANCE)) {
            int amp = living.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1;
            damage *= (1.0 - amp * 0.2);
        }
        float prot = 0;
        for (ItemStack stack : living.getArmorItems()) {
            if (assumeBestArmor) {
                prot += 4;
            } else {
                prot += EnchantmentHelper.getLevel(Enchantments.PROTECTION, stack);
                prot += EnchantmentHelper.getLevel(Enchantments.BLAST_PROTECTION, stack) * 2;
            }
        }
        damage = DamageUtil.getInflictedDamage((float) damage, prot);
        return Math.max(0, damage);
    }

    private float[] getRotationsTo(Vec3d from, Vec3d to) {
        Vec3d diff = to.subtract(from);
        double yaw = Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90;
        double pitch = -Math.toDegrees(Math.atan2(diff.y, Math.sqrt(diff.x*diff.x + diff.z*diff.z)));
        return new float[] { MathHelper.wrapDegrees((float)yaw), MathHelper.wrapDegrees((float)pitch) };
    }

    public void onRenderWorld(net.minecraft.client.util.math.MatrixStack matrices) {
        if (!render.get() || renderPos == null) return;
        renderBox(matrices, renderPos, new Color(255, 0, 0, 80).getRGB());
        renderBoundingBox(matrices, renderPos, 1.5f, 0xFFFF0000);
        if (renderDamage > 0) {
            Vec3d pos = renderPos.toCenterPos().add(0, 0.5, 0);
        }
        for (Map.Entry<BlockPos, Animation> entry : fadeList.entrySet()) {
            if (entry.getKey().equals(renderPos)) continue;
            Animation anim = entry.getValue();
            anim.setState(false);
            float factor = (float) anim.getFactor();
            if (factor > 0.01f) {
                int alpha = (int) (80 * factor);
                int boxColor = new Color(255, 0, 0, alpha).getRGB();
                int lineColor = new Color(255, 255, 255, (int)(255*factor)).getRGB();
                renderBox(matrices, entry.getKey(), boxColor);
                renderBoundingBox(matrices, entry.getKey(), 1.0f, lineColor);
            }
        }
        fadeList.entrySet().removeIf(e -> e.getValue().getFactor() <= 0.01);
    }

    private void renderBox(MatrixStack matrices, BlockPos pos, int color) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        float r = ((color>>16)&0xFF)/255f, g = ((color>>8)&0xFF)/255f, b = (color&0xFF)/255f, a = ((color>>24)&0xFF)/255f;
        renderBoundingBox(matrices, pos, 1.0f, color);
    }

    private void renderBoundingBox(MatrixStack matrices, BlockPos pos, float width, int color) {
    }

    public void onPacketInbound(Object packet) {
        if (packet instanceof EntitySpawnS2CPacket spawn) {
        }
        if (packet instanceof ExplosionS2CPacket exp) {
            for (Entity e : MC.level.getEntities()) {
                if (e instanceof EndCrystalEntity && e.squaredDistanceTo(exp.getX(), exp.getY(), exp.getZ()) < 144) {
                    MC.level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
                    attackPackets.remove(e.getId());
                }
            }
        }
        if (packet instanceof EntitiesDestroyS2CPacket destroy) {
            for (int id : destroy.getEntityIds()) attackPackets.remove(id);
        }
    }

    public void onPacketOutbound(Object packet) {
        if (packet instanceof UpdateSelectedSlotC2SPacket) {
            lastSwapTime = System.currentTimeMillis();
        }
    }

    private static class EvictingQueue<E> extends ConcurrentLinkedDeque<E> {
        private final int limit;
        public EvictingQueue(int limit) { this.limit = limit; }
        @Override public boolean add(E e) { boolean b = super.add(e); while (size() > limit) remove(); return b; }
    }

    private static class PerSecondCounter {
        private final Deque<Long> times = new ConcurrentLinkedDeque<>();
        public void updateCounter() { times.add(System.currentTimeMillis() + 1000); }
        public int getPerSecond() {
            long now = System.currentTimeMillis();
            times.removeIf(t -> t < now);
            return times.size();
        }
    }

    private static class Timer {
        private long last = System.currentTimeMillis();
        public boolean passed(long ms) { return System.currentTimeMillis() - last >= ms; }
        public void reset() { last = System.currentTimeMillis(); }
    }

    private static class Animation {
        private final float length;
        private long start;
        private boolean state;
        public Animation(boolean initial, float length) {
            this.length = length;
            this.state = initial;
            this.start = System.currentTimeMillis();
        }
        public void setState(boolean state) { this.state = state; this.start = System.currentTimeMillis(); }
        public double getFactor() {
            double elapsed = (System.currentTimeMillis() - start) / length;
            double f = state ? Math.min(elapsed, 1) : Math.max(1 - elapsed, 0);
            return Math.min(f, 1);
        }
        public boolean isFinished() { return getFactor() <= 0.01 || getFactor() >= 0.99; }
    }

    @Override
    public String getModuleData() {
        return String.format("%d/s", crystalCounter.getPerSecond());
    }
}
