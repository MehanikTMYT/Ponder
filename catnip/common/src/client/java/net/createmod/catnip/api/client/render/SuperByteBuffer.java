package net.createmod.catnip.api.client.render;

import net.minecraft.world.level.BlockAndLightGetter;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.createmod.catnip.api.theme.Color;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3fc;
import com.mojang.math.Axis;

public interface SuperByteBuffer {
	static int maxLight(int packedLight1, int packedLight2) {
		int blockLight1 = LightCoordsUtil.block(packedLight1);
		int skyLight1 = LightCoordsUtil.sky(packedLight1);
		int blockLight2 = LightCoordsUtil.block(packedLight2);
		int skyLight2 = LightCoordsUtil.sky(packedLight2);
		return LightCoordsUtil.pack(Math.max(blockLight1, blockLight2), Math.max(skyLight1, skyLight2));
	}

	void renderInto(PoseStack ms, VertexConsumer consumer);

	boolean isEmpty();

	PoseStack getTransforms();

	<Self extends SuperByteBuffer> Self reset();

	<Self extends SuperByteBuffer> Self color(int color);

	<Self extends SuperByteBuffer> Self color(int r, int g, int b, int a);

	<Self extends SuperByteBuffer> Self disableDiffuse();

	<Self extends SuperByteBuffer> Self shiftUV(SpriteShiftEntry entry);

	<Self extends SuperByteBuffer> Self shiftUVScrolling(SpriteShiftEntry entry, float scrollU, float scrollV);

	<Self extends SuperByteBuffer> Self shiftUVtoSheet(SpriteShiftEntry entry, float uTarget, float vTarget, int sheetSize);

	<Self extends SuperByteBuffer> Self overlay(int overlay);

	<Self extends SuperByteBuffer> Self light(int packedLight);

	/**
	 * Indicate that this buffer should look up the light coordinates in the level.
	 *
	 * Takes the light half of the level contract rather than the tint half: the lookup is
	 * {@link net.minecraft.client.renderer.LevelRenderer#getLightCoords}, which asks for a
	 * {@code BlockAndLightGetter}. Narrowing it to {@code BlockAndTintGetter} would exclude a plain
	 * {@code Level}, which since 26.1 is a light getter but not a tint getter.
	 */
	<Self extends SuperByteBuffer> Self useLevelLight(BlockAndLightGetter level);

	/**
	 * Indicate that this buffer should look up the light coordinates in the level.
	 * Light Positions will be transformed by the passed Matrix before the lookup.
	 */
	<Self extends SuperByteBuffer> Self useLevelLight(BlockAndLightGetter level, Matrix4f lightTransform);

	//

	default void delete() {
	}

	default <Self extends SuperByteBuffer> Self color(Color color) {
		return this.color(
			color.getRed(),
			color.getGreen(),
			color.getBlue(),
			color.getAlpha()
		);
	}

	default <Self extends SuperByteBuffer> Self shiftUVScrolling(SpriteShiftEntry entry, float scrollV) {
		return this.shiftUVScrolling(entry, 0, scrollV);
	}

	@FunctionalInterface
	interface SpriteShiftFunc {
		void shift(float u, float v, Output output);

		interface Output {
			void accept(float u, float v);
		}
	}

	class ShiftOutput implements SpriteShiftFunc.Output {
		public float u;
		public float v;

		@Override
		public void accept(float u, float v) {
			this.u = u;
			this.v = v;
		}
	}

	// ─── transform algebra ───
	//
	// Before 26.1 these arrived by inheritance: SuperByteBuffer extended Flywheel's Transform, and
	// translate/rotate/scale/centered-rotation came from Affine as default methods. Catnip 26.1 dropped
	// that supertype, and it had to: catnip/common declares no Flywheel dependency at all, so the
	// interface cannot name Flywheel types. The operations went with it even though the state did not -
	// every implementation still keeps the PoseStack that getTransforms() exposes.
	//
	// So the algebra is restored here, over that stack, with Flywheel's exact semantics: CENTER is the
	// block centre (0.5), rotate takes radians, the Degrees variants take degrees, and centred rotation
	// pivots on the block centre rather than the origin.
	//
	// None of the matrix work is written by hand. PoseStack.Pose already maintains the normal matrix
	// correctly for each operation - rotating it for rotations, and recomputing it by inverse-transpose
	// when a non-uniform scale or a non-orthogonal matrix makes the shortcut invalid. Re-deriving that
	// here would be a second implementation to keep right.

