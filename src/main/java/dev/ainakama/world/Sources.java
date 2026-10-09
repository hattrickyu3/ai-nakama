package dev.ainakama.world;

import dev.ainakama.core.Items;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「このアイテムはどのブロックを掘れば手に入るか」の表。
 * プレイヤーの建物を壊さないように、丸石は自然の石（stone）からだけ取る。
 */
public final class Sources {
	private static final Map<String, Set<String>> BLOCKS = Map.of(
		"cobblestone", Set.of("stone"),
		"coal", Set.of("coal_ore", "deepslate_coal_ore"),
		"raw_iron", Set.of("iron_ore", "deepslate_iron_ore"),
		"dirt", Set.of("dirt", "grass_block"),
		"sand", Set.of("sand"),
		"gravel", Set.of("gravel"),
		"clay_ball", Set.of("clay"));

	/** そのブロックを掘るのに最低限ほしい道具。表にないものは道具なしでよい。 */
	private static final Map<String, String> TOOL = Map.of(
		"stone", "wooden_pickaxe",
		"coal_ore", "wooden_pickaxe",
		"deepslate_coal_ore", "wooden_pickaxe",
		"iron_ore", "stone_pickaxe",
		"deepslate_iron_ore", "stone_pickaxe");

	private Sources() {
	}

	public static Predicate<BlockState> matcher(String item) {
		if (Items.isLog(item)) {
			return s -> s.is(BlockTags.LOGS) && !BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().startsWith("stripped_");
		}
		Set<String> ids = BLOCKS.getOrDefault(item, Set.of(item));
		return s -> ids.contains(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath());
	}

	private static final int LEAVES_SEARCH_RADIUS = 2;
	private static final int LEAVES_SEARCH_UP = 7;

	/**
	 * 自然に生えた木の原木か。近く（主に上のほう）に、自然にできた葉っぱ（PERSISTENT=false）があれば木とみなす。
	 * プレイヤーが原木で作った家は、自然の葉っぱとくっついていないので切らずにすむ。
	 */
	public static boolean isNaturalLog(Level level, BlockPos pos) {
		for (BlockPos p : BlockPos.betweenClosed(
			pos.offset(-LEAVES_SEARCH_RADIUS, -1, -LEAVES_SEARCH_RADIUS),
			pos.offset(LEAVES_SEARCH_RADIUS, LEAVES_SEARCH_UP, LEAVES_SEARCH_RADIUS))) {
			BlockState s = level.getBlockState(p);
			if (s.is(BlockTags.LEAVES) && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) {
				return true;
			}
		}
		return false;
	}

	/** 掘るのに必要な道具（作るべきもの）。いらなければ null。 */
	public static String toolFor(BlockState state) {
		return TOOL.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
	}
}
