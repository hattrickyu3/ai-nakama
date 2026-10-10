package dev.ainakama.world;

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地形を調べる道具。「掘ってよい自然のブロックか」「立てる場所か」「地面の高さ」など。
 * 仲間が掘ってよいのは、ここで自然のものと判断したブロックだけ（プレイヤーの建物を壊さないため）。
 */
public final class Terrain {
	/** 自然にある、やわらかい地面。 */
	private static final Set<String> SOFT_GROUND = Set.of(
		"dirt", "grass_block", "coarse_dirt", "podzol", "rooted_dirt", "mud", "mycelium",
		"sand", "red_sand", "gravel", "clay", "snow");
	/**
	 * 自然にある、かたい地面。家の材料としてもよく使われるので、地面より高く積んであるものは建物かもしれないと考える。
	 * 砂岩・テラコッタ・苔ブロックなど、飾りに使われやすいものは入れていない（壊さない）。
	 * 鉄や銅の鉱石は石のツルハシが要るので入れていない（掘り下げるときはよけて通る）。
	 */
	private static final Set<String> HARD_GROUND = Set.of(
		"stone", "andesite", "diorite", "granite", "deepslate", "tuff", "calcite", "dripstone_block",
		"coal_ore", "deepslate_coal_ore", "netherrack");

	/** 床からこの高さ以上に積んである石は、建物の壁かもしれないとみなす。 */
	public static final int WALL_HEIGHT = 2;

	private Terrain() {
	}

	public static String idOf(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}

	/** 自然の地面（土・石・砂など）か。 */
	public static boolean isGround(BlockState state) {
		String id = idOf(state);
		return SOFT_GROUND.contains(id) || HARD_GROUND.contains(id);
	}

	/** 石などのかたい地面か。 */
	public static boolean isHardGround(BlockState state) {
		return HARD_GROUND.contains(idOf(state));
	}

	/**
	 * 地形に埋まっている石か：下と、横4方向のうち3つ以上がふさがっている。
	 * 厚さ1マスの壁（横は2方向しかふさがっていない）を、資源として掘らないための目安。
	 */
	public static boolean isEmbedded(Level level, BlockPos pos) {
		if (isOpen(level.getBlockState(pos.below()))) {
			return false;
		}
		int solid = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (!isOpen(level.getBlockState(pos.relative(d)))) {
				solid++;
			}
		}
		return solid >= 3;
	}

	/**
	 * 仲間が片付けてよい自然のものか：地面、草花などの置き換えられるもの、葉っぱ、葉っぱのついた木。
	 * 板材やガラスなど、プレイヤーが置いたとわかるものは false。
	 */
	public static boolean isNaturalClearable(Level level, BlockPos pos, BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return false;
		}
		if (isGround(state) || state.canBeReplaced()) {
			return true;
		}
		if (state.is(BlockTags.LEAVES)) {
			// プレイヤーが置いた葉っぱ（生け垣や屋根）は PERSISTENT=true なので壊さない
			return state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
		}
		return state.is(BlockTags.LOGS) && Sources.isNaturalLog(level, pos);
	}

	/**
	 * 収穫してよい作物か：育ちきった小麦・ニンジン・ジャガイモ・ビートルートなど。
	 * 畑はプレイヤーが作ったものだが、育ちきった作物だけは、頼まれたときに収穫してよい（収穫したら植え直す）。
	 * 育っている途中のものは false。
	 */
	public static boolean isRipeCrop(BlockState state) {
		return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
	}

	/** 空気や草など、体が通れてブロックを置ける場所か（水や溶岩は含まない）。 */
	public static boolean isOpen(BlockState state) {
		return state.canBeReplaced() && state.getFluidState().isEmpty();
	}

	/** そのブロックのまわり6方向に水か溶岩があるか（掘ると流れ込んでくる）。 */
	public static boolean touchesFluid(Level level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (!level.getBlockState(pos.relative(d)).getFluidState().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** 6方向のどこかが開いていて、外から手が届くブロックか。 */
	public static boolean isExposed(Level level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (isOpen(level.getBlockState(pos.relative(d)))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * (x, z) の地面の高さ（立てる足元の y）。aroundY の少し上から下へ探す。木や葉っぱは地面に数えない。
	 */
	public static int surfaceY(Level level, int x, int z, int aroundY) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = aroundY + 8; y > aroundY - 12; y--) {
			p.set(x, y - 1, z);
			BlockState below = level.getBlockState(p);
			if (!below.canBeReplaced() && !below.is(BlockTags.LEAVES) && !below.is(BlockTags.LOGS)) {
				return y;
			}
		}
		return aroundY;
	}

	/** 足元が固く、体の高さ2マスが空いている場所か。 */
	public static boolean canStandAt(Level level, BlockPos feet) {
		BlockState below = level.getBlockState(feet.below());
		return !below.canBeReplaced() && below.getFluidState().isEmpty()
			&& isOpen(level.getBlockState(feet)) && isOpen(level.getBlockState(feet.above()));
	}

	/** target の近く（上下左右3マス以内）で立てる場所を探す。近い順。 */
	public static Optional<BlockPos> standableNear(Level level, BlockPos target) {
		return standableNear(level, target, false);
	}

	/**
	 * @param beside true なら、target と同じ縦の列（真上・真下・そのもの）は選ばない。
	 *               そのマスにブロックを置いたり掘ったりするとき、自分が邪魔にならないようにするため
	 */
	public static Optional<BlockPos> standableNear(Level level, BlockPos target, boolean beside) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(target.offset(-3, -3, -3), target.offset(3, 3, 3))) {
			if (beside && p.getX() == target.getX() && p.getZ() == target.getZ()) {
				continue;
			}
			double d = p.distSqr(target);
			if (d < bestDist && canStandAt(level, p)) {
				best = p.immutable();
				bestDist = d;
			}
		}
		return Optional.ofNullable(best);
	}
}