	/** The block centre, and the pivot used by every centred rotation. */
	float CENTER = 0.5f;

	default SuperByteBuffer translate(float x, float y, float z) {
		getTransforms().translate(x, y, z);
		return this;
	}

	default SuperByteBuffer translate(double x, double y, double z) {
		return translate((float) x, (float) y, (float) z);
	}

	default SuperByteBuffer translate(float v) {
		return translate(v, v, v);
	}

	default SuperByteBuffer translateX(float x) {
		return translate(x, 0, 0);
	}

	default SuperByteBuffer translateY(float y) {
		return translate(0, y, 0);
	}

	default SuperByteBuffer translateZ(float z) {
		return translate(0, 0, z);
	}

	default SuperByteBuffer translate(Vec3i vec) {
		return translate(vec.getX(), vec.getY(), vec.getZ());
	}

	default SuperByteBuffer translate(Vec3 vec) {
		return translate(vec.x, vec.y, vec.z);
	}

	default SuperByteBuffer translate(Vector3fc vec) {
		return translate(vec.x(), vec.y(), vec.z());
	}

	default SuperByteBuffer translateBack(float x, float y, float z) {
		return translate(-x, -y, -z);
	}

	default SuperByteBuffer translateBack(double x, double y, double z) {
		return translate(-x, -y, -z);
	}

	default SuperByteBuffer translateBack(Vec3i vec) {
		return translateBack(vec.getX(), vec.getY(), vec.getZ());
	}

	default SuperByteBuffer translateBack(Vec3 vec) {
		return translateBack(vec.x, vec.y, vec.z);
	}

	/** Moves to the block centre, so a following rotation turns about it. */
	default SuperByteBuffer center() {
		return translate(CENTER);
	}

	/** The inverse of {@link #center()}. */
	default SuperByteBuffer uncenter() {
		return translate(-CENTER);
	}

	default SuperByteBuffer rotate(Quaternionfc quaternion) {
		getTransforms().mulPose(quaternion);
		return this;
	}

	default SuperByteBuffer rotate(float radians, float axisX, float axisY, float axisZ) {
		if (radians == 0)
			return this;
		return rotate(new Quaternionf().setAngleAxis(radians, axisX, axisY, axisZ));
	}

	default SuperByteBuffer rotate(float radians, Direction axis) {
		return rotate(radians, axis.getStepX(), axis.getStepY(), axis.getStepZ());
	}

