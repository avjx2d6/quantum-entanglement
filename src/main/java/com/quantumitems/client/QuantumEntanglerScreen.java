package com.quantumitems.client;

import com.quantumitems.QuantumItemsMod;
import com.quantumitems.menu.QuantumEntanglerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class QuantumEntanglerScreen extends AbstractContainerScreen<QuantumEntanglerMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(QuantumItemsMod.MOD_ID, "textures/gui/quantum_entangler.png");
    private static final int ARROW_X = 66;
    private static final int ARROW_Y = 32;
    private static final int ARROW_WIDTH = 44;
    private static final int ARROW_HEIGHT = 16;

    public QuantumEntanglerScreen(QuantumEntanglerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        guiGraphics.blit(TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);

        int progress = this.menu.getProgress();
        if (progress > 0) {
            int filled = progress * ARROW_WIDTH / this.menu.getProcessTime();
            guiGraphics.blit(TEXTURE, x + ARROW_X, y + ARROW_Y, 176, 0, filled, ARROW_HEIGHT);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
