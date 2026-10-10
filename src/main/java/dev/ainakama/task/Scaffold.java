package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Terrain;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 高いところに手が届くように、自分の足元に土や丸石を積んで登る足場。
 * 作業が終わったら {@link #removeAll} で片付けて、積んだブロックを持ち物に戻す。
 */
final class Scaffold {
	/** 足場に使うもの。落ちてこない、ありふれたブロックだけ。 */
	static final List<String> FILLERS = List.of("dirt", "cobblestone", "cobbled_deepslate", "netherrack", "stone");
	private static final int MAX_HEIGHT = 24;

	private final Deque<BlockPos> placed = new ArrayDeque<>();

	static boolean hasFiller(NakamaEntity nakama) {
		return fillerSlot(nakama) >= 0;
	}

	static int fillerSlot(NakamaEntity nakama) {
		for (String id : FILLERS) {
			int slot = Inv.findSlot(nakama.getInventory(), id);
			if (slot >= 0) {
				return slot;
			}
		}
		return -1;
	}

	int height() {
		return placed.size();
	}

	/**
	 * 足元に1つ積んで、1段上がる。
	 *
	 * @return 上がれたら true。材料がない・頭の上がふさがっている・高すぎるなら false
	 */
	boolean raise(NakamaEntity nakama, ServerLevel level) {
		int slot = fillerSlot(nakama);
		BlockPos feet = nakama.blockPosition();
		if (slot < 0 || placed.size() >= MAX_HEIGHT || !Terrain.isOpen(level.getBlockState(feet.above(2)))) {
			return false;
		}
		ItemStack stack = nakama.getInventory().getItem(slot);
		if (!(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		BlockState state = blockItem.getBlock().defaultBlockState();
		nakama.getNavigation().stop();
		nakama.snapTo(feet.getX() + 0.5, feet.getY() + 1, feet.getZ() + 0.5, nakama.getYRot(), nakama.getXRot());
		if (!level.setBlock(feet, state, Block.UPDATE_ALL)) {
			return false;
		}
		level.playSound(null, feet, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
		stack.shrink(1);
		nakama.getInventory().setChanged();
		placed.push(feet);
		return true;
	}

	/** 積んだ足場を上から全部取りのぞき、持ち物に戻す。 */
	void removeAll(NakamaEntity nakama, ServerLevel level) {
		while (!placed.isEmpty()) {
			BlockPos pos = placed.pop();
			BlockState state = level.getBlockState(pos);
			if (FILLERS.contains(Terrain.idOf(state))) {
				level.destroyBlock(pos, false, nakama);
				Breaker.stash(nakama, level, List.of(new ItemStack(state.getBlock().asItem())));
			}
		}
	}
}
