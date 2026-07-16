package com.quantumitems.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

import org.jetbrains.annotations.NotNull;

/**
 * Injects an extra item into a vanilla loot table without replacing it —
 * conditions (which table, what chance) live in the datapack JSON, so other
 * mods touching the same table are unaffected. Used to make the Quantum
 * Shard exclusively rare Ancient City loot: crafting it would make the
 * network fuel farmable, and automation is exactly what the artifact must
 * never feed on.
 */
public class AddItemLootModifier extends LootModifier {
    public static final MapCodec<AddItemLootModifier> CODEC = RecordCodecBuilder.mapCodec(instance ->
            codecStart(instance).and(instance.group(
                    BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(m -> m.item),
                    Codec.INT.optionalFieldOf("count", 1).forGetter(m -> m.count)
            )).apply(instance, AddItemLootModifier::new));

    private final Item item;
    private final int count;

    public AddItemLootModifier(LootItemCondition[] conditions, Item item, int count) {
        super(conditions);
        this.item = item;
        this.count = count;
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(@NotNull ObjectArrayList<ItemStack> generatedLoot,
                                                          LootContext context) {
        generatedLoot.add(new ItemStack(item, count));
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
