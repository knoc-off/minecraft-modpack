package dev.tabsearch.mixin;

import dev.tabsearch.TabSearchMatcher;
import dev.tabsearch.compat.EmiCompat;
import dev.tabsearch.mixin.accessor.WidgetPositionAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Targets Inventory Tabs' TabManager, which loads through Sinytra Connector (the mod
 * is published for Fabric and is not in our compile classpath -- see libs/ for the
 * reference jar used only to read its bytecode during development).
 *
 * <p>Connector remaps Fabric intermediary bytecode to Mojang mappings before handing
 * classes to the rest of the NeoForge/Mixin pipeline, which is why this mixin (and the
 * accessor mixins it relies on) can use real Minecraft types despite the target
 * classes never appearing on our compile classpath. {@code @Pseudo} suppresses the
 * "class not found" warning Mixin would otherwise log for an unresolvable target;
 * {@code "required": false} in tabsearch.mixins.json means a mapping mismatch or a
 * missing Inventory Tabs install fails this one mixin, not mod startup.
 */
@Pseudo
@Mixin(targets = "folk.sisby.inventory_tabs.TabManager", remap = false)
public class TabManagerMixin {

    @Shadow(remap = false)
    private static List<Object> tabs;

    @Shadow(remap = false)
    private static List<Object> tabPositions;

    @Shadow(remap = false)
    private static int currentPage;

    @Inject(method = "render", at = @At("TAIL"), remap = false, require = 0)
    private static void tabsearch$onRender(GuiGraphics context, double mouseX, double mouseY, CallbackInfo ci) {
        if (!(EmiCompat.isLoaded() && EmiCompat.isHighlightActive())) {
            return;
        }

        int visible = Math.min(tabPositions.size(), tabs.size() - currentPage * tabPositions.size());
        for (int i = 0; i < visible; i++) {
            Object rawPos = tabPositions.get(i);
            Object tab = tabs.get(currentPage * tabPositions.size() + i);
            if (rawPos == null || tab == null) continue;

            boolean matches = TabSearchMatcher.matches(tab, Minecraft.getInstance().level);
            if (!matches) {
                WidgetPositionAccessor pos = (WidgetPositionAccessor) rawPos;
                int x = pos.getX();
                int y = pos.getY() + (pos.isUp() ? -21 : 0);
                context.fill(x, y, x + 24, y + 21, 0x77000000);
            }
        }
    }
}
