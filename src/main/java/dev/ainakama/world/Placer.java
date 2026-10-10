package dev.ainakama.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Optional;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ブロックを置く。扉の上半分や松明の向きのような「置き方の決まり」は、マイクラ本体の置く処理に任せる。
 */
public final class Placer {
	private Placer() {
	}

	/**
	 * 持ち物のアイテムを pos に置く。置けたら stack を1つ減らす。
	 *
	 * @param facing 置く人が向いている水平の向き（扉や階段の向きが決まる）
	 * @return 置けたら true
	 */
	public static boolean place(ServerLevel level, BlockPos pos, ItemStack stack, Direction facing) {
		if (!(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		int before = stack.getCount();
		var result = blockItem.place(new DirectionalPlaceContext(level, pos, facing, stack, Direction.UP));
		if (!result.consumesAction()) {
			return false;
		}
		// 置く処理が数を減らさない場合に備えて、ちょうど1つ減った状態にそろえる
		stack.setCount(before - 1);
		return true;
	}

	/** 覚えた設計図どおりの向き・形で置く。 */
	public static boolean placeExact(ServerLevel level, BlockPos pos, BlockState state) {
		if (!level.setBlock(pos, state, Block.UPDATE_ALL)) {
			return false;
		}
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
		return true;
	}

	/** ブロックの状態を "minecraft:oak_door[facing=north,half=lower]" のような文字にする。 */
	public static String write(BlockState state) {
		return BlockStateParser.serialize(state);
	}

	public static Optional<BlockState> read(ServerLevel level, String text) {
		try {
			return Optional.of(BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), text, false).blockState());
		} catch (CommandSyntaxException e) {
			return Optional.empty();
		}
	}
}
