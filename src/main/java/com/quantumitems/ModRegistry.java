package com.quantumitems;

import com.quantumitems.block.QuantumEntanglerBlock;
import com.quantumitems.block.QuantumEntanglerBlockEntity;
import com.quantumitems.menu.QuantumEntanglerMenu;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
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
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRegistry {
    private ModRegistry() {
    }

    private static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, QuantumItemsMod.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(QuantumItemsMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(QuantumItemsMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, QuantumItemsMod.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, QuantumItemsMod.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, QuantumItemsMod.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<QuantumLinkData>> QUANTUM_LINK =
            DATA_COMPONENTS.register("quantum_link", () -> DataComponentType.<QuantumLinkData>builder()
                    .persistent(QuantumLinkData.CODEC)
                    .networkSynchronized(QuantumLinkData.STREAM_CODEC)
                    .build());

    public static final DeferredBlock<QuantumEntanglerBlock> QUANTUM_ENTANGLER =
            BLOCKS.registerBlock("quantum_entangler", QuantumEntanglerBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_PURPLE)
                            .strength(4.0f, 1200.0f)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL));

    public static final DeferredItem<BlockItem> QUANTUM_ENTANGLER_ITEM =
            ITEMS.registerSimpleBlockItem(QUANTUM_ENTANGLER);

    public static final DeferredItem<Item> QUANTUM_SHARD =
            ITEMS.registerSimpleItem("quantum_shard", new Item.Properties().rarity(Rarity.RARE));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuantumEntanglerBlockEntity>> QUANTUM_ENTANGLER_BE =
            BLOCK_ENTITIES.register("quantum_entangler",
                    () -> BlockEntityType.Builder.of(QuantumEntanglerBlockEntity::new, QUANTUM_ENTANGLER.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<QuantumEntanglerMenu>> QUANTUM_ENTANGLER_MENU =
            MENUS.register("quantum_entangler",
                    () -> IMenuTypeExtension.create(QuantumEntanglerMenu::fromNetwork));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.quantumitems"))
                    .icon(() -> new ItemStack(QUANTUM_SHARD.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(QUANTUM_SHARD.get());
                        output.accept(QUANTUM_ENTANGLER_ITEM.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        DATA_COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        CREATIVE_TABS.register(modBus);
    }
}
