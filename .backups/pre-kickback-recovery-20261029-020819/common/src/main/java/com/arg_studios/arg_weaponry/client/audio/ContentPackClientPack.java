package com.arg_studios.arg_weaponry.client.audio;

import com.arg_studios.arg_weaponry.Constants;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import com.arg_studios.arg_weaponry.ArgWeaponry;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplayLoader;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * One content pack, exposed to Minecraft's resource stack.
 *
 * <p>Minecraft plays audio through its sound engine, which resolves an event identifier through the client's
 * {@code SoundManager} registry - and that registry is built from every {@code sounds.json} in the resource stack. A
 * content pack's {@code .ogg} files are therefore only reachable once the pack participates in that stack, which is what
 * this class provides: everything is delegated to the pack folder itself ({@link PathPackResources}), so the existing
 * content-pack layout - {@code assets/<namespace>/sounds/**.ogg} - is used unchanged and no second resource system is
 * introduced.</p>
 *
 * <h2>Sound events without a hand-written sounds.json</h2>
 * <p>A weapon definition names its sounds as full resource identifiers, for example
 * {@code arg_pack:weapons/dmrs/m100_dmr/m100_dmr_fire}, which is exactly the identifier Minecraft derives for the file
 * {@code assets/arg_pack/sounds/weapons/dmrs/m100_dmr/m100_dmr_fire.ogg}. The bundled pack ships those files but an empty
 * {@code sounds.json}, so this class <em>synthesises</em> one for every namespace that does not provide its own: one
 * event per {@code .ogg} file found under {@code sounds/}, named after its path without the extension. A pack that
 * ships a real {@code sounds.json} is left completely untouched - the file it wrote always wins.</p>
 *
 * <p>The synthesised document is generated once per namespace and cached, so a resource reload does not rescan the
 * folder for every event lookup.</p>
 *
 * <h2>Slot icons (Milestone 10S)</h2>
 * <p>The same bridge also makes a weapon's configured {@code slot} texture reach the item renderer. A display file names
 * it as {@code arg_pack:gui/hud/slots/m100_dmr}, and the weapon item id is {@code arg_pack:m100_dmr}, so Minecraft looks
 * for {@code assets/arg_pack/items/m100_dmr.json} - a file no content pack ships, because the id is chosen by the pack.
 * For every weapon whose display declares a usable {@code slot}, this class therefore serves two synthesised documents:</p>
 * <ul>
 *   <li>{@code assets/<ns>/items/<weapon path>.json} - the 26.3 item definition that points at the model below, and</li>
 *   <li>{@code assets/<ns>/models/arg_slot/<weapon path>.json} - a {@code minecraft:item/generated} model whose
 *   {@code layer0} is the declared slot texture.</li>
 * </ul>
 * <p>The texture itself is served by the delegated pack resources, so the PNG on disk is what gets drawn - nothing is
 * copied, converted or baked into the mod. A weapon whose display declares no slot keeps the built-in placeholder, and a
 * pack that ships its own {@code items/<id>.json} always wins.</p>
 */
public final class ContentPackClientPack extends PathPackResources {

    private static final String SOUNDS_JSON = "sounds.json";
    private static final String SOUNDS_DIRECTORY = "sounds";
    private static final String OGG_EXTENSION = ".ogg";
    private static final String ASSETS = "assets";
    private static final String ITEMS_DIRECTORY = "items/";
    private static final String SLOT_MODEL_DIRECTORY = "models/arg_slot/";
    /** The model id path, i.e. what follows {@code models/} in the file layout. */
    private static final String SLOT_MODEL_ID_PATH = "arg_slot/";
    private static final String TEXTURES_DIRECTORY = "textures";
    private static final String JSON_EXTENSION = ".json";
    private static final String PNG_EXTENSION = ".png";

    private final Path root;
    private final ContentPack pack;
    private final Map<String, byte[]> synthesisedByNamespace = new ConcurrentHashMap<>();
    private final Map<String, byte[]> synthesisedDocuments = new ConcurrentHashMap<>();
    private Map<String, String> slotTexturesByItemPath;

    /**
     * Supplies the weapon definitions this bridge reads slot textures from.
     *
     * <p>The game uses {@link ArgWeaponry#weaponDefinitions()}, i.e. exactly the weapons the pack loader accepted. The seam
     * exists so the bridge can be exercised against a temporary pack folder - with a real display document, a real PNG and a
     * real definition - without starting the game.</p>
     */
    @FunctionalInterface
    public interface WeaponSource {

        /**
         * @return the weapon definitions whose displays should be consulted
         */
        java.util.Collection<WeaponDefinition> definitions();
    }

    private static volatile WeaponSource weaponSource = ArgWeaponry::weaponDefinitions;

    /**
     * @param location the pack location vanilla reports for this pack
     * @param root     the pack folder, i.e. the directory that contains {@code assets} and {@code data}
     */
    public ContentPackClientPack(PackLocationInfo location, Path root) {
        this(location, root, null);
    }

    /**
     * @param location the pack location vanilla reports for this pack
     * @param root     the pack folder
     * @param pack     the content pack this folder belongs to, used to read its weapon displays; may be {@code null}
     */
    /**
     * Replaces the weapon source used to resolve slot textures.
     *
     * @param source the new source; {@code null} restores the game's own weapon list
     */
    public static void setWeaponSource(WeaponSource source) {
        weaponSource = source == null ? ArgWeaponry::weaponDefinitions : source;
    }

    public ContentPackClientPack(PackLocationInfo location, Path root, ContentPack pack) {
        super(location, root);
        this.root = root;
        this.pack = pack;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
        if (type != PackType.CLIENT_RESOURCES) {
            // Client-side only on purpose: the project reads a pack's data/ tree through its own scanner, and mounting
            // it as a datapack as well would silently add the pack's recipe files to the integrated server.
            return null;
        }
        IoSupplier<InputStream> original = super.getResource(type, location);
        if (!SOUNDS_JSON.equals(location.getPath())) {
            if (original != null) {
                return original;
            }
            byte[] document = synthesisedSlotDocument(location);
            return document == null ? null : () -> new ByteArrayInputStream(document);
        }
        if (original != null && !isEmptyDocument(original)) {
            // The pack wrote a real sounds.json: it wins, untouched.
            return original;
        }
        byte[] generated = synthesisedSoundsJson(location.getNamespace());
        return generated == null ? original : () -> new ByteArrayInputStream(generated);
    }

    /**
     * Checks whether a pack's own {@code sounds.json} declares nothing.
     *
     * <p>The bundled pack ships an empty object, and a pack created from a template usually does too. Treating that as
     * "the author declared no events" would leave every {@code .ogg} file in the pack unreachable even though the weapon
     * definitions name them, so an empty (or whitespace-only) document is treated as absent and the synthesised one is
     * used instead. A document with any content at all is never replaced.</p>
     *
     * @param supplier the resource to inspect
     * @return whether it contains no event declarations
     */
    private static boolean isEmptyDocument(IoSupplier<InputStream> supplier) {
        try (InputStream stream = supplier.get()) {
            byte[] bytes = stream.readNBytes(4096);
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            return text.isEmpty() || text.equals("{}") || text.equals("{\n\n}");
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? super.getNamespaces(type) : Set.of();
    }

    /**
     * Lists the pack's real files, plus the slot documents Milestone 10S synthesises.
     *
     * <p>Minecraft does not discover models by asking for a known id: {@code ModelManager} walks {@code models/**} and
     * {@code items/**} through this method, so a document that only exists in memory would be served by
     * {@link #getResource} but never found by the loader. The synthesised slot entries are therefore listed as if they were
     * files, and only when the pack does not ship its own copy.</p>
     */
    @Override
    public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }
        super.listResources(type, namespace, directory, output);
        if (pack == null) {
            return;
        }
        String prefix = namespace + ":";
        for (String key : slotTexturesByItemPath().keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String itemPath = key.substring(prefix.length());
            listIfMatching(Identifier.fromNamespaceAndPath(namespace,
                    ITEMS_DIRECTORY + itemPath + JSON_EXTENSION), directory, output);
            listIfMatching(Identifier.fromNamespaceAndPath(namespace,
                    SLOT_MODEL_DIRECTORY + itemPath + JSON_EXTENSION), directory, output);
        }
    }

    /**
     * Reports one synthesised document to a listing, when the listed directory contains it.
     *
     * @param id        the resource id of the synthesised document
     * @param directory the directory being listed, empty for the pack root
     * @param output    the sink to report to
     */
    private void listIfMatching(Identifier id, String directory, ResourceOutput output) {
        String path = id.getPath();
        if (!directory.isEmpty() && !path.startsWith(directory + "/")) {
            return;
        }
        if (super.getResource(PackType.CLIENT_RESOURCES, id) != null) {
            return;
        }
        byte[] document = synthesisedSlotDocument(id);
        if (document != null) {
            output.accept(id, () -> new ByteArrayInputStream(document));
        }
    }

    /**
     * Builds (and caches) the {@code sounds.json} of one namespace from the {@code .ogg} files the pack contains.
     *
     * @param namespace the pack's resource namespace
     * @return the document in UTF-8, or {@code null} when the pack declares no sound files for that namespace
     */
    private byte[] synthesisedSoundsJson(String namespace) {
        byte[] cached = synthesisedByNamespace.get(namespace);
        if (cached != null) {
            return cached;
        }
        Path soundsRoot = root.resolve(ASSETS).resolve(namespace).resolve(SOUNDS_DIRECTORY);
        if (!Files.isDirectory(soundsRoot)) {
            return null;
        }
        Map<String, String> events = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(soundsRoot)) {
            List<Path> files = new ArrayList<>(walk.filter(Files::isRegularFile).toList());
            files.sort(Path::compareTo);
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.endsWith(OGG_EXTENSION)) {
                    continue;
                }
                String relative = soundsRoot.relativize(file).toString().replace('\\', '/');
                String eventPath = relative.substring(0, relative.length() - OGG_EXTENSION.length());
                events.put(eventPath, namespace + ":" + eventPath);
            }
        } catch (IOException e) {
            Constants.LOG.warn("[ARG] The sound folder of namespace '{}' could not be read ({}); its sounds stay "
                    + "silent", namespace, e.getMessage());
            return null;
        }
        if (events.isEmpty()) {
            return null;
        }

        JsonObject document = new JsonObject();
        for (Map.Entry<String, String> event : events.entrySet()) {
            JsonObject definition = new JsonObject();
            JsonArray sounds = new JsonArray();
            sounds.add(event.getValue());
            definition.add("sounds", sounds);
            document.add(event.getKey(), definition);
        }
        byte[] json = new GsonBuilder().setPrettyPrinting().create().toJson(document)
                .getBytes(StandardCharsets.UTF_8);
        synthesisedByNamespace.put(namespace, json);
        Constants.LOG.info("[ARG] Content pack '{}' declares {} playable sound event(s) in namespace '{}'",
                packId(), events.size(), namespace);
        return json;
    }

    /**
     * Serves the two synthesised documents that give a weapon a 2D slot icon.
     *
     * @param location the requested client resource
     * @return the document in UTF-8, or {@code null} when this resource is not a weapon slot document of this pack
     */
    private byte[] synthesisedSlotDocument(Identifier location) {
        if (pack == null) {
            return null;
        }
        String path = location.getPath();
        String itemPath;
        boolean itemDefinition;
        if (path.startsWith(ITEMS_DIRECTORY) && path.endsWith(JSON_EXTENSION)) {
            itemPath = path.substring(ITEMS_DIRECTORY.length(), path.length() - JSON_EXTENSION.length());
            itemDefinition = true;
        } else if (path.startsWith(SLOT_MODEL_DIRECTORY) && path.endsWith(JSON_EXTENSION)) {
            itemPath = path.substring(SLOT_MODEL_DIRECTORY.length(), path.length() - JSON_EXTENSION.length());
            itemDefinition = false;
        } else {
            return null;
        }
        String cacheKey = (itemDefinition ? "item:" : "model:") + location.getNamespace() + ":" + itemPath;
        byte[] cached = synthesisedDocuments.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String slot = slotTexturesByItemPath().get(location.getNamespace() + ":" + itemPath);
        if (slot == null) {
            return null;
        }
        JsonObject document = new JsonObject();
        if (itemDefinition) {
            JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            // The id is what follows "models/", so the document served at models/arg_slot/<item> is addressed as
            // <namespace>:arg_slot/<item>.
            model.addProperty("model", location.getNamespace() + ":" + SLOT_MODEL_ID_PATH + itemPath);
            document.add("model", model);
        } else {
            document.addProperty("parent", "minecraft:item/generated");
            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", slot);
            document.add("textures", textures);
        }
        byte[] json = new GsonBuilder().setPrettyPrinting().create().toJson(document)
                .getBytes(StandardCharsets.UTF_8);
        synthesisedDocuments.put(cacheKey, json);
        return json;
    }

    /**
     * Reads the slot texture every weapon of this pack declares.
     *
     * <p>Built once from the pack's own weapon registry through the established display loader, so the mapping comes from
     * the same parser the renderer uses and no path is hard-coded here. A weapon whose declared PNG is not in the pack is
     * left out: synthesising an item model for a texture that cannot be opened would replace the placeholder icon with a
     * missing-texture icon, which is strictly worse than keeping the placeholder, so the bridge reports it and falls back.</p>
     *
     * @return a map from {@code namespace:itemPath} to the declared slot texture id
     */
    private synchronized Map<String, String> slotTexturesByItemPath() {
        if (slotTexturesByItemPath != null) {
            return slotTexturesByItemPath;
        }
        Map<String, String> map = new TreeMap<>();
        try {
            for (WeaponDefinition definition : weaponSource.definitions()) {
                ResourceId weaponId = definition.authoritativeId();
                if (weaponId == null || !pack.providesNamespace(weaponId.namespace())) {
                    continue;
                }
                Optional<WeaponDisplay> display = WeaponDisplayLoader.load(pack, definition, new ValidationReport());
                if (display.isEmpty() || display.get().slot() == null) {
                    continue;
                }
                String slot = display.get().slot().asString();
                if (!textureExists(slot)) {
                    reportMissingTexture(definition, slot);
                    continue;
                }
                map.put(weaponId.asString(), slot);
            }
        } catch (RuntimeException e) {
            Constants.LOG.warn("[ARG] The slot textures of pack '{}' could not be resolved ({}); those weapons keep "
                    + "the placeholder icon", pack.id(), e.getMessage());
        }
        slotTexturesByItemPath = map;
        if (!map.isEmpty()) {
            Constants.LOG.info("[ARG] Pack '{}' provides {} weapon slot texture(s)", pack.id(), map.size());
        }
        return map;
    }

    /**
     * @param slot the declared texture id, for example {@code arg_pack:gui/hud/slots/m100_dmr}
     * @return whether the PNG really exists inside this pack
     */
    private boolean textureExists(String slot) {
        Path png = texturePath(slot);
        return png != null && Files.isRegularFile(png);
    }

    /**
     * @param slot the declared texture id
     * @return the file the id resolves to inside this pack, or {@code null} when the id is not a resource identifier
     */
    private Path texturePath(String slot) {
        Identifier id = Identifier.tryParse(slot);
        if (id == null) {
            return null;
        }
        return root.resolve(ASSETS).resolve(id.getNamespace()).resolve(TEXTURES_DIRECTORY)
                .resolve(id.getPath() + PNG_EXTENSION);
    }

    /**
     * Reports a declared slot texture that this pack does not contain.
     *
     * @param definition the weapon that declared it
     * @param slot       the declared texture id
     */
    private void reportMissingTexture(WeaponDefinition definition, String slot) {
        Path png = texturePath(slot);
        Constants.LOG.warn("[ARG] The weapon '{}' declares the slot texture '{}' but no file exists at {}; it keeps the "
                        + "placeholder icon instead of a missing texture",
                definition.name(), slot, png == null ? "(not a valid resource identifier)" : root.relativize(png));
    }
}
