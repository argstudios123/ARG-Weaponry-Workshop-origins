package com.arg_studios.arg_weaponry.weapon.model;

/**
 * One geometry block, resolved into a renderable mesh, plus the counters of that call.
 *
 * <p>This is the only value the two geometry layers hand back, and it is deliberately small: the parser owns the
 * model-wide totals, so a layer reports what it produced instead of accumulating document state.</p>
 *
 * @param mesh                the triangles of the block, in model space
 * @param cubes               how many cubes of a {@code cubes} array produced geometry; {@code 0} for a poly mesh
 * @param degenerateTriangles how many emitted triangles had no area
 */
record GeometryBlock(BoneMesh mesh, int cubes, int degenerateTriangles) {

    GeometryBlock {
        if (mesh == null) {
            throw new IllegalArgumentException("A geometry block always has a mesh");
        }
    }
}
