package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Terrain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 高いところに手が届くように、自分の足元に土や丸石を積んで登る足場。
 * 足場にするものを持っていなければ、近くの地面の土を借りる（{@link #ensure}）。
 * 作業が終わったら {@link #removeAll} で片付けて、積んだブロックを持ち物に戻し、借りた土を埋め戻す。
 */
final class Scaffold {
	/** 足場に使うもの。落ちてこない、ありふれたブロックだけ。 */
	static final List<String> FILLERS = List.of("dirt", "cobblestone", "cobbled_deepslate", "netherrack", "stone");
	private static final int MAX_HEIGHT = 24;
	/** 土を借りる地面を探す広さ。 */
	private static final int BORROW_RADIUS = 6;
	private static final double BORROW_REACH = 4.0;
	private static final int BORROW_STUCK_TICKS = 80;
	/** 拠点のまわりでは、地面の土を借りない。 */
	private static final int HOME_PROTECT_RADIUS = 8;

	/** 足場にするものがそろっているか。 */
	enum Supply {
		/** そろっている。 */
		READY,
		/** 近くの地面から借りている途中。 */
		WORKING,
		/** 借りられる地面が近くにない。 */
		NONE
	}

	/** 積んだ場所と、そこに積んだブロックの種類。 */
	private record Placed(BlockPos pos, String block) {
	}

	private final Deque<Placed> placed = new ArrayDeque<>();
	/** 土を借りた場所（あとで埋め戻す）。 */
	private final List<BlockPos> borrowed = new ArrayList<>();
	private final Set<BlockPos> unreachable = new HashSet<>();
	private final Walker borrowWalker = new Walker(BORROW_STUCK_TICKS);
	private final Breaker borrowBreaker = new Breaker();
	private BlockPos borrowTarget;

	/**
	 * 足場に使えるものが入っている持ち物の位置。
	 *
	 * @param keep 足場に使ってはいけないもの（これから建物に使う材料）
	 */
	static int fillerSlot(NakamaEntity nakama, Set<String> keep) {
		for (String id : FILLERS) {
			if (keep.contains(id)) {
				continue;
			}
			int slot = Inv.findSlot(nakama.getInventory(), id);
			if (slot >= 0) {
				return slot;
			}
		}
		return -1;
	}

	/** 足場に使えるものを、全部でいくつ持っているか。 */
	static int fillerCount(NakamaEntity nakama, Set<String> keep) {
		return FILLERS.stream().filter(id -> !keep.contains(id)).mapToInt(id -> Inv.count(nakama.getInventory(), id)).sum();
	}

	/** そのマスに、自分が積んだ足場があるか。 */
	boolean contains(BlockPos pos) {
		return placed.stream().anyMatch(p -> p.pos().equals(pos));
	}

	boolean isEmpty() {
		return placed.isEmpty() && borrowed.isEmpty();
	}

	/**
	 * 足場にするものを need 個持っている状態にする。足りなければ、近くの地面の土を1つずつ掘って借りる。
	 * WORKING の間は、毎tick呼び続ける。
	 */
	Supply ensure(NakamaEntity nakama, ServerLevel level, int need, Set<String> keep) {
		if (fillerCount(nakama, keep) >= need) {
			borrowBreaker.cancel(nakama, level);
			borrowTarget = null;
			return Supply.READY;
		}
		if (borrowTarget == null || !isLooseGround(level, borrowTarget)) {
			borrowBreaker.cancel(nakama, level);
			borrowTarget = findGround(nakama, level);
			if (borrowTarget == null) {
				return Supply.NONE;
			}
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(borrowTarget));
		if (dist > BORROW_REACH) {
			borrowBreaker.cancel(nakama, level);
			if (!borrowWalker.step(nakama, borrowTarget, 1, 20, dist)) {
				unreachable.add(borrowTarget);
				borrowTarget = null;
			}
			return Supply.WORKING;
		}
		nakama.getNavigation().stop();
		List<ItemStack> drops = borrowBreaker.tick(nakama, level, borrowTarget);
		if (drops != null) {
			borrowed.add(borrowTarget);
			Breaker.stash(nakama, level, drops);
			borrowTarget = null;
		}
		return Supply.WORKING;
	}

	/** 土を借りてよい地面のうち、いちばん近いもの。自分の真下・足場の下・拠点のそばは選ばない。 */
	private BlockPos findGround(NakamaEntity nakama, ServerLevel level) {
		BlockPos feet = nakama.blockPosition();
		BlockPos home = nakama.home();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(feet.offset(-BORROW_RADIUS, -3, -BORROW_RADIUS), feet.offset(BORROW_RADIUS, 1, BORROW_RADIUS))) {
			double d = p.distSqr(feet);
			boolean ownColumn = Math.abs(p.getX() - feet.getX()) <= 1 && Math.abs(p.getZ() - feet.getZ()) <= 1;
			if (d >= bestDist || ownColumn || unreachable.contains(p) || !isLooseGround(level, p)) {
				continue;
			}
			if (home != null && home.distSqr(p) < HOME_PROTECT_RADIUS * HOME_PROTECT_RADIUS) {
				continue;
			}
			if (placed.stream().anyMatch(s -> s.pos().getX() == p.getX() && s.pos().getZ() == p.getZ())) {
				continue;
			}
			best = p.immutable();
			bestDist = d;
		}
		return best;
	}

	/** 上に何も載っていない、土か草ブロックか。 */
	private static boolean isLooseGround(ServerLevel level, BlockPos pos) {
		String id = Terrain.idOf(level.getBlockState(pos));
		return (id.equals("dirt") || id.equals("grass_block")) && Terrain.isOpen(level.getBlockState(pos.above()));
	}

	/**
	 * 足元に1つ積んで、1段上がる。
	 *
	 * @return 上がれたら true。材料がない・頭の上がふさがっている・高すぎるなら false
	 */
	boolean raise(NakamaEntity nakama, ServerLevel level, Set<String> keep) {
		int slot = fillerSlot(nakama, keep);
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
		placed.push(new Placed(feet, Terrain.idOf(state)));
		return true;
	}

	/** 積んだ足場を上から全部取りのぞいて持ち物に戻し、借りた土を埋め戻す。 */
	void removeAll(NakamaEntity nakama, ServerLevel level) {
		borrowBreaker.cancel(nakama, level);
		borrowTarget = null;
		while (!placed.isEmpty()) {
			Placed p = placed.pop();
			BlockPos pos = p.pos();
			BlockState state = level.getBlockState(pos);
			// 自分が積んだ種類のままのときだけ取りのぞく（あとから別のものに置き換わっていたら触らない）
			if (p.block().equals(Terrain.idOf(state))) {
				level.destroyBlock(pos, false, nakama);
				Breaker.stash(nakama, level, List.of(new ItemStack(state.getBlock().asItem())));
			}
		}
		for (BlockPos hole : borrowed) {
			// 借りたあとに何かが置かれていたら、そのままにする
			if (Terrain.isOpen(level.getBlockState(hole)) && Inv.remove(nakama.getInventory(), "dirt", 1) == 1) {
				level.setBlock(hole, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		borrowed.clear();
		unreachable.clear();
	}
}
