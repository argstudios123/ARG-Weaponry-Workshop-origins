package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Development-only screen that plays any animation of the held weapon's animation document.
 *
 * <p>The screen owns no animation state: it reads and drives {@link AnimationTester}, which in turn drives the
 * shared {@code WeaponAnimationController}. Because the first-person weapon is still rendered while a screen
 * is open, the pose the tester produces is visible behind this panel, so a clip can be inspected while it
 * plays.</p>
 *
 * <p>The panel is deliberately plain: text, buttons and a progress bar, no textures, no external libraries. It
 * clamps to the window size, truncates long animation names with an ellipsis and greys out controls that
 * cannot do anything in the current state.</p>
 */
public final class AnimationTesterScreen extends Screen {

    private static final int PANEL_HEIGHT = 232;
    private static final int BUTTON_HEIGHT = 20;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFF9A9A9A;
    private static final int TEXT_WARN = 0xFFFFAA55;
    private static final int PANEL_BACKGROUND = 0xC8101010;
    private static final int PANEL_BORDER = 0xFF6A6A6A;
    private static final int BAR_BACKGROUND = 0xFF202020;
    private static final int BAR_FILL = 0xFF4FBF4F;

    private Button previousButton;
    private Button nextButton;
    private Button playButton;
    private Button pauseButton;
    private Button resumeButton;
    private Button stopButton;
    private Button restartButton;
    private Button loopButton;
    private Button backStepButton;
    private Button forwardStepButton;
    private final Button[] speedButtons = new Button[AnimationTester.SPEEDS.length];

    private int panelX;
    private int panelY;
    private int panelWidth;

    public AnimationTesterScreen() {
        super(Component.literal("ARG Animation Tester"));
    }

    @Override
    protected void init() {
        AnimationTester tester = AnimationTester.instance();
        panelWidth = Math.max(240, Math.min(360, width - 16));
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(2, (height - PANEL_HEIGHT) / 2);
        int left = panelX + 8;
        int inner = panelWidth - 16;

        int selectorY = panelY + 32;
        previousButton = addRenderableWidget(Button.builder(Component.literal("<"),
                button -> tester.stepAnimation(-1)).bounds(left, selectorY, 20, BUTTON_HEIGHT).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"),
                button -> tester.stepAnimation(1)).bounds(left + inner - 20, selectorY, 20, BUTTON_HEIGHT).build());

        int transportY = panelY + 56;
        int quarter = (inner - 3 * 4) / 4;
        playButton = addRenderableWidget(Button.builder(Component.literal("Play"),
                button -> tester.play()).bounds(left, transportY, quarter, BUTTON_HEIGHT).build());
        pauseButton = addRenderableWidget(Button.builder(Component.literal("Pause"),
                button -> tester.pause()).bounds(left + (quarter + 4), transportY, quarter, BUTTON_HEIGHT).build());
        resumeButton = addRenderableWidget(Button.builder(Component.literal("Resume"),
                button -> tester.resume()).bounds(left + 2 * (quarter + 4), transportY, quarter, BUTTON_HEIGHT).build());
        stopButton = addRenderableWidget(Button.builder(Component.literal("Stop"),
                button -> tester.stop()).bounds(left + 3 * (quarter + 4), transportY, quarter, BUTTON_HEIGHT).build());

        int optionsY = panelY + 80;
        int half = (inner - 4) / 2;
        restartButton = addRenderableWidget(Button.builder(Component.literal("Restart"),
                button -> tester.restart()).bounds(left, optionsY, half, BUTTON_HEIGHT).build());
        loopButton = addRenderableWidget(Button.builder(Component.literal("Loop"),
                button -> tester.toggleLoop()).bounds(left + half + 4, optionsY, half, BUTTON_HEIGHT).build());

        int speedY = panelY + 104;
        for (int index = 0; index < AnimationTester.SPEEDS.length; index++) {
            double speed = AnimationTester.SPEEDS[index];
            int slotWidth = (inner - 3 * 4) / 4;
            int slotX = left + index * (slotWidth + 4);
            speedButtons[index] = addRenderableWidget(Button.builder(Component.literal(labelForSpeed(speed)),
                    button -> tester.setSpeed(speed)).bounds(slotX, speedY, slotWidth, BUTTON_HEIGHT).build());
        }

        int stepY = panelY + 128;
        backStepButton = addRenderableWidget(Button.builder(Component.literal("<- 1/60s"),
                button -> tester.stepFrame(-1)).bounds(left, stepY, half, BUTTON_HEIGHT).build());
        forwardStepButton = addRenderableWidget(Button.builder(Component.literal("1/60s ->"),
                button -> tester.stepFrame(1)).bounds(left + half + 4, stepY, half, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(panelX + panelWidth / 2 - 50, panelY + PANEL_HEIGHT - 22, 100, BUTTON_HEIGHT).build());

        com.arg_studios.arg_weaponry.Constants.LOG.info(
                "[ARG] Animation tester screen opened: panel {}x{} at ({}, {}), window {}x{}, {} discovered animation(s)",
                panelWidth, PANEL_HEIGHT, panelX, panelY, width, height,
                AnimationTester.instance().animationNames().size());
    }