	default SuperByteBuffer rotate(float radians, Direction.Axis axis) {
		return rotate(radians, Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE));
	}

	default SuperByteBuffer rotateX(float radians) {
		return rotate(radians, 1, 0, 0);
	}

	default SuperByteBuffer rotateY(float radians) {
		return rotate(radians, 0, 1, 0);
	}

	default SuperByteBuffer rotateZ(float radians) {
		return rotate(radians, 0, 0, 1);
	}

	default SuperByteBuffer rotateXDegrees(float degrees) {
		return rotateX(Mth.DEG_TO_RAD * degrees);
	}

	default SuperByteBuffer rotateYDegrees(float degrees) {
		return rotateY(Mth.DEG_TO_RAD * degrees);
	}

	default SuperByteBuffer rotateZDegrees(float degrees) {
		return rotateZ(Mth.DEG_TO_RAD * degrees);
	}

	default SuperByteBuffer rotateAround(Quaternionfc quaternion, float x, float y, float z) {
		getTransforms().rotateAround(quaternion, x, y, z);
		return this;
	}

	default SuperByteBuffer rotateCentered(Quaternionfc quaternion) {
		return rotateAround(quaternion, CENTER, CENTER, CENTER);
	}

	default SuperByteBuffer rotateCentered(float radians, float axisX, float axisY, float axisZ) {
		if (radians == 0)
			return this;
		return rotateCentered(new Quaternionf().setAngleAxis(radians, axisX, axisY, axisZ));
	}

	default SuperByteBuffer rotateCentered(float radians, Direction axis) {
		return rotateCentered(radians, axis.getStepX(), axis.getStepY(), axis.getStepZ());
	}

	default SuperByteBuffer rotateCentered(float radians, Direction.Axis axis) {
		return rotateCentered(radians, Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE));
	}

	default SuperByteBuffer rotateCenteredDegrees(float degrees, Direction axis) {
		return rotateCentered(Mth.DEG_TO_RAD * degrees, axis);
	}

	default SuperByteBuffer scale(float factorX, float factorY, float factorZ) {
		getTransforms().scale(factorX, factorY, factorZ);
		return this;
	}

	default SuperByteBuffer scale(float factor) {
		return scale(factor, factor, factor);
	}

	default SuperByteBuffer mulPose(Matrix4fc pose) {
		getTransforms().mulPose(pose);
		return this;
	}

	/** Applies another stack's current pose, position and normals together. */
	default SuperByteBuffer transform(PoseStack.Pose pose) {
		return mulPose(pose.pose());
	}

	default SuperByteBuffer transform(PoseStack stack) {
		return transform(stack.last());
	}

	/**
	 * Offsets this object randomly by a fraction of a pixel, deterministically in the seed.
	 *
	 * Identical blocks drawn at the same depth fight for the depth buffer; a stable sub-pixel offset
	 * breaks the tie without being visible. The constants are the ones Create has always used - the same
	 * ones {@code PackageVisual} still computes inline.
	 */
	default SuperByteBuffer nudge(int seed) {
		long randomBits = (long) seed * 31L * 493286711L;
		randomBits = randomBits * randomBits * 4392167121L + randomBits * 98761L;
		float xNudge = (((float) (randomBits >> 16 & 7L) + 0.5F) / 8.0F - 0.5F) * 0.004F;
		float yNudge = (((float) (randomBits >> 20 & 7L) + 0.5F) / 8.0F - 0.5F) * 0.004F;
		float zNudge = (((float) (randomBits >> 24 & 7L) + 0.5F) / 8.0F - 0.5F) * 0.004F;
		return translate(xNudge, yNudge, zNudge);
	}

	default SuperByteBuffer rotateXCentered(float radians) {
		return rotateCentered(radians, Direction.EAST);
	}

	default SuperByteBuffer rotateYCentered(float radians) {
		return rotateCentered(radians, Direction.UP);
	}

	default SuperByteBuffer rotateZCentered(float radians) {
		return rotateCentered(radians, Direction.SOUTH);
	}

	default SuperByteBuffer rotateXCenteredDegrees(float degrees) {
		return rotateXCentered(Mth.DEG_TO_RAD * degrees);
	}

	default SuperByteBuffer rotateYCenteredDegrees(float degrees) {
		return rotateYCentered(Mth.DEG_TO_RAD * degrees);
	}

	default SuperByteBuffer rotateZCenteredDegrees(float degrees) {
		return rotateZCentered(Mth.DEG_TO_RAD * degrees);
	}

	/**
	 * Multiplies only the normal matrix.
	 *
	 * Separate from {@link #mulPose} because a caller that has already applied a transform to positions
	 * by other means still needs the normals to follow it; the pose is left alone on purpose.
	 */
	default SuperByteBuffer mulNormal(Matrix3fc normal) {
		getTransforms().last()
			.normal()
			.mul(normal);
		return this;
	}

	// com.mojang.math.Axis is a third axis spelling, distinct from Direction and Direction.Axis: it is a
	// quaternion factory rather than a direction, so it supplies the rotation itself.
	default SuperByteBuffer rotate(float radians, Axis axis) {
		if (radians == 0)
			return this;
		return rotate(axis.rotation(radians));
	}

	default SuperByteBuffer rotateCentered(float radians, Axis axis) {
		if (radians == 0)
			return this;
		return rotateCentered(axis.rotation(radians));
	}

	default SuperByteBuffer rotateDegrees(float degrees, Axis axis) {
		return rotate(Mth.DEG_TO_RAD * degrees, axis);
	}

	default SuperByteBuffer rotateCenteredDegrees(float degrees, Axis axis) {
		return rotateCentered(Mth.DEG_TO_RAD * degrees, axis);
	}
}
