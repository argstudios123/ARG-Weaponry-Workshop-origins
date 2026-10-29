package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.AnimationFile;
import com.arg_studios.arg_weaponry.weapon.animation.BedrockAnimation;
import com.arg_studios.arg_weaponry.weapon.animation.ChannelKind;
import com.arg_studios.arg_weaponry.weapon.animation.LoopMode;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.SamplingIssue;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationController;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.dev.DevelopmentWeapons;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * State behind the development Animation Tester.
 *
 * <p>Discovers the animation names of one weapon's animation document through the normal content-pack
 * resolver and {@link WeaponAnimationCache}, and drives exactly one {@link WeaponAnimationController}. The
 * screen is a thin view over this object: every control here is also usable without a GUI, which is what the
 * headless tests exercise.</p>
 *
 * <p>Nothing here is gameplay: it is enabled only by {@code -Darg_weaponry.dev_animation_tester=true} together
 * with the development-weapon switch, it never mutates the animation document, the model or the cache, and it
 * only ever plays clips the user selects. The animation list is discovered, never hard-coded, so any weapon
 * whose display declares an animation resource works.</p>
 *
 * <p>The screen never opens by itself. Both loaders open it only from the Ctrl + Alt + J shortcut, which is
 * handled by {@link #openScreenFromShortcut()}, and there is no automatic or scripted opening path.</p>
 */
public final class AnimationTester {

    /** System property that enables the tester. Off unless explicitly set to {@code true}. */
    public static final String ENABLE_PROPERTY = "arg_weaponry.dev_animation_tester";

    /**
     * The key that opens the tester, registered by both loaders. The shortcut is <strong>Ctrl + Alt + J</strong>:
     * this is the bound key, and both modifiers must be held when it is pressed.
     */
    public static final int SHORTCUT_KEY = InputConstants.KEY_J;

    /** The documented stepping interval used by the frame-step buttons and keyboard arrows. */
    public static final double FRAME_SECONDS = 1.0 / 60.0;

    /** The playback speeds offered by the tester. */
    public static final double[] SPEEDS = {0.25, 0.5, 1.0, 2.0};

    /** Longest frame delta fed to the controller, so a stalled frame does not jump through a clip. */
    private static final double MAX_FRAME_DELTA_SECONDS = 0.25;

    /** How many frames are logged as evidence that playback really advances. */
    private static final int EVIDENCE_FRAMES = 12;

    /** The bone reported in the evidence log; it moves in `anim.model.fire`. */
    private static final String EVIDENCE_BONE = "bolt2";

    private static final AnimationTester INSTANCE = new AnimationTester();

    private final WeaponAnimationController controller = new WeaponAnimationController();

    private ContentPack pack;
    private WeaponDefinition definition;
    private ResourceId resource;
    private AnimationFile document;
    private List<String> names = List.of();
    private String selected;
    private String message = "";
    private double speed = 1.0;
    private boolean loop = true;
    private long lastFrameNanos;
    private int loggedFrames;

    private AnimationTester() {
    }

    /** @return the shared tester state; the screen and the renderer hook both use it. */
    public static AnimationTester instance() {
        return INSTANCE;
    }

    /** @return {@code true} when the tester switch and the development weapon switch are both on. */
    public boolean isEnabled() {
        String override = System.getProperty(ENABLE_PROPERTY);
        if (override == null || !Boolean.parseBoolean(override.trim())) {
            return false;
        }
        return DevelopmentWeapons.isEnabled();
    }

    // --- discovery ---

    /**
     * Attaches the tester to a weapon: resolves its display, loads its animation document through the cache and
     * discovers the animation names in file order.
     *
     * <p>Idempotent: calling it every frame with the same weapon costs one resource-identifier comparison. A
     * different animation resource (after a reload, or another weapon) triggers rediscovery, and a selection
     * that no longer exists falls back to the first animation with a message.</p>
     *
     * @param pack       the weapon's content pack
     * @param definition the weapon definition
     * @return {@code true} when at least one animation is available
     */
    public synchronized boolean attach(ContentPack pack, WeaponDefinition definition) {
        if (!isEnabled() || pack == null || definition == null) {
            return false;
        }
        WeaponDisplay display = WeaponDisplayCache.instance().get(pack, definition).orElse(null);
        if (display == null || display.animations() == null) {
            message = "this weapon declares no animation resource";
            return false;
        }
        if (display.animations().equals(resource) && document != null) {
            return !names.isEmpty();
        }

        Optional<AnimationFile> loaded = WeaponAnimationCache.instance().load(pack, display.animations());
        this.pack = pack;
        this.definition = definition;
        this.resource = display.animations();
        if (loaded.isEmpty()) {
            this.document = null;
            this.names = List.of();
            this.selected = null;
            this.message = "the animation resource could not be loaded: " + display.animations().asString();
            return false;
        }

        this.document = loaded.get();
        this.names = loaded.get().names();
        if (selected == null || !names.contains(selected)) {
            selected = names.isEmpty() ? null : names.get(0);
        }
        this.message = "loaded " + names.size() + " animation(s) from " + display.animations().asString();
        Constants.LOG.info("[ARG] Animation tester attached to '{}': {} animation(s) from {} ({})",
                definition.name(), names.size(), display.animations().asString(), String.join(", ", names));
        return !names.isEmpty();
    }

    /** Forgets the attached weapon and stops playback, used when content is reloaded. */
    public synchronized void reset() {
        controller.stop();
        pack = null;
        definition = null;
        resource = null;
        document = null;
        names = List.of();
        selected = null;
        lastFrameNanos = 0L;
        loggedFrames = 0;
        message = "resources were reloaded; select an animation again";
        Constants.LOG.info("[ARG] Animation tester reset after a resource reload");
    }

    /** @return the discovered animation names, in file order; never {@code null}. */
    public synchronized List<String> animationNames() {
        return names;
    }

    /** @return the animation the selector points at, or {@code null} when none could be discovered. */
    public synchronized String selectedAnimation() {
        return selected;
    }

    /** @return the index of the selected animation inside the discovered list, or {@code -1}. */
    public synchronized int selectedIndex() {
        return selected == null ? -1 : names.indexOf(selected);
    }

    /** @return the weapon name shown in the tester header. */
    public synchronized String weaponName() {
        return definition == null ? "(no weapon)" : definition.name();
    }

    /** @return the animation resource shown in the tester header. */
    public synchronized ResourceId resourceId() {
        return resource;
    }

    /** @return the last human readable status or error message. */
    public synchronized String message() {
        return message;
    }

    /** @return {@code true} when a document is attached. */
    public synchronized boolean isAttached() {
        return document != null;
    }

    // --- selection ---

    /**
     * Selects an animation by its exact name and starts it from the beginning.
     *
     * @param name the animation name as written in the file
     * @return {@code true} when the animation exists and started
     */
    public synchronized boolean select(String name) {
        if (document == null) {
            message = "no animation document is attached";
            return false;
        }
        if (name == null || !names.contains(name)) {
            message = "unknown animation: " + name;
            return false;
        }
        BedrockAnimation animation = document.animation(name);
        if (animation == null) {
            message = "the document does not declare " + name;
            return false;
        }
        selected = name;
        controller.play(animation, loop ? LoopMode.LOOP : LoopMode.ONCE);
        loggedFrames = 0;
        Constants.LOG.info("[ARG] Animation tester selected '{}' ({}s, loop override {})", name,
                animation.lengthSeconds(), loop);
        message = "playing " + name + " (" + format(animation.lengthSeconds()) + "s)";
        return true;
    }

    /**
     * Selects the animation at an index, wrapping around the discovered list.
     *
     * @param index the index into {@link #animationNames()}
     * @return {@code true} when an animation started
     */
    public synchronized boolean selectIndex(int index) {
        if (names.isEmpty()) {
            return false;
        }
        return select(names.get(Math.floorMod(index, names.size())));
    }

    /** Selects the previous (-1) or next (+1) animation in the discovered list. */
    public synchronized boolean stepAnimation(int direction) {
        return selectIndex(selectedIndex() + direction);
    }

    // --- playback controls ---

    /** Starts the selected animation from the beginning. */
    public synchronized boolean play() {
        return selected != null && select(selected);
    }

    /** Freezes playback at the current time; the pose stays visible. */
    public synchronized void pause() {
        controller.pause();
        message = "paused at " + format(controller.elapsedSeconds()) + "s";
    }

    /** Continues a paused animation from where it stopped. */
    public synchronized void resume() {
        controller.resume();
        message = "resumed at " + format(controller.elapsedSeconds()) + "s";
    }

    /** Stops playback; the weapon returns to its static pose. */
    public synchronized void stop() {
        controller.stop();
        message = "stopped";
    }

    /** Restarts the selected animation at time zero. */
    public synchronized boolean restart() {
        return play();
    }

    /** @return the new loop state. */
    public synchronized boolean toggleLoop() {
        setLoop(!loop);
        return loop;
    }

    /**
     * Changes the looping policy of the tester without touching the animation document.
     *
     * <p>When a clip is loaded, it is restarted with the new policy at the same time so the change is visible
     * immediately; the parsed {@code BedrockAnimation} is never modified (the policy is a per-play override).</p>
     *
     * @param loop {@code true} to loop, {@code false} to play once
     */
    public synchronized void setLoop(boolean loop) {
        if (this.loop == loop) {
            return;
        }
        this.loop = loop;
        String name = controller.animationName();
        if (name != null) {
            double time = controller.elapsedSeconds();
            boolean wasPaused = controller.isPaused();
            if (select(name)) {
                controller.seek(time);
                if (wasPaused) {
                    controller.pause();
                }
            }
        }
        message = "loop " + (loop ? "on" : "off");
    }

    /** @return {@code true} when the tester loops the selected clip. */
    public synchronized boolean loopEnabled() {
        return loop;
    }

    /**
     * Sets the playback speed multiplier applied to the frame delta.
     *
     * @param speed a positive, finite multiplier
     */
    public synchronized void setSpeed(double speed) {
        if (!Double.isFinite(speed) || speed <= 0.0) {
            return;
        }
        this.speed = speed;
        message = "speed " + formatSpeed(speed);
    }

    /** @return the current playback speed multiplier. */
    public synchronized double speed() {
        return speed;
    }

    /**
     * Moves the current pose by a fixed frame interval without resuming playback.
     *
     * @param direction {@code -1} for the previous frame, {@code +1} for the next frame
     */
    public synchronized void stepFrame(int direction) {
        controller.seek(controller.elapsedSeconds() + direction * FRAME_SECONDS);
        message = "stepped to " + format(controller.elapsedSeconds()) + "s";
    }

    /**
     * Samples an explicit time without starting, stopping or resuming playback.
     *
     * <p>The screen exposes this through the frame-step buttons; the method is public so a scripted session or a
     * future slider can seek to any time without touching the animation document.</p>
     *
     * @param seconds the requested time; clamped to the clip, wrapped for a looping clip
     */
    public synchronized void seek(double seconds) {
        controller.seek(seconds);
        message = "seeked to " + format(controller.elapsedSeconds()) + "s";
    }

    // --- state for the screen ---

    /** @return the playback time in seconds. */
    public synchronized double elapsedSeconds() {
        return controller.elapsedSeconds();
    }

    /** @return the duration of the loaded clip in seconds, or {@code 0}. */
    public synchronized double durationSeconds() {
        return controller.durationSeconds();
    }

    /** @return the fraction of the clip already played, in {@code [0, 1]}. */
    public synchronized double progress() {
        double duration = durationSeconds();
        if (duration <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, elapsedSeconds() / duration));
    }

    /** @return a short status word for the header. */
    public synchronized String statusText() {
        if (controller.animationName() == null) {
            return "stopped";
        }
        if (controller.isPaused()) {
            return "paused";
        }
        return controller.isFinished() ? "finished (holding last frame)" : "playing";
    }

    /** @return {@code true} while an animation is loaded. */
    public synchronized boolean isPlaying() {
        return controller.isPlaying();
    }

    /** @return {@code true} while playback is frozen. */
    public synchronized boolean isPaused() {
        return controller.isPaused();
    }

    /** @return {@code true} once a one-shot clip reached its end. */
    public synchronized boolean isFinished() {
        return controller.isFinished();
    }

    /** @return the animated bone names of the current pose, in file order. */
    public synchronized List<String> animatedBones() {
        WeaponPose pose = currentPose();
        return pose == null ? List.of() : new ArrayList<>(pose.bones().keySet());
    }

    /** @return how many channels the current pose evaluates. */
    public synchronized int channelCount() {
        WeaponPose pose = currentPose();
        if (pose == null) {
            return 0;
        }
        int count = 0;
        for (SampledBone bone : pose.bones().values()) {
            for (ChannelKind kind : ChannelKind.values()) {
                if (bone.channel(kind) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    /** @return the sampling issues of the current pose; empty when everything sampled cleanly. */
    public synchronized List<SamplingIssue> issues() {
        WeaponPose pose = currentPose();
        return pose == null ? List.of() : pose.issues();
    }

    /** @return the current immutable pose, or {@code null} when nothing should be applied. */
    public synchronized WeaponPose currentPose() {
        if (!isEnabled() || document == null) {
            return null;
        }
        if (!controller.isPlaying() && !controller.isFinished()) {
            return null;
        }
        WeaponPose pose = controller.currentPose();
        return pose.isEmpty() ? null : pose;
    }

    /**
     * Returns the pose to render for a weapon, or {@code null} when the tester does not own it.
     *
     * <p>Guarded by the exact definition instance so a tester session can never animate a different weapon.</p>
     *
     * @param definition the definition currently being rendered
     * @return the pose, or {@code null}
     */
    public synchronized WeaponPose poseFor(WeaponDefinition definition) {
        if (definition == null || definition != this.definition) {
            return null;
        }
        return currentPose();
    }

    // --- frame hooks ---

    /**
     * Advances tester playback by an explicit delta, scaled by the selected speed.
     *
     * @param deltaSeconds frame time in seconds; invalid values are ignored, long stalls are capped
     */
    public synchronized void advance(double deltaSeconds) {
        if (!isEnabled() || document == null) {
            return;
        }
        double delta = deltaSeconds;
        if (!Double.isFinite(delta) || delta < 0.0) {
            delta = 0.0;
        }
        if (delta > MAX_FRAME_DELTA_SECONDS) {
            delta = MAX_FRAME_DELTA_SECONDS;
        }
        controller.tick(delta * speed);
    }

    /**
     * Client hook called once per rendered weapon frame: attaches to the weapon and advances playback with the
     * real frame time. It never opens the screen; only the Ctrl + Alt + J shortcut does that.
     *
     * @param pack       the rendered weapon's content pack
     * @param definition the rendered weapon's definition
     */
    public static void onWeaponFrame(ContentPack pack, WeaponDefinition definition) {
        AnimationTester tester = INSTANCE;
        if (!tester.isEnabled()) {
            return;
        }
        tester.attach(pack, definition);
        synchronized (tester) {
            long now = System.nanoTime();
            double delta = tester.lastFrameNanos == 0L ? 0.0 : (now - tester.lastFrameNanos) / 1_000_000_000.0;
            tester.lastFrameNanos = now;
            tester.advance(delta);
            tester.logEvidence(delta);
        }
    }

    /** Logs the first handful of frames so a scripted run can prove that playback really advances. */
    private void logEvidence(double deltaSeconds) {
        if (loggedFrames >= EVIDENCE_FRAMES || (!isPlaying() && !isFinished())) {
            return;
        }
        loggedFrames++;
        WeaponPose pose = currentPose();
        Vec3 sample = pose == null || pose.bone(EVIDENCE_BONE) == null ? null
                : pose.bone(EVIDENCE_BONE).channel(ChannelKind.POSITION);
        Constants.LOG.info(String.format(Locale.ROOT,
                "[ARG] Animation tester frame %d: delta %.4fs, elapsed %.4fs, %s (%d bones), %s.position = %s",
                loggedFrames, deltaSeconds, elapsedSeconds(), statusText(), pose == null ? 0 : pose.boneCount(),
                EVIDENCE_BONE, sample));
    }

    private static String format(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private static String formatSpeed(double speed) {
        return String.format(Locale.ROOT, "%sx", speed == Math.rint(speed) ? String.valueOf((int) speed)
                : String.valueOf(speed));
    }

    /**
     * Opens the tester for the weapon currently held in the main hand, or tells the player why it cannot.
     *
     * <p>Called by the development-only key binding on both loaders.</p>
     */
    public void openScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isEnabled() || minecraft.player == null) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (!(held.getItem() instanceof WeaponItem weapon)) {
            minecraft.player.sendSystemMessage(
                    Component.literal("[ARG] Hold a weapon to open the animation tester"));
            return;
        }
        if (!attach(weapon.pack(), weapon.definition())) {
            minecraft.player.sendSystemMessage(Component.literal("[ARG] " + message()));
            return;
        }
        minecraft.gui.setScreen(new AnimationTesterScreen());
    }

    /**
     * Handles the tester shortcut: opens the tester for the held weapon only while Ctrl and Alt are held.
     *
     * <p>Called by the development-only key binding on both loaders, so the modifier rule lives in one place
     * instead of being duplicated per loader.</p>
     *
     * @return {@code true} when the shortcut modifiers were held and the request was handled
     */
    public boolean openScreenFromShortcut() {
        if (!areShortcutModifiersDown()) {
            return false;
        }
        openScreen();
        return true;
    }

    /**
     * Tells whether the shortcut modifiers are held.
     *
     * @return {@code true} when both Ctrl and Alt are currently held, read from the same key state vanilla's own
     *         key mappings are polled from
     */
    public static boolean areShortcutModifiersDown() {
        boolean control = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
        boolean alt = InputConstants.isKeyDown(InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(InputConstants.KEY_RALT);
        return control && alt;
    }
}