    private static String labelForSpeed(double speed) {
        return speed == Math.rint(speed) ? (int) speed + "x" : speed + "x";
    }

    /** Lets the world keep ticking and rendering so the weapon animates behind the panel. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        updateWidgets();

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + PANEL_HEIGHT, PANEL_BACKGROUND);
        graphics.outline(panelX, panelY, panelWidth, PANEL_HEIGHT, PANEL_BORDER);
        super.extractRenderState(graphics, mouseX, mouseY, a);

        AnimationTester tester = AnimationTester.instance();
        net.minecraft.client.gui.Font font = getFont();
        int left = panelX + 8;
        int textWidth = panelWidth - 16;

        graphics.text(font, "Animation Tester  -  " + tester.weaponName(), left, panelY + 6, TEXT);
        ResourceId resource = tester.resourceId();
        graphics.text(font, fit(font, resource == null ? "(no animation resource)" : resource.asString(), textWidth),
                left, panelY + 18, TEXT_DIM);

        String selected = tester.selectedAnimation();
        String selection;
        if (selected == null) {
            selection = "(no animations discovered)";
        } else {
            selection = fit(font, selected, textWidth - 52) + "   " + (tester.selectedIndex() + 1) + "/"
                    + tester.animationNames().size();
        }
        graphics.text(font, selection, left + 24, panelY + 38, selected == null ? TEXT_WARN : TEXT);

        int barY = panelY + 152;
        graphics.fill(left, barY, left + textWidth, barY + 8, BAR_BACKGROUND);
        int filled = (int) Math.round(textWidth * tester.progress());
        if (filled > 0) {
            graphics.fill(left, barY, left + filled, barY + 8, BAR_FILL);
        }
        graphics.outline(left, barY, textWidth, 8, PANEL_BORDER);

        graphics.text(font, String.format(java.util.Locale.ROOT, "%s / %s s   %s   speed %s   loop %s",
                        formatSeconds(tester.elapsedSeconds()), formatSeconds(tester.durationSeconds()),
                        tester.statusText(), labelForSpeed(tester.speed()),
                        tester.loopEnabled() ? "on" : "off"),
                left, panelY + 164, TEXT);

        List<String> bones = tester.animatedBones();
        String boneText = bones.isEmpty() ? "animated bones: none"
                : "bones (" + bones.size() + ", " + tester.channelCount() + " channels): " + String.join(", ", bones);
        graphics.text(font, fit(font, boneText, textWidth), left, panelY + 176, TEXT_DIM);

        List<com.arg_studios.arg_weaponry.weapon.animation.SamplingIssue> issues = tester.issues();
        String status = issues.isEmpty() ? tester.message() : "issue: " + issues.get(0).reason();
        graphics.text(font, fit(font, status, textWidth), left, panelY + 188,
                issues.isEmpty() ? TEXT_DIM : TEXT_WARN);
    }

    private void updateWidgets() {
        AnimationTester tester = AnimationTester.instance();
        boolean attached = tester.isAttached();
        boolean hasSelection = tester.selectedAnimation() != null;
        boolean multiple = attached && tester.animationNames().size() > 1;

        previousButton.active = multiple;
        nextButton.active = multiple;
        playButton.active = hasSelection;
        pauseButton.active = tester.isPlaying() && !tester.isPaused() && !tester.isFinished();
        resumeButton.active = tester.isPaused();
        stopButton.active = tester.isPlaying() || tester.isFinished();
        restartButton.active = hasSelection;
        backStepButton.active = hasSelection;
        forwardStepButton.active = hasSelection;
        loopButton.setMessage(Component.literal("Loop: " + (tester.loopEnabled() ? "ON" : "OFF")));

        for (int index = 0; index < speedButtons.length; index++) {
            double speed = AnimationTester.SPEEDS[index];
            boolean active = Math.abs(tester.speed() - speed) < 1.0E-9;
            speedButtons[index].setMessage(Component.literal(
                    (active ? "> " : "") + labelForSpeed(speed) + (active ? " <" : "")));
        }
    }

    private static String formatSeconds(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", seconds);
    }

    /** Cuts a string to a pixel width, appending an ellipsis, so long animation names never overlap controls. */
    private static String fit(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (text == null || font.width(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        int budget = Math.max(0, maxWidth - font.width(ellipsis));
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            String candidate = builder.toString() + text.charAt(index);
            if (font.width(candidate) > budget) {
                break;
            }
            builder.append(text.charAt(index));
        }
        return builder + ellipsis;
    }
}
