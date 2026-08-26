package com.quantumitems;

import com.mojang.serialization.MapCodec;
import com.quantumitems.block.QuantumCoreBlock;
import com.quantumitems.block.QuantumCoreBlockEntity;
import com.quantumitems.block.ResonatorBlock;
import com.quantumitems.block.ResonatorBlockEntity;
import com.quantumitems.loot.AddItemLootModifier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModRegistry {
    private ModRegistry() {
    }

    private static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, QuantumItemsMod.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(QuantumItemsMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(QuantumItemsMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, QuantumItemsMod.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, QuantumItemsMod.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, QuantumItemsMod.MOD_ID);
    private static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, QuantumItemsMod.MOD_ID);

    private static DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(QuantumItemsMod.MOD_ID, name)));
    }

    public static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> RITUAL_HUM = sound("ritual_hum");
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> RITUAL_RISER = sound("ritual_riser");
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> RITUAL_BURST = sound("ritual_burst");
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> RITUAL_CANCEL = sound("ritual_cancel");

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<QuantumLinkData>> QUANTUM_LINK =
            DATA_COMPONENTS.register("quantum_link", () -> DataComponentType.<QuantumLinkData>builder()
                    .persistent(QuantumLinkData.CODEC)
                    .networkSynchronized(QuantumLinkData.STREAM_CODEC)
                    .build());

    public static final DeferredBlock<QuantumCoreBlock> QUANTUM_CORE =
            BLOCKS.registerBlock("quantum_core", QuantumCoreBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_PURPLE)
                            .strength(4.0f, 1200.0f)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()
                            .lightLevel(state -> state.getValue(com.quantumitems.block.QuantumCoreBlock.GLOW))
                            .sound(SoundType.METAL));

    public static final DeferredBlock<ResonatorBlock> RESONATOR =
            BLOCKS.registerBlock("resonator", ResonatorBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_PURPLE)
                            .strength(3.0f, 1200.0f)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()
                            .sound(SoundType.AMETHYST));

    public static final DeferredItem<BlockItem> QUANTUM_CORE_ITEM =
            ITEMS.registerSimpleBlockItem(QUANTUM_CORE);

    public static final DeferredItem<BlockItem> RESONATOR_ITEM =
            ITEMS.registerSimpleBlockItem(RESONATOR);

    public static final DeferredItem<Item> QUANTUM_KNOT =
            ITEMS.registerSimpleItem("quantum_knot", new Item.Properties().rarity(Rarity.RARE));

    /** A creative toy; see {@link com.quantumitems.debug.StrandWandItem}. */
    public static final DeferredItem<Item> STRAND_WAND =
            ITEMS.register("strand_wand",
                    () -> new com.quantumitems.debug.StrandWandItem(
                            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredItem<Item> EYE_OF_ELSEWHERE =
            ITEMS.registerSimpleItem("eye_of_elsewhere", new Item.Properties().rarity(Rarity.UNCOMMON));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuantumCoreBlockEntity>> QUANTUM_CORE_BE =
            BLOCK_ENTITIES.register("quantum_core",
                    () -> BlockEntityType.Builder.of(QuantumCoreBlockEntity::new, QUANTUM_CORE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResonatorBlockEntity>> RESONATOR_BE =
            BLOCK_ENTITIES.register("resonator",
                    () -> BlockEntityType.Builder.of(ResonatorBlockEntity::new, RESONATOR.get()).build(null));

    public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<AddItemLootModifier>> ADD_ITEM_LOOT =
            LOOT_MODIFIERS.register("add_item", () -> AddItemLootModifier.CODEC);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.quantumitems"))
                    .icon(() -> new ItemStack(QUANTUM_KNOT.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(QUANTUM_KNOT.get());
                        output.accept(EYE_OF_ELSEWHERE.get());
                        output.accept(RESONATOR_ITEM.get());
                        output.accept(QUANTUM_CORE_ITEM.get());
                        output.accept(STRAND_WAND.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        DATA_COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        CREATIVE_TABS.register(modBus);
        LOOT_MODIFIERS.register(modBus);
        SOUNDS.register(modBus);
    }
}
