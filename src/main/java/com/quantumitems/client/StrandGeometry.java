package com.quantumitems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;

/**
 * An untextured glowing tube threaded through a path of nodes — the one piece
 * of geometry every "strand" in the mod is made of, whether it is a ritual beam
 * between a resonator and the core or the knot that lives inside the shard item.
 *
 * <p>The rings are SHARED between neighbouring segments. Drawing one cuboid per
 * segment leaves each segment's end faces meeting the next at an angle, so a
 * corner sticks out at every node: invisible on a fast thin beam, very obvious
 * on a slow fat one you can stand beside. Emitting every ring once and letting
 * both adjacent segments use it makes the surface continuous by construction.
 *
 * <p>The ring frame is carried along the path (parallel transport) rather than
 * rebuilt from world-up at each node — rebuilding spins the tube around its own
 * axis wherever the direction changes.
 */
public final class StrandGeometry {

    private StrandGeometry() {
    }

    /** Where a surface point's colour comes from, when one flat colour will not do. */
    @FunctionalInterface
    public interface Tint {
        /**
         * @param along  position along the path, 0 at the first node, 1 at the last
         * @param facing |normal · toCamera|, so 1 looking straight at the surface
         *               and 0 along the silhouette edge — the term real thin-film
         *               iridescence turns on
         * @return packed 0xRRGGBB, before the node's brightness is applied
         */
        int rgb(float along, float facing);
    }

    /** One flat colour the whole way along. */
    public static void tube(PoseStack poseStack, VertexConsumer consumer, Vec3[] pts,
                            float[] bright, int rgb, float radius, int sides, boolean closed) {
        tube(poseStack, consumer, pts, bright, (along, facing) -> rgb, null, radius, sides, closed);
    }

    /**
     * @param pts       node positions; for a closed path {@code pts[last]} must
     *                  be the same point as {@code pts[0]}
     * @param bright    per-node multiplier applied to the tint
     * @param toCamera  unit vector from the surface toward the eye, in the same
     *                  space as {@code pts}; null feeds the tint a flat 1
     * @param sides     faces around the tube — four reads chunky, eight is round,
     *                  six sits between; drop to five or four for tiny renders
     * @param closed    whether the path is a loop, which changes how the end
     *                  tangents are found and closes the frame (see below)
     */
    public static void tube(PoseStack poseStack, VertexConsumer consumer, Vec3[] pts,
                            float[] bright, Tint tint, Vec3 toCamera,
                            float radius, int sides, boolean closed) {
        int n = pts.length - 1;
        PoseStack.Pose last = poseStack.last();

        Vec3[] tangent = new Vec3[n + 1];
        if (closed) {
            for (int i = 0; i <= n; i++) {
                tangent[i] = pts[(i + 1) % n].subtract(pts[(i - 1 + n) % n]).normalize();
            }
        } else {
            tangent[0] = pts[1].subtract(pts[0]).normalize();
            tangent[n] = pts[n].subtract(pts[n - 1]).normalize();
            for (int i = 1; i < n; i++) {
                tangent[i] = pts[i + 1].subtract(pts[i - 1]).normalize();
            }
        }

        Vec3[] u = new Vec3[n + 1];
        Vec3 seed = Math.abs(tangent[0].y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 carried = tangent[0].cross(seed).normalize();
        for (int i = 0; i <= n; i++) {
            // parallel transport: drop the component along the new tangent
            carried = carried.subtract(tangent[i].scale(carried.dot(tangent[i])));
            if (carried.lengthSqr() < 1.0e-8) {
                carried = tangent[i].cross(new Vec3(0, 1, 0));
            }
            u[i] = carried = carried.normalize();
        }

        // Parallel transport around a LOOP does not generally come back to where
        // it started — the frame arrives rotated by some holonomy angle, and the
        // closing segment would be visibly pinched. Measure that angle once and
        // unwind it evenly along the path, so the last ring lands on the first.
        double twist = 0;
        if (closed) {
            twist = Math.atan2(tangent[0].dot(u[n].cross(u[0])), u[n].dot(u[0]));
        }

        // Colour is resolved once per ring vertex rather than once per quad
        // corner: every vertex is shared by four corners, and the tint may be
        // doing real work (a hue ramp, a dot product) for each one.
        Vec3[][] ring = new Vec3[n + 1][sides];
        Vec3[][] normals = new Vec3[n + 1][sides];
        int[][] color = new int[n + 1][sides];
        for (int i = 0; i <= n; i++) {
            Vec3 ui = u[i];
            if (twist != 0) {
                double a = twist * i / n;   // Rodrigues; the t·u term drops out, u ⟂ t
                ui = ui.scale(Math.cos(a)).add(tangent[i].cross(ui).scale(Math.sin(a)));
            }
            Vec3 vi = tangent[i].cross(ui).normalize();
            float along = i / (float) n;
            for (int k = 0; k < sides; k++) {
                double a = 2 * Math.PI * k / sides;
                Vec3 normal = ui.scale(Math.cos(a)).add(vi.scale(Math.sin(a)));
                normals[i][k] = normal;
                ring[i][k] = pts[i].add(normal.scale(radius));
                float facing = toCamera == null ? 1.0f
                        : (float) Math.abs(normal.dot(toCamera));
                color[i][k] = tint.rgb(along, facing);
            }
        }

        for (int i = 0; i < n; i++) {
            for (int k = 0; k < sides; k++) {
                int k2 = (k + 1) % sides;
                emit(consumer, last, ring[i][k], normals[i][k], color[i][k], bright[i]);
                emit(consumer, last, ring[i][k2], normals[i][k2], color[i][k2], bright[i]);
                emit(consumer, last, ring[i + 1][k2], normals[i + 1][k2], color[i + 1][k2], bright[i + 1]);
                emit(consumer, last, ring[i + 1][k], normals[i + 1][k], color[i + 1][k], bright[i + 1]);
            }
        }
    }

    /**
     * The strand's texture is one white texel, so every vertex samples the
     * middle of it and the colour survives untouched (see
     * {@link QuantumRenderTypes#RITUAL_BEAM} for why there is a texture at all).
     */
    private static final float U = 0.5f;
    private static final float V = 0.5f;

    private static void emit(VertexConsumer consumer, PoseStack.Pose pose, Vec3 p, Vec3 normal,
                             int rgb, float bright) {
        consumer.addVertex(pose.pose(), (float) p.x, (float) p.y, (float) p.z)
                .setColor((int) (((rgb >> 16) & 0xFF) * bright),
                        (int) (((rgb >> 8) & 0xFF) * bright),
                        (int) ((rgb & 0xFF) * bright), 255)
                .setUv(U, V)
                // The strand emits; it is never lit. Vanilla's beacon shader
                // ignores this outright, and a shader pack reads it as "as
                // bright as this can get", which is what we want either way.
                .setLight(LightTexture.FULL_BRIGHT)
                // Unused by vanilla too, but a pack writes it into the gbuffer
                // and lights the scene off it, so it had better be the real
                // surface normal rather than a placeholder.
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
