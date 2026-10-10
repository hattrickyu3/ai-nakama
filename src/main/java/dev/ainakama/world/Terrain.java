package dev.ainakama.world;

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地形を調べる道具。「掘ってよい自然のブロックか」「立てる場所か」「地面の高さ」など。
 * 仲間が掘ってよいのは、ここで自然のものと判断したブロックだけ（プレイヤーの建物を壊さないため）。
 */
public final class Terrain {
	/** 自然にある、掘ってよい地面のブロック。 */
	private static final Set<String> GROUND = Set.of(
		"dirt", "grass_block", "coarse_dirt", "podzol", "rooted_dirt", "mud", "mycelium", "dirt_path",
		"sand", "red_sand", "gravel", "clay", "snow_block", "snow", "sandstone", "red_sandstone",
		"stone", "andesite", "diorite", "granite", "deepslate", "tuff", "calcite", "dripstone_block",
		"coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore", "copper_ore", "deepslate_copper_ore",
		"terracotta", "moss_block", "netherrack");

	private Terrain() {
	}

	public static String idOf(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}

	/** 自然の地面（土・石・砂など）か。 */
	public static boolean isGround(BlockState state) {
		return GROUND.contains(idOf(state));
	}

	/**
	 * 仲間が片付けてよい自然のものか：地面、草花などの置き換えられるもの、葉っぱ、葉っぱのついた木。
	 * 板材やガラスなど、プレイヤーが置いたとわかるものは false。
	 */
	public static boolean isNaturalClearable(Level level, BlockPos pos, BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return false;
		}
		if (isGround(state) || state.canBeReplaced() || state.is(BlockTags.LEAVES)) {
			return true;
		}
		return state.is(BlockTags.LOGS) && Sources.isNaturalLog(level, pos);
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
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(target.offset(-3, -3, -3), target.offset(3, 3, 3))) {
			double d = p.distSqr(target);
			if (d < bestDist && canStandAt(level, p)) {
				best = p.immutable();
				bestDist = d;
			}
		}
		return Optional.ofNullable(best);
	}
}
