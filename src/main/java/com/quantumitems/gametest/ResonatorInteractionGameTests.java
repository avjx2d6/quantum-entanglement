package com.quantumitems.gametest;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import com.quantumitems.QuantumLinkData;
import com.quantumitems.QuantumNetworks;
import com.quantumitems.block.ResonatorBlockEntity;
import com.quantumitems.engine.QuantumEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Depot-style hand interaction with a resonator (mirrors Create's depot):
 * clicking always hands back whatever lies there, then lays down what you
 * held — empty hand is a plain take, full hand is a swap. Reproduces the
 * playtest bug where an occupied resonator returned FAIL on an empty-hand
 * click, which suppressed the useWithoutItem fallback entirely.
 */
@PrefixGameTestTemplate(false)
public class ResonatorInteractionGameTests {

    @EventBusSubscriber(modid = QuantumItemsMod.MOD_ID)
    public static final class Registrar {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            event.register(ResonatorInteractionGameTests.class);
        }
    }

    private static final BlockPos POS = new BlockPos(1, 2, 1);

    /**
     * Replicates ServerPlayerGameMode's exact dispatch: useWithoutItem runs
     * ONLY on PASS_TO_DEFAULT_BLOCK_INTERACTION (the vanilla helper's
     * useBlock is more lenient and would mask the FAIL bug).
     */
    private static void interact(GameTestHelper helper, Player player, BlockPos relativePos) {
        BlockPos absolute = helper.absolutePos(relativePos);
        BlockState state = helper.getLevel().getBlockState(absolute);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute),
                net.minecraft.core.Direction.UP, absolute, false);
        ItemInteractionResult result = state.useItemOn(player.getItemInHand(InteractionHand.MAIN_HAND),
                helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
        if (result == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            state.useWithoutItem(helper.getLevel(), player, hit);
        }
    }

    private static ResonatorBlockEntity resonator(GameTestHelper helper) {
        helper.setBlock(POS, ModRegistry.RESONATOR.get());
        return (ResonatorBlockEntity) helper.getBlockEntity(POS);
    }

    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void emptyHandTakesLaidOutStackIntoTheHand(GameTestHelper helper) {
        ResonatorBlockEntity resonator = resonator(helper);
        resonator.setItem(0, new ItemStack(Items.BREAD, 20));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        interact(helper, player, POS);
        if (!resonator.isEmpty()) {
            helper.fail("Empty-hand click must take the stack back, resonator still holds "
                    + resonator.getItem(0));
        }
        ItemStack inHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!inHand.is(Items.BREAD) || inHand.getCount() != 20) {
            helper.fail("Taken stack must land IN THE HAND, hand holds " + inHand);
        }
        helper.succeed();
    }

    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void fullHandSwapsStacks(GameTestHelper helper) {
        ResonatorBlockEntity resonator = resonator(helper);
        resonator.setItem(0, new ItemStack(Items.APPLE, 7));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 20));
        interact(helper, player, POS);
        ItemStack laid = resonator.getItem(0);
        if (!laid.is(Items.BREAD) || laid.getCount() != 20) {
            helper.fail("Held stack should lie down whole, resonator holds " + laid);
        }
        ItemStack inHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!inHand.is(Items.APPLE) || inHand.getCount() != 7) {
            helper.fail("Swap must put the previous stack into the hand, hand holds " + inHand);
        }
        helper.succeed();
    }

    /** Taking a window by hand is a player gesture: the link must travel whole. */
    @GameTest(template = "box", templateNamespace = "quantumitems", timeoutTicks = 100)
    public void windowTakenByHandKeepsItsLink(GameTestHelper helper) {
        ResonatorBlockEntity resonator = resonator(helper);
        QuantumNetworks networks = QuantumNetworks.get(helper.getLevel().getServer());
        ItemStack plain = new ItemStack(Items.BREAD, 15);
        int id = networks.createNetwork(plain);
        ItemStack window = plain.copy();
        window.set(ModRegistry.QUANTUM_LINK.get(), new QuantumLinkData(id, 1));
        QuantumEngine.onServerThread().adopt(window);
        resonator.setItem(0, window);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        interact(helper, player, POS);

        ItemStack inHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        QuantumLinkData link = inHand.get(ModRegistry.QUANTUM_LINK.get());
        if (link == null || link.networkId() != id) {
            helper.fail("Window lost its link on a hand take: " + inHand);
        }
        if (inHand.getCount() != 15) {
            helper.fail("Window count diverged: " + inHand.getCount());
        }
        QuantumNetworks.Network network = networks.network(id);
        if (network == null || network.pool != 15) {
            helper.fail("Pool damaged by a hand take");
        }
        helper.succeed();
    }
}
