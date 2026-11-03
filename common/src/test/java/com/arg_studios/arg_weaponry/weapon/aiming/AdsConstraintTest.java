package com.arg_studios.arg_weaponry.weapon.aiming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;

/**
 * The ADS constraint's retention arithmetic, bone selection and camera rules.
 *
 * <p>The expected values follow the reference implementation's {@code AdsConstraintMath}: a retention of {@code r} keeps
 * {@code r} of the animated value at full ADS and the correction scales linearly with ADS progress.</p>
 */
class AdsConstraintTest {

    private static final ModelBone ADS_OFFSET = bone("ads_offset", new Vec3(2.0F, 4.0F, 0.5F));
    private static final ModelBone MAIN = bone("main", Vec3.ZERO);
    private static final ModelBone ROOT = bone("root", Vec3.ZERO);
    private static final ModelBone CAMERA = bone("camera", Vec3.ZERO);

    private static final WeaponDefinition.AdsConstraint ENABLED =
            new WeaponDefinition.AdsConstraint(true, true, Double.NaN);

    @Test
    void aDisabledOrInactiveConstraintReturnsTheSamePoseObject() {
        WeaponPose pose = pose();

        assertSame(pose, AdsConstraint.apply(pose, model(MAIN), WeaponDefinition.AdsConstraint.DISABLED, 1.0));
        assertSame(pose, AdsConstraint.apply(pose, model(MAIN), ENABLED, 0.0));
        assertNull(AdsConstraint.apply(null, model(MAIN), ENABLED, 1.0));
        assertSame(pose, AdsConstraint.apply(pose, null, ENABLED, 1.0));
    }

    @Test
    void theOffsetBoneRetentionCorrectsTheMainRotationAtFullAds() {
        WeaponPose result = AdsConstraint.apply(pose(), model(MAIN), ENABLED, 1.0);

        // ads_offset = (2, 4, 0.5) degrees -> retention (1, 1, 0.5).
        // main rotation (10, 20, 30) -> (10, 20, 15): x and y are untouched, z keeps half.
        SampledBone main = result.bone("main");
        assertEquals(10.0F, main.rotation().x(), 1.0E-5);
        assertEquals(20.0F, main.rotation().y(), 1.0E-5);
        assertEquals(15.0F, main.rotation().z(), 1.0E-5);
    }

    @Test
    void partialAdsAppliesTheCorrectionInProportion() {
        SampledBone main = AdsConstraint.apply(pose(), model(MAIN), ENABLED, 0.5).bone("main");

        assertEquals(10.0F, main.rotation().x(), 1.0E-5);
        assertEquals(20.0F, main.rotation().y(), 1.0E-5);
        assertEquals(22.5F, main.rotation().z(), 1.0E-5, "half of the 50 % loss at half progress");
    }

    @Test
    void positionIsCorrectedWithItsOwnRetention() {
        WeaponPose result = AdsConstraint.apply(pose(), model(MAIN), ENABLED, 1.0);

        // ads_offset position (0.2, 0.5, 2.0) -> retention (0.2, 0.5, 1).
        // main position (10, -20, 30) -> (2, -10, 30).
        SampledBone main = result.bone("main");
        assertEquals(2.0F, main.position().x(), 1.0E-4);
        assertEquals(-10.0F, main.position().y(), 1.0E-4);
        assertEquals(30.0F, main.position().z(), 1.0E-4);
    }

    @Test
    void theCameraUsesTheModifierAsARetentionOnAllSixAxes() {
        WeaponDefinition.AdsConstraint modifier =
                new WeaponDefinition.AdsConstraint(true, true, 0.7);

        WeaponPose result = AdsConstraint.apply(pose(), model(MAIN), modifier, 1.0);

        // 0.7 retention means 30 % removed. Camera rotation (10, -20, 40) -> (7, -14, 28).
        SampledBone camera = result.bone("camera");
        assertEquals(7.0F, camera.rotation().x(), 1.0E-5);
        assertEquals(-14.0F, camera.rotation().y(), 1.0E-5);
        assertEquals(28.0F, camera.rotation().z(), 1.0E-5);
        // Camera position (1, 2, 3) -> (0.7, 1.4, 2.1).
        assertEquals(0.7F, camera.position().x(), 1.0E-5);
        assertEquals(1.4F, camera.position().y(), 1.0E-5);
        assertEquals(2.1F, camera.position().z(), 1.0E-5);
    }

