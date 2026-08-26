package net.favela.yaw.impl.modules.categories.combat;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.event.Events;
import net.favela.yaw.impl.event.Priority;
import net.favela.yaw.impl.event.events.PacketEvent;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.BooleanSetting;
import net.favela.yaw.impl.setting.settings.EnumSetting;
import net.favela.yaw.impl.setting.settings.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.HashMap;
import java.util.Map;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class AutoCrystal extends Module {

    public enum Swap {
        NORMAL,
        SILENT,
        OFF
    }

    public enum Rotate {
        NONE,
        PACKET
    }

    public enum PlaceMode {
        NATIVE,
        PROTOCOL
    }

    public final EnumSetting<Swap> swap = enm(
            "Swap",
            "Crystal hotbar swap mode",
            Swap.SILENT
    );

    public final EnumSetting<Rotate> rotate = enm(
            "Rotate",
            "Server-side rotation",
            Rotate.PACKET
    );

    public final EnumSetting<PlaceMode> placeMode = enm(
            "PlaceMode",
            "Crystal placement mode",
            PlaceMode.NATIVE
    );

    public final NumberSetting placeRange = num(
            "PlaceRange",
            "Maximum crystal placement distance",
            0.1,
            6.0,
            4.5,
            0.1
    );

    public final NumberSetting placeWallRange = num(
            "PlaceWallRange",
            "Maximum placement distance through walls",
            0.1,
            6.0,
            4.5,
            0.1
    );

    public final NumberSetting breakRange = num(
            "BreakRange",
            "Maximum crystal attack distance",
            0.1,
            6.0,
            4.5,
            0.1
    );

    public final NumberSetting breakWallRange = num(
            "BreakWallRange",
            "Maximum crystal attack distance through walls",
            0.1,
            6.0,
            4.5,
            0.1
    );

    public final NumberSetting minDamage = num(
            "MinDamage",
            "Minimum damage to an enemy",
            0.1,
            36.0,
            4.0,
            0.1
    );

    public final NumberSetting maxSelfDamage = num(
            "MaxSelfDamage",
            "Maximum allowed self damage",
            0.1,
            36.0,
            10.0,
            0.1
    );

    public final NumberSetting attackDelay = num(
            "AttackDelay",
            "Delay between crystal attacks",
            0,
            1000,
            50,
            1
    );

    public final NumberSetting placeDelay = num(
            "PlaceDelay",
            "Delay between crystal placements",
            0,
            1000,
            50,
            1
    );

    public final BooleanSetting safety = bool(
            "Safety",
            "Protect against excessive self damage",
            true
    );

    public final BooleanSetting breakThroughWalls = bool(
            "BreakThroughWalls",
            "Allow attacking crystals through walls",
            true
    );

    public final BooleanSetting placeThroughWalls = bool(
            "PlaceThroughWalls",
            "Allow placing through walls",
            true
    );

    public final BooleanSetting swing = bool(
            "Swing",
            "Swing when attacking or placing",
            true
    );

    public final BooleanSetting attack = bool(
            "Attack",
            "Attack existing crystals",
            true
    );

    public final BooleanSetting place = bool(
            "Place",
            "Place crystals",
            true
    );

    public final BooleanSetting targetPlayersOnly = bool(
            "PlayersOnly",
            "Only target players",
            false
    );

    private Events.Handler<PacketEvent.Receive> packetHandler;

    private EndCrystal attackTarget;
    private BlockPos placeTarget;
    private Vec3 rotationTarget;

    private long lastAttack;
    private long lastPlace;

    private int silentPreviousSlot = -1;

    private final Map<Integer, Long> attackedCrystals = new HashMap<>();

    public AutoCrystal() {
        super(
                "AutoCrystal",
                "Automatically places and attacks end crystals.",
                Category.COMBAT
        );
    }

    @Override
    public void onEnable() {
        packetHandler = Events.on(
                PacketEvent.Receive.class,
                Priority.HIGH,
                this::onPacketReceive
        );

        reset();
    }

    @Override
    public void onDisable() {
        restoreSilentSlot();

        if (packetHandler != null) {
            Events.off(packetHandler);
            packetHandler = null;
        }

        reset();
        attackedCrystals.clear();
    }

    private void reset() {
        attackTarget = null;
        placeTarget = null;
        rotationTarget = null;
        lastAttack = 0L;
        lastPlace = 0L;
    }

    @Override
    public void onTick() {
        if (MC.player == null
                || MC.level == null
                || MC.gameMode == null) {
            return;
        }

        if (MC.player.isSpectator()) {
            return;
        }

        cleanupAttackCache();

        attackTarget = null;
        placeTarget = null;
        rotationTarget = null;

        if (attack.get()) {
            attackTarget = findBestCrystalToBreak();
        }

        if (place.get()) {
            placeTarget = findBestBlockToPlace();
        }

        if (rotationTarget != null && rotate.get() == Rotate.PACKET) {
            rotateServer(rotationTarget);
        }

        long now = System.currentTimeMillis();

        if (attackTarget != null
                && now - lastAttack >= attackDelay.getLong()) {
            attackCrystal(attackTarget);
            lastAttack = now;
        }

        if (placeTarget != null
                && now - lastPlace >= placeDelay.getLong()) {
            if (placeCrystal(placeTarget)) {
                lastPlace = now;
            }
        }
    }

    private EndCrystal findBestCrystalToBreak() {
        EndCrystal bestCrystal = null;
        double bestDamage = minDamage.getDouble();

        double range = breakRange.getDouble();
        double wallRange = breakWallRange.getDouble();

        for (Entity entity : MC.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal)) {
                continue;
            }

            if (crystal.isRemoved()) {
                continue;
            }

            int id = crystal.getId();

            if (attackedCrystals.containsKey(id)) {
                continue;
            }

            double distance = MC.player.distanceTo(crystal);

            if (distance > range && distance > wallRange) {
                continue;
            }

            Vec3 crystalPosition = crystal.position();

            boolean visible = isVisible(crystalPosition);

            if (!visible && !breakThroughWalls.get()) {
                continue;
            }

            if (!visible && distance > wallRange) {
                continue;
            }

            double selfDamage = getExplosionDamage(
                    MC.player,
                    crystalPosition
            );

            if (safety.get()
                    && selfDamage > maxSelfDamage.getDouble()) {
                continue;
            }

            double targetDamage = getBestTargetDamage(crystalPosition);

            if (targetDamage > bestDamage) {
                bestDamage = targetDamage;
                bestCrystal = crystal;
                rotationTarget = crystalPosition;
            }
        }

        return bestCrystal;
    }

    private BlockPos findBestBlockToPlace() {
        BlockPos best = null;
        double bestDamage = minDamage.getDouble();

        double range = placeRange.getDouble();
        double wallRange = placeWallRange.getDouble();

        for (BlockPos pos : getCrystalBlocks()) {
            if (!canPlaceCrystalOn(pos)) {
                continue;
            }

            Vec3 crystalPosition = new Vec3(
                    pos.getX() + 0.5,
                    pos.getY() + 1.0,
                    pos.getZ() + 0.5
            );

            double distance = MC.player.position().distanceTo(
                    crystalPosition
            );

            if (distance > range && distance > wallRange) {
                continue;
            }

            boolean visible = isVisible(crystalPosition);

            if (!visible && !placeThroughWalls.get()) {
                continue;
            }

            if (!visible && distance > wallRange) {
                continue;
            }

            double selfDamage = getExplosionDamage(
                    MC.player,
                    crystalPosition
            );

            if (safety.get()
                    && selfDamage > maxSelfDamage.getDouble()) {
                continue;
            }

            double targetDamage = getBestTargetDamage(crystalPosition);

            if (targetDamage > bestDamage) {
                bestDamage = targetDamage;
                best = pos;
                rotationTarget = crystalPosition;
            }
        }

        return best;
    }

    private double getBestTargetDamage(Vec3 explosionPosition) {
        double best = 0.0;

        for (Entity entity : MC.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }

            if (!isValidTarget(living)) {
                continue;
            }

            if (living.isDeadOrDying()) {
                continue;
            }

            double damage = getExplosionDamage(
                    living,
                    explosionPosition
            );

            if (damage > best) {
                best = damage;
            }
        }

        return best;
    }

    private void attackCrystal(EndCrystal crystal) {
        if (MC.player == null || MC.gameMode == null) {
            return;
        }

        if (crystal.isRemoved()) {
            return;
        }

        int id = crystal.getId();

        attackedCrystals.put(
                id,
                System.currentTimeMillis()
        );

        MC.gameMode.attack(
                MC.player,
                crystal
        );

        if (swing.get()) {
            MC.player.swing(
                    InteractionHand.MAIN_HAND
            );
        }

        restoreSilentSlot();
    }

    private boolean placeCrystal(BlockPos pos) {
        InteractionHand hand = getCrystalHand();

        int oldSlot = MC.player.getInventory().getSelectedSlot();

        if (hand == null) {
            int slot = getCrystalSlot();

            if (slot == -1) {
                return false;
            }

            if (swap.get() == Swap.OFF) {
                return false;
            }

            if (swap.get() == Swap.SILENT) {
                switchToSilent(slot);
            } else {
                MC.player.getInventory().setSelectedSlot(slot);
                MC.player.connection.send(
                        new ServerboundSetCarriedItemPacket(slot)
                );
            }

            hand = InteractionHand.MAIN_HAND;
        }

        Direction side = getPlaceDirection(pos);

        Vec3 hitPosition = new Vec3(
                pos.getX() + 0.5,
                pos.getY() + 1.0,
                pos.getZ() + 0.5
        );

        BlockHitResult hitResult = new BlockHitResult(
                hitPosition,
                side,
                pos,
                false
        );

        var result = MC.gameMode.useItemOn(
                MC.player,
                hand,
                hitResult
        );

        if (swing.get()) {
            MC.player.swing(hand);
        }

        if (swap.get() != Swap.SILENT
                && swap.get() != Swap.NORMAL
                && oldSlot != MC.player.getInventory().getSelectedSlot()) {
            MC.player.getInventory().setSelectedSlot(oldSlot);
        }

        if (swap.get() == Swap.SILENT) {
            restoreSilentSlot();
        }

        return result.consumesAction();
    }

    private InteractionHand getCrystalHand() {
        ItemStack offhand = MC.player.getItemInHand(
                InteractionHand.OFF_HAND
        );

        if (!offhand.isEmpty()
                && offhand.getItem() instanceof EndCrystalItem) {
            return InteractionHand.OFF_HAND;
        }

        ItemStack mainhand = MC.player.getItemInHand(
                InteractionHand.MAIN_HAND
        );

        if (!mainhand.isEmpty()
                && mainhand.getItem() instanceof EndCrystalItem) {
            return InteractionHand.MAIN_HAND;
        }

        return null;
    }

    private int getCrystalSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack =
                    MC.player.getInventory().getItem(slot);

            if (!stack.isEmpty()
                    && stack.getItem() instanceof EndCrystalItem) {
                return slot;
            }
        }

        return -1;
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
            silentPreviousSlot = -1;
            return;
        }

        if (silentPreviousSlot == -1) {
            return;
        }

        int slot = silentPreviousSlot;

        MC.player.connection.send(
                new ServerboundSetCarriedItemPacket(slot)
        );

        silentPreviousSlot = -1;
    }

    private boolean canPlaceCrystalOn(BlockPos pos) {
        BlockState state = MC.level.getBlockState(pos);

        if (!state.is(Blocks.OBSIDIAN)
                && !state.is(Blocks.BEDROCK)) {
            return false;
        }

        BlockPos first = pos.above();
        BlockPos second = pos.above(2);

        BlockState firstState =
                MC.level.getBlockState(first);

        BlockState secondState =
                MC.level.getBlockState(second);

        if (placeMode.get() == PlaceMode.PROTOCOL) {
            if (!firstState.isAir()
                    && !firstState.is(Blocks.FIRE)) {
                return false;
            }

            if (!secondState.isAir()) {
                return false;
            }
        } else {
            if (!firstState.isAir()
                    && !firstState.is(Blocks.FIRE)) {
                return false;
            }

            if (!secondState.isAir()) {
                return false;
            }
        }

        AABB crystalBox = new AABB(
                pos.getX(),
                pos.getY() + 1.0,
                pos.getZ(),
                pos.getX() + 1.0,
                pos.getY() + 3.0,
                pos.getZ() + 1.0
        );

        for (Entity entity : MC.level.entitiesForRendering()) {
            if (entity.isRemoved()) {
                continue;
            }

            if (entity.getBoundingBox().intersects(crystalBox)) {
                return false;
            }
        }

        return true;
    }

    private Direction getPlaceDirection(BlockPos pos) {
        Vec3 eye = MC.player.getEyePosition();

        Direction best = Direction.UP;
        double bestDistance = Double.MAX_VALUE;

        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.relative(direction);

            if (!MC.level.getBlockState(adjacent).isSolid()) {
                continue;
            }

            Vec3 hit = Vec3.atCenterOf(adjacent)
                    .add(
                            direction.getStepX() * 0.5,
                            direction.getStepY() * 0.5,
                            direction.getStepZ() * 0.5
                    );

            double distance = eye.distanceToSqr(hit);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = direction;
            }
        }

        return best;
    }

    private Iterable<BlockPos> getCrystalBlocks() {
        int radius =
                (int) Math.ceil(
                        Math.max(
                                placeRange.getDouble(),
                                placeWallRange.getDouble()
                        )
                );

        BlockPos origin =
                MC.player.blockPosition();

        java.util.ArrayList<BlockPos> positions =
                new java.util.ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos =
                            origin.offset(x, y, z);

                    double dx =
                            pos.getX() + 0.5 - MC.player.getX();
                    double dy =
                            pos.getY() + 1.0 - MC.player.getY();
                    double dz =
                            pos.getZ() + 0.5 - MC.player.getZ();

                    double distance =
                            Math.sqrt(
                                    dx * dx
                                            + dy * dy
                                            + dz * dz
                            );

                    if (distance <=
                            Math.max(
                                    placeRange.getDouble(),
                                    placeWallRange.getDouble()
                            )) {
                        positions.add(pos);
                    }
                }
            }
        }

        return positions;
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (entity == MC.player) {
            return false;
        }

        if (entity.isDeadOrDying()) {
            return false;
        }

        if (!entity.isAttackable()) {
            return false;
        }

        if (targetPlayersOnly.get()
                && !(entity instanceof Player)) {
            return false;
        }

        return true;
    }

    private boolean isVisible(Vec3 target) {
        if (MC.player == null || MC.level == null) {
            return false;
        }

        HitResult result = MC.level.clip(
                new ClipContext(
                        MC.player.getEyePosition(),
                        target,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        MC.player
                )
        );

        return result.getType() == HitResult.Type.MISS;
    }

    private double getExplosionDamage(
            Entity entity,
            Vec3 explosionPosition
    ) {
        if (!(entity instanceof LivingEntity living)) {
            return 0.0;
        }

        double distance =
                living.position().distanceTo(
                        explosionPosition
                );

        double exposure =
                getExposure(
                        explosionPosition,
                        living.getBoundingBox()
                );

        double scaledDistance =
                distance / 12.0;

        if (scaledDistance >= 1.0) {
            return 0.0;
        }

        double impact =
                (1.0 - scaledDistance)
                        * exposure;

        double damage =
                ((impact * impact + impact)
                        / 2.0)
                        * 7.0
                        * 12.0
                        + 1.0;

        return applyProtection(
                living,
                (float) damage
        );
    }

    private double getExposure(
            Vec3 source,
            AABB box
    ) {
        double xStep =
                1.0 /
                        (box.maxX - box.minX + 1.0);

        double yStep =
                1.0 /
                        (box.maxY - box.minY + 1.0);

        double zStep =
                1.0 /
                        (box.maxZ - box.minZ + 1.0);

        int hits = 0;
        int total = 0;

        for (double x = box.minX;
             x <= box.maxX;
             x += xStep) {

            for (double y = box.minY;
                 y <= box.maxY;
                 y += yStep) {

                for (double z = box.minZ;
                     z <= box.maxZ;
                     z += zStep) {

                    Vec3 point =
                            new Vec3(x, y, z);

                    if (canSeeExplosionPoint(
                            point,
                            source
                    )) {
                        hits++;
                    }

                    total++;
                }
            }
        }

        if (total == 0) {
            return 0.0;
        }

        return (double) hits / total;
    }

    private boolean canSeeExplosionPoint(
            Vec3 start,
            Vec3 end
    ) {
        HitResult result = MC.level.clip(
                new ClipContext(
                        start,
                        end,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        MC.player
                )
        );

        return result.getType() == HitResult.Type.MISS;
    }

    private double applyProtection(
            LivingEntity entity,
            float damage
    ) {
        float armor =
                entity.getArmorValue();

        float toughness =
                (float) entity
                        .getAttributeValue(
                                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS
                        );

        net.minecraft.world.damagesource.DamageSource source =
                MC.level.damageSources()
                        .explosion(
                                null,
                                null
                        );

        float reduced =
                net.minecraft.world.damagesource.CombatRules
                        .getDamageAfterAbsorb(
                                entity,
                                damage,
                                source,
                                armor,
                                toughness
                        );

        if (entity.hasEffect(
                net.minecraft.world.effect.MobEffects.RESISTANCE
        )) {
            net.minecraft.world.effect.MobEffectInstance effect =
                    entity.getEffect(
                            net.minecraft.world.effect.MobEffects.RESISTANCE
                    );

            if (effect != null) {
                int amplifier =
                        effect.getAmplifier() + 1;

                reduced *=
                        Math.max(
                                0.0f,
                                1.0f
                                        - amplifier * 0.2f
                        );
            }
        }

        return Math.max(
                0.0,
                reduced
        );
    }

    private void rotateServer(Vec3 target) {
        if (MC.player == null) {
            return;
        }

        Vec3 eye =
                MC.player.getEyePosition();

        double dx =
                target.x - eye.x;

        double dy =
                target.y - eye.y;

        double dz =
                target.z - eye.z;

        double horizontal =
                Math.sqrt(
                        dx * dx
                                + dz * dz
                );

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

        yaw = wrapDegrees(yaw);
        pitch = Math.max(-90.0f, Math.min(90.0f, pitch));

        MC.player.connection.send(
                new ServerboundMovePlayerPacket.Rot(
                        yaw,
                        pitch,
                        MC.player.onGround(),
                        MC.player.horizontalCollision
                )
        );
    }

    private float wrapDegrees(float value) {
        value %= 360.0f;

        if (value >= 180.0f) {
            value -= 360.0f;
        }

        if (value < -180.0f) {
            value += 360.0f;
        }

        return value;
    }

    private void onPacketReceive(PacketEvent.Receive event) {
        if (MC.player == null
                || MC.level == null) {
            return;
        }

        if (event.packet()
                instanceof ClientboundRemoveEntitiesPacket packet) {

            for (int id : packet.getEntityIds()) {
                attackedCrystals.remove(id);
            }

            return;
        }

        if (event.packet()
                instanceof ClientboundExplodePacket packet) {

            Vec3 center = packet.center();
            double radius = packet.radius();

            attackedCrystals.entrySet().removeIf(
                    entry -> {
                        Entity entity =
                                MC.level.getEntity(
                                        entry.getKey()
                                );

                        if (!(entity instanceof EndCrystal crystal)) {
                            return true;
                        }

                        return crystal.position()
                                .distanceToSqr(center)
                                <= radius * radius * 4.0;
                    }
            );
        }
    }

    private void cleanupAttackCache() {
        long now =
                System.currentTimeMillis();

        attackedCrystals.entrySet().removeIf(
                entry -> now - entry.getValue() > 1000L
        );
    }
}
