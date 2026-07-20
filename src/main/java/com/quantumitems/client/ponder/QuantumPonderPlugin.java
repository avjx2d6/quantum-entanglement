package com.quantumitems.client.ponder;

import com.quantumitems.ModRegistry;
import com.quantumitems.QuantumItemsMod;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers Quantum Entanglement's Ponder content.
 *
 * Vocabulary, straight from the library:
 *  - A <b>scene</b> is one {@link net.createmod.ponder.api.scene.PonderStoryBoard}:
 *    a single animated sequence played over one schematic ({@code .nbt}).
 *  - The <b>group of scenes</b> the player pages through with "Next Scene" is
 *    simply every storyboard registered against the same <i>component</i>
 *    (item/block). There is no explicit group object — it is the set of
 *    scenes sharing a component, ordered via {@code orderAfter/orderBefore}.
 *  - A <b>tag</b> ({@link PonderTagRegistrationHelper}) is a category in the
 *    Ponder index screen; it bundles several components under one heading.
 *
 * Schematics resolve from {@code assets/quantumitems/ponder/<path>.nbt}.
 * Scene text defaults are authored in this repo's {@code en_us.json}/
 * {@code ru_ru.json} under keys {@code quantumitems.ponder.<sceneId>.*}.
 */
public class QuantumPonderPlugin implements PonderPlugin {

    public static final ResourceLocation TAG_QUANTUM =
            ResourceLocation.fromNamespaceAndPath(QuantumItemsMod.MOD_ID, "quantum_entanglement");

    @Override
    public String getModId() {
        return QuantumItemsMod.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation shard = ModRegistry.QUANTUM_SHARD.getId();
        ResourceLocation eye = ModRegistry.EYE_OF_ELSEWHERE.getId();
        ResourceLocation core = ModRegistry.QUANTUM_CORE_ITEM.getId();

        // Scene 1 — building the ritual circle. Anchored to the core item,
        // since the core is what the player places to start a structure.
        helper.addStoryBoard(core, "ritual_circle", QuantumScenes::circleAssembly, TAG_QUANTUM);

        // Scene 2 — running the ritual (also surfaced on the shard, the fuel).
        helper.addStoryBoard(shard, "ritual_circle", QuantumScenes::ritual, TAG_QUANTUM);

        // Scene 3 — one pool seen through windows far apart. The payoff of the
        // eye, so it lives there.
        helper.addStoryBoard(eye, "shared_pool", QuantumScenes::sharedPool, TAG_QUANTUM);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(TAG_QUANTUM)
                .addToIndex()
                .item(ModRegistry.EYE_OF_ELSEWHERE.get(), true, false)
                .title("Quantum Entanglement")
                .description("Entangle stacks so distant windows share one pool of items.")
                .register();

        helper.addToTag(TAG_QUANTUM)
                .add(ModRegistry.QUANTUM_CORE_ITEM.getId())
                .add(ModRegistry.RESONATOR_ITEM.getId())
                .add(ModRegistry.QUANTUM_SHARD.getId())
                .add(ModRegistry.EYE_OF_ELSEWHERE.getId());
    }
}
