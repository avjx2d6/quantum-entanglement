package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The Quantum Shard is exclusively rare Ancient City loot — crafting it would
 * make network fuel farmable, and the artifact must never be automatable.
 * The injection goes through a global loot modifier so other mods touching
 * the same table are unaffected.
 */
@PrefixGameTestTemplate(false)
public class LootGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(LootGameTests.class);
        }
    }

    private static int countShards(GameTestHelper helper, net.minecraft.resources.ResourceKey<LootTable> tableKey,
                                   int rolls) {
        LootTable table = helper.getLevel().getServer().reloadableRegistries().getLootTable(tableKey);
        LootParams params = new LootParams.Builder(helper.getLevel())
                .withParameter(LootContextParams.ORIGIN, helper.absoluteVec(new net.minecraft.world.phys.Vec3(1, 1, 1)))
                .create(LootContextParamSets.CHEST);
        int shards = 0;
        for (int i = 0; i < rolls; i++) {
            List<ItemStack> loot = table.getRandomItems(params);
            for (ItemStack stack : loot) {
                if (stack.is(ModRegistry.QUANTUM_KNOT.get())) {
                    shards += stack.getCount();
                }
            }
        }
        return shards;
    }

    /**
     * 300 chest rolls at 15% ≈ 45 expected shards; the [10, 120] window is
     * over twelve sigma wide, so a failure means the modifier is broken,
     * not that the dice were unlucky.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public void ancientCityChestsYieldShards(GameTestHelper helper) {
        int shards = countShards(helper, BuiltInLootTables.ANCIENT_CITY, 300);
        if (shards < 10 || shards > 120) {
            helper.fail("Expected roughly 15%% shard rate from ancient city chests, got " + shards + "/300 rolls");
        }
        helper.succeed();
    }

    /** The modifier must not leak into unrelated tables. */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 200)
    public void otherChestsNeverYieldShards(GameTestHelper helper) {
        int shards = countShards(helper, BuiltInLootTables.SIMPLE_DUNGEON, 100);
        if (shards != 0) {
            helper.fail("Quantum shard leaked into simple_dungeon loot: " + shards);
        }
        helper.succeed();
    }

    /**
     * The load-time heal must never read a sealed loot container. Reading any
     * slot of one rolls its whole table (getItem calls unpackLootTable), which
     * writes items, marks the block changed, and asks a neighbour for its
     * comparator signal — a world lookup that can pull in another chunk. From
     * the chunk-load handler that parked the server thread inside its own chunk
     * pipeline and the world hung with no error at all. A sealed container also
     * cannot hold a window, so there is nothing in it to heal.
     */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void reconcileLeavesSealedLootChestSealed(GameTestHelper helper) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(1, 1, 1);
        helper.setBlock(pos, net.minecraft.world.level.block.Blocks.CHEST);
        net.minecraft.world.level.block.entity.ChestBlockEntity chest =
                (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(pos);
        chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 1234L);

        com.quantumitems.engine.QuantumEngine.onServerThread().reconcileContainer(chest);

        if (chest.getLootTable() == null) {
            helper.fail("Reconcile unpacked a sealed loot chest — that rolls the table and can deadlock chunk load");
        }
        helper.succeed();
    }
}
