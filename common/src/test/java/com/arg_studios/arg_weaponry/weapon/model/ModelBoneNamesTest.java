package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

/**
 * The master-bone alias used by sway: {@code main} first, then the existing ARG-compatible {@code root}.
 *
 * <p>A missing model or a model with neither bone resolves to {@code null}; no caller has to throw or invent a name.</p>
 */
class ModelBoneNamesTest {

    @Test
    void mainIsPreferredAndRootIsTheBackwardsCompatibleAlias() {
        assertEquals(ModelBoneNames.MAIN, ModelBoneNames.masterBone(model("main", "root")));
        assertEquals(ModelBoneNames.MAIN_FALLBACK, ModelBoneNames.masterBone(model("root")));
    }

    @Test
    void aModelWithoutEitherBoneFailsGracefully() {
        assertNull(ModelBoneNames.masterBone(model("gunbody")));
        assertNull(ModelBoneNames.masterBone(model()));
        assertNull(ModelBoneNames.masterBone(null));
    }

    private static WeaponModel model(String... names) {
        List<ModelBone> bones = java.util.Arrays.stream(names)
                .map(name -> new ModelBone(name, null, Vec3.ZERO, Vec3.ZERO, List.of(), List.of()))
                .toList();
        return new WeaponModel(new ResourceId("test", "model"), "geometry.test", "1.8.0", 64, 64,
                bones, bones, 0, 0);
    }
}
