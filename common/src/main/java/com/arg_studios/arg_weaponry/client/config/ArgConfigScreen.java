package com.arg_studios.arg_weaponry.client.config;

import com.arg_studios.arg_weaponry.config.ArgConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The mod's configuration screen: one ON/OFF button for {@code toggle_aiming}.
 *
 * <h2>Who opens it</h2>
 * <p>On Fabric the screen is exposed through Mod Menu's {@code modmenu} entrypoint, so it appears as the "Config" button of
 * this mod when Mod Menu is installed. It is a plain {@link Screen} with no Mod Menu or Cloth Config type in its signature,
 * so the class itself is safe to load on both loaders and in a game without Mod Menu; only the entrypoint that mentions Mod
 * Menu needs that mod, and Mod Menu only loads that entrypoint when it is installed.</p>
 *
 * <h2>Behaviour</h2>
 * <p>The button reads and writes {@link ArgConfig}, and each press is persisted immediately. The aiming input reads the
 * value on every client tick, so a change is applied without closing the screen, restarting the game or reloading a world.
 * The screen does not pause a single-player game, so the effect can be observed right away.</p>
 */
public final class ArgConfigScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFF9A9A9A;
    private static final int PANEL_BACKGROUND = 0xC8101010;
    private static final int PANEL_BORDER = 0xFF6A6A6A;

    private final Screen parent;

    private Button toggleButton;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    /**
     * @param parent the screen to return to when this one closes, may be {@code null}
     */
    public ArgConfigScreen(Screen parent) {
        super(Component.literal("ARG Weaponry Workshop Origins"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(BUTTON_WIDTH + 16, Math.min(260, width - 16));
        panelHeight = 92;
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(2, (height - panelHeight) / 2);
        int left = panelX + (panelWidth - BUTTON_WIDTH) / 2;

        toggleButton = addRenderableWidget(Button.builder(toggleLabel(), button -> toggleAiming())
                .bounds(left, panelY + 34, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(left, panelY + 60, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void toggleAiming() {
        ArgConfig.setToggleAiming(!ArgConfig.toggleAiming());
        if (toggleButton != null) {
            toggleButton.setMessage(toggleLabel());
        }
    }

    private static Component toggleLabel() {
        return Component.literal("Toggle Aiming: " + (ArgConfig.toggleAiming() ? "ON" : "OFF"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BACKGROUND);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER);
        super.extractRenderState(graphics, mouseX, mouseY, a);

        int left = panelX + 8;
        graphics.text(getFont(), "ARG Weaponry Workshop Origins", left, panelY + 8, TEXT);
        graphics.text(getFont(), "ON: right-click toggles aiming. OFF: hold right-click to aim.",
                left, panelY + 20, TEXT_DIM);
    }

    /** Lets the world keep ticking so the aiming change is visible behind the panel. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }
}