    @Test
    void withoutAModifierTheCameraUsesTheAdsOffsetRetention() {
        SampledBone camera = AdsConstraint.apply(pose(), model(MAIN), ENABLED, 1.0).bone("camera");

        // Same retention as the main bone: (1, 1, 0.5) for rotation and (0.2, 0.5, 1) for position.
        assertEquals(10.0F, camera.rotation().x(), 1.0E-5);
        assertEquals(-20.0F, camera.rotation().y(), 1.0E-5);
        assertEquals(20.0F, camera.rotation().z(), 1.0E-5);
        assertEquals(0.2F, camera.position().x(), 1.0E-5);
        assertEquals(1.0F, camera.position().y(), 1.0E-5);
        assertEquals(3.0F, camera.position().z(), 1.0E-5);
    }

    @Test
    void cameraCorrectionCanBeDisabledIndependently() {
        WeaponDefinition.AdsConstraint noCamera = new WeaponDefinition.AdsConstraint(true, false, 0.2);
        WeaponPose result = AdsConstraint.apply(pose(), model(MAIN), noCamera, 1.0);

        assertEquals(10.0F, result.bone("camera").rotation().x(), 1.0E-5);
        assertEquals(10.0F, result.bone("main").rotation().x(), 1.0E-5, "main still uses the ads_offset values");
    }

    @Test
    void theCameraModifierStillAppliesWhenTheAdsOffsetBoneIsMissing() {
        WeaponModel model = new WeaponModel(new ResourceId("test", "model"), "geometry.test", "1.8.0", 64, 64,
                List.of(MAIN, CAMERA), List.of(MAIN, CAMERA), 0, 0);
        WeaponDefinition.AdsConstraint modifier = new WeaponDefinition.AdsConstraint(true, true, 0.7);

        WeaponPose result = AdsConstraint.apply(pose(), model, modifier, 1.0);

        assertEquals(7.0F, result.bone("camera").rotation().x(), 1.0E-5,
                "a declared camera retention is independent of the ads_offset bone");
        assertEquals(10.0F, result.bone("main").rotation().x(), 1.0E-5,
                "without ads_offset the main pose has no retention to apply");
    }

    @Test
    void aMissingMainBoneStillAllowsTheCameraToUseTheOffset() {
        WeaponModel model = new WeaponModel(new ResourceId("test", "model"), "geometry.test", "1.8.0", 64, 64,
                List.of(ADS_OFFSET, CAMERA), List.of(ADS_OFFSET, CAMERA), 0, 0);

        WeaponPose result = AdsConstraint.apply(pose(), model, ENABLED, 1.0);

        assertEquals(20.0F, result.bone("camera").rotation().z(), 1.0E-5);
    }

    @Test
    void aModelWithoutTheAdsOffsetBoneIsLeftAlone() {
        WeaponPose pose = pose();
        WeaponModel model = new WeaponModel(new ResourceId("test", "model"), "geometry.test", "1.8.0", 64, 64,
                List.of(MAIN, CAMERA), List.of(MAIN, CAMERA), 0, 0);

        assertSame(pose, AdsConstraint.apply(pose, model, ENABLED, 1.0));
    }

    @Test
    void aModelWithoutAnyMainOrRootBoneCorrectsOnlyTheCamera() {
        WeaponPose pose = pose();
        WeaponModel model = new WeaponModel(new ResourceId("test", "model"), "geometry.test", "1.8.0", 64, 64,
                List.of(ADS_OFFSET, CAMERA), List.of(ADS_OFFSET, CAMERA), 0, 0);

        assertNull(AdsConstraint.mainBoneName(model));
        WeaponPose result = AdsConstraint.apply(pose, model, ENABLED, 1.0);
        assertEquals(20.0F, result.bone("camera").rotation().z(), 1.0E-5,
                "the camera can still use the ads_offset retention without a main bone");
    }

