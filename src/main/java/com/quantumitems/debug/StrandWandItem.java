package com.quantumitems.debug;

import com.quantumitems.client.debug.PaintedStrands;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * A toy. Click one block, click another, and a strand of the ritual's own
 * glowing line is stretched between the two points; sneak-click the air to
 * change its colour, sneak-click a block to wipe them all.
 *
 * <p>SCAFFOLDING — strip this and {@link PaintedStrands} before release, along
 * with the registry entry and the two lang keys. Nothing in the mod depends on
 * either.
 *
 * <p>Everything it draws lives on the client and nowhere else: no block, no
 * entity, no saved data, nothing sent to anyone. That is what makes it thirty
 * lines instead of three hundred, and also why nobody else on a server sees
 * what you paint.
 */
public class StrandWandItem extends Item {

    public StrandWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
                PaintedStrands.clear();
            } else {
                PaintedStrands.mark(context.getClickLocation());
            }
        }
        // SUCCESS on both sides so the arm swings; the server has nothing to do
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide && player.isShiftKeyDown()) {
            PaintedStrands.nextColour();
        }
        return InteractionResultHolder.success(held);
    }
}
