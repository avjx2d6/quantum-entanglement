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
        // All four components share ONE ordered set of scenes, so hovering any
        // of them opens the same chapter list, pageable with the Next Scene
        // arrows. (Attaching a scene to several components via forComponents is
        // exactly how Create does it — the scene's text keys are shared.)
        helper.forComponents(
                        ModRegistry.QUANTUM_CORE_ITEM.getId(),
                        ModRegistry.RESONATOR_ITEM.getId(),
                        ModRegistry.QUANTUM_SHARD.getId(),
                        ModRegistry.EYE_OF_ELSEWHERE.getId())
                .addStoryBoard("ritual_circle", QuantumScenes::circleAssembly, TAG_QUANTUM)
                .addStoryBoard("ritual_circle", QuantumScenes::ritual, TAG_QUANTUM)
                .addStoryBoard("shared_pool", QuantumScenes::sharedPool, TAG_QUANTUM);
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
