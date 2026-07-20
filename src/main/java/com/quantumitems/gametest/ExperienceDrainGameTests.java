package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.block.QuantumCoreBlock;
import com.quantumitems.block.QuantumCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Observer drinks experience during a ritual: nearby players leak XP as
 * real orbs, and ALL orbs in radius get reeled into the core and vanish.
 * Conservation is structural: drained amount == orb value; absorbed = gone,
 * caught mid-flight = refunded by vanilla pickup.
 */
@PrefixGameTestTemplate(false)
public class ExperienceDrainGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(ExperienceDrainGameTests.class);
        }
    }

    private static final BlockPos CORE = new BlockPos(3, 2, 3);
    private static final BlockPos[] RESONATORS = {
            new BlockPos(1, 2, 1), new BlockPos(5, 2, 1),
            new BlockPos(1, 2, 5), new BlockPos(5, 2, 5)};
    private static final int RITUAL_TICKS = QuantumCoreBlockEntity.CHARGING_TICKS
            + QuantumCoreBlockEntity.JUDGEMENT_TICKS + 5;

    private static void buildCircle(GameTestHelper helper) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(CORE.offset(dx, -1, dz), Blocks.AMETHYST_BLOCK);
            }
        }
        for (BlockPos pos : RESONATORS) {
            helper.setBlock(pos, ModRegistry.RESONATOR.get());
        }
        helper.setBlock(CORE, ModRegistry.QUANTUM_CORE.get().defaultBlockState());
        helper.setBlock(CORE.above(), ModRegistry.QUANTUM_CORE.get().defaultBlockState()
                .setValue(QuantumCoreBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static QuantumCoreBlockEntity core(GameTestHelper helper) {
        return (QuantumCoreBlockEntity) helper.getBlockEntity(CORE);
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 200)
    public void ritualDrainsNearbyPlayerExperience(GameTestHelper helper) {
        buildCircle(helper);
        // makeMockServerPlayerInLevel is locked to creative (stub connection
        // ignores setGameMode) and creative is exempt from the drain — so use
        // a plain survival mock added to the level as an ordinary entity.
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 standing = Vec3.atCenterOf(helper.absolutePos(CORE.offset(1, 0, 0)));
        player.moveTo(standing.x, standing.y - 0.5, standing.z, 0, 0);
        helper.getLevel().addFreshEntity(player);
        player.giveExperiencePoints(40);
        int before = player.totalExperience;

        core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(RITUAL_TICKS, () -> {
            if (player.totalExperience >= before) {
                helper.fail("Player standing in the circle must leak experience, still has "
                        + player.totalExperience + "/" + before);
            }
            player.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "arena", templateNamespace = "quantumitems", timeoutTicks = 200)
    public void ritualAbsorbsLooseOrbs(GameTestHelper helper) {
        buildCircle(helper);
        Vec3 orbPos = Vec3.atCenterOf(helper.absolutePos(CORE.offset(2, 1, 2)));
        ExperienceOrb orb = new ExperienceOrb(helper.getLevel(), orbPos.x, orbPos.y, orbPos.z, 5);
        helper.getLevel().addFreshEntity(orb);

        core(helper).placeShard(new ItemStack(ModRegistry.QUANTUM_SHARD.get()));
        helper.runAfterDelay(RITUAL_TICKS, () -> {
            AABB around = new AABB(helper.absolutePos(CORE)).inflate(8);
            var remaining = helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, around);
            if (!remaining.isEmpty()) {
                helper.fail("All loose orbs in radius must be reeled in and absorbed, "
                        + remaining.size() + " remain");
            }
            helper.succeed();
        });
    }
}