    @Test
    void rootIsAcceptedAsTheMainBoneWhenAModelHasNoExplicitMain() {
        WeaponModel model = new WeaponModel(new ResourceId("test", "model"), "geometry.test", "1.8.0", 64, 64,
                List.of(ROOT, ADS_OFFSET, CAMERA), List.of(ROOT, ADS_OFFSET, CAMERA), 0, 0);

        assertEquals("root", AdsConstraint.mainBoneName(model));
        SampledBone root = AdsConstraint.apply(pose(), model, ENABLED, 1.0).bone("root");
        assertEquals(15.0F, root.rotation().z(), 1.0E-5);
    }

    @Test
    void anAuthoredOffsetRotationEncodesRetentionEvenWithoutAKeyframe() {
        ModelBone authored = new ModelBone("ads_offset", null, Vec3.ZERO, new Vec3(0.25F, 0.5F, 1.0F),
                List.of(), List.of());
        WeaponModel model = new WeaponModel(new ResourceId("test", "model"), "geometry.test", "1.8.0", 64, 64,
                List.of(MAIN, authored, CAMERA), List.of(MAIN, authored, CAMERA), 0, 0);

        WeaponPose pose = new WeaponPose("anim.test", 0.0, Map.of(
                "main", new SampledBone("main", new Vec3(10.0F, 20.0F, 30.0F), null, null),
                "ads_offset", new SampledBone("ads_offset", Vec3.ZERO, null, null)), List.of());

        SampledBone main = AdsConstraint.apply(pose, model, ENABLED, 1.0).bone("main");
        assertEquals(2.5F, main.rotation().x(), 1.0E-5);
        assertEquals(10.0F, main.rotation().y(), 1.0E-5);
        assertEquals(30.0F, main.rotation().z(), 1.0E-5);
    }

    @Test
    void aMissingOffsetPoseUsesZeroAnimatedValuesButKeepsAuthoredRetention() {
        WeaponModel model = model(MAIN);
        WeaponPose pose = new WeaponPose("anim.test", 0.0,
                Map.of("main", new SampledBone("main", new Vec3(10.0F, 20.0F, 30.0F), null, null)), List.of());

        // ADS_OFFSET authored rotation is (2,4,0.5) so retention is (1,1,0.5), and its position is zero.
        SampledBone main = AdsConstraint.apply(pose, model, ENABLED, 1.0).bone("main");
        assertEquals(15.0F, main.rotation().z(), 1.0E-5);
    }

    // ---------------------------------------------------------------- fixtures

    private static WeaponPose pose() {
        return new WeaponPose("anim.test", 0.0, Map.of(
                "main", new SampledBone("main", new Vec3(10.0F, 20.0F, 30.0F),
                        new Vec3(10.0F, -20.0F, 30.0F), null),
                "root", new SampledBone("root", new Vec3(10.0F, 20.0F, 30.0F),
                        new Vec3(10.0F, -20.0F, 30.0F), null),
                "ads_offset", new SampledBone("ads_offset", Vec3.ZERO,
                        new Vec3(0.2F, 0.5F, 2.0F), null),
                "camera", new SampledBone("camera", new Vec3(10.0F, -20.0F, 40.0F),
                        new Vec3(1.0F, 2.0F, 3.0F), null)), List.of());
    }

    private static WeaponModel model(ModelBone main) {
        List<ModelBone> bones = main == null
                ? List.of(ADS_OFFSET, CAMERA)
                : List.of(main, ADS_OFFSET, CAMERA);
        return new WeaponModel(new ResourceId("test", "model"), "geometry.test", "1.8.0", 64, 64, bones, bones, 0, 0);
    }

    private static ModelBone bone(String name, Vec3 rotation) {
        return new ModelBone(name, null, Vec3.ZERO, rotation, List.of(), List.of());
    }
}
