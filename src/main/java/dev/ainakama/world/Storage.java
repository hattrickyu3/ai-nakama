package dev.ainakama.world;

import dev.ainakama.core.Items;
import dev.ainakama.entity.NakamaEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 近くのチェストやかまどを探す道具。
 * ブロックを1つずつ調べるのではなく、読み込まれているチャンクが持っている「中身のあるブロック」の一覧から探すので、広い範囲でも軽い。
 */
public final class Storage {
	/** チェストを探す広さ（水平）。 */
	public static final int CHEST_RADIUS = 16;
	/** チェストを探す高さ（上下）。別の階や地下の洞窟にあるチェストまでは見に行かない。 */
	public static final int CHEST_HEIGHT = 6;
	/** かまどを探す広さ（水平）。材料を掘りに行って離れたあとでも、置いたかまどを見つけられるように広くしている。 */
	public static final int FURNACE_RADIUS = 48;
	private static final int FURNACE_INPUT = 0;
	private static final int FURNACE_FUEL = 1;
	private static final int FURNACE_RESULT = 2;

	/** チェストの中で見つけたもの。 */
	public record Found(BlockPos pos, String id, int count) {
	}

	private Storage() {
	}

	/** 探すときの中心。自分のいる場所と、拠点（あれば）。 */
	public static List<BlockPos> centers(NakamaEntity nakama) {
		List<BlockPos> centers = new ArrayList<>();
		centers.add(nakama.blockPosition());
		if (nakama.home() != null) {
			centers.add(nakama.home());
		}
		return centers;
	}

	/** centers のどれかから radius マス以内（水平）にある、条件に合う「中身のあるブロック」。最初の中心に近い順。 */
	private static List<BlockEntity> near(ServerLevel level, List<BlockPos> centers, int radius, Predicate<BlockEntity> filter) {
		List<BlockEntity> found = new ArrayList<>();
		for (BlockPos center : centers) {
			for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
				for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) {
						continue;
					}
					for (BlockEntity be : chunk.getBlockEntities().values()) {
						if (!be.isRemoved() && horizontalDistance(be.getBlockPos(), center) <= radius && filter.test(be) && !found.contains(be)) {
							found.add(be);
						}
					}
				}
			}
		}
		BlockPos origin = centers.get(0);
		found.sort(Comparator.comparingDouble(be -> be.getBlockPos().distSqr(origin)));
		return found;
	}

	private static int horizontalDistance(BlockPos a, BlockPos b) {
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
	}

	public static boolean isChest(BlockEntity be) {
		if (!(be instanceof Container)) {
			return false;
		}
		if (be instanceof RandomizableContainer loot && loot.getLootTable() != null) {
			// まだ誰も開けていない宝箱（遺跡など）。中をのぞくと中身が決まってしまうので、触らない
			return false;
		}
		String id = Inv.idOf(be.getBlockState().getBlock().asItem());
		return id.equals("chest") || id.equals("barrel") || id.equals("trapped_chest");
	}

	/** 近くのチェスト（樽も可）の場所。近い順。 */
	public static List<BlockPos> chests(ServerLevel level, List<BlockPos> centers) {
		return nearbyChests(level, centers).stream().map(BlockEntity::getBlockPos).toList();
	}

	private static List<BlockEntity> nearbyChests(ServerLevel level, List<BlockPos> centers) {
		return near(level, centers, CHEST_RADIUS, be -> isChest(be)
			&& centers.stream().anyMatch(c -> Math.abs(be.getBlockPos().getY() - c.getY()) <= CHEST_HEIGHT));
	}

	/** key（ID・グループ・any:a|b）に当てはまるものが入っている、いちばん近いチェスト。 */
	public static Optional<Found> findItem(ServerLevel level, List<BlockPos> centers, String key) {
		for (BlockEntity be : nearbyChests(level, centers)) {
			Container chest = (Container) be;
			for (int i = 0; i < chest.getContainerSize(); i++) {
				ItemStack s = chest.getItem(i);
				if (!s.isEmpty() && Items.matches(key, Inv.idOf(s.getItem()))) {
					String id = Inv.idOf(s.getItem());
					return Optional.of(new Found(be.getBlockPos(), id, Inv.count(chest, id)));
				}
			}
		}
		return Optional.empty();
	}

	/** 何も入っていない、すぐ使えるかまど。プレイヤーが使っている途中のかまどには手を出さない。 */
	public static Optional<BlockPos> freeFurnace(ServerLevel level, List<BlockPos> centers) {
		return near(level, centers, FURNACE_RADIUS, Storage::isFreeFurnace).stream().map(BlockEntity::getBlockPos).findFirst();
	}

	private static boolean isFreeFurnace(BlockEntity be) {
		return be instanceof AbstractFurnaceBlockEntity furnace
			&& Inv.idOf(be.getBlockState().getBlock().asItem()).equals("furnace")
			&& furnace.getItem(FURNACE_INPUT).isEmpty() && furnace.getItem(FURNACE_RESULT).isEmpty();
	}

	public static ItemStack furnaceInput(AbstractFurnaceBlockEntity furnace) {
		return furnace.getItem(FURNACE_INPUT);
	}

	public static ItemStack furnaceFuel(AbstractFurnaceBlockEntity furnace) {
		return furnace.getItem(FURNACE_FUEL);
	}

	public static ItemStack furnaceResult(AbstractFurnaceBlockEntity furnace) {
		return furnace.getItem(FURNACE_RESULT);
	}

	public static void setFurnaceInput(AbstractFurnaceBlockEntity furnace, ItemStack stack) {
		furnace.setItem(FURNACE_INPUT, stack);
		furnace.setChanged();
	}

	public static void setFurnaceFuel(AbstractFurnaceBlockEntity furnace, ItemStack stack) {
		furnace.setItem(FURNACE_FUEL, stack);
		furnace.setChanged();
	}

	/** できあがったものをかまどから全部取り出す。 */
	public static ItemStack takeFurnaceResult(AbstractFurnaceBlockEntity furnace) {
		ItemStack out = furnace.removeItemNoUpdate(FURNACE_RESULT);
		furnace.setChanged();
		return out;
	}

	/** 燃料の枠に残っているものをかまどから取り出す。 */
	public static ItemStack takeFurnaceFuel(AbstractFurnaceBlockEntity furnace) {
		ItemStack out = furnace.removeItemNoUpdate(FURNACE_FUEL);
		furnace.setChanged();
		return out;
	}

	/** 入れたままの材料をかまどから全部取り出す。 */
	public static ItemStack takeFurnaceInput(AbstractFurnaceBlockEntity furnace) {
		ItemStack out = furnace.removeItemNoUpdate(FURNACE_INPUT);
		furnace.setChanged();
		return out;
	}

	/** stack を container に入れる。入った分だけ stack が減る。 */
	public static void moveInto(Container container, ItemStack stack) {
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack slot = container.getItem(i);
			if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
				int room = Math.min(slot.getMaxStackSize(), container.getMaxStackSize()) - slot.getCount();
				int n = Math.min(room, stack.getCount());
				if (n > 0) {
					slot.grow(n);
					stack.shrink(n);
				}
			}
		}
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			if (container.getItem(i).isEmpty()) {
				container.setItem(i, stack.copy());
				stack.setCount(0);
			}
		}
	}
}
