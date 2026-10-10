package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.core.Place;
import dev.ainakama.core.Smelting;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Storage;
import dev.ainakama.world.Terrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * かまどで焼く。本物のかまどに材料と燃料を入れ、焼けるのを待って取り出す（1個およそ10秒）。
 * 始める前に、かまど・材料・燃料がそろっているかを確かめ、足りなければ「先に用意して」と返す。
 * ・かまどが近くになければ、作って置く
 * ・材料は、チェストから出す・集める・焼いて作る、のどれかで用意する
 * ・燃料は、持っている石炭・木炭・板材・原木・棒を使う。足りなければ原木を集める
 * 途中でやめるときは、かまどに入れたものを持ち帰る。
 */
final class SmeltTask implements Task {
	private static final double REACH = 3.5;
	private static final int STUCK_TICKS = 160;
	private static final int CHECK_INTERVAL = 10;
	/** これだけ待っても1個も焼き上がらなければ、あきらめる（ふつうは1個10秒）。 */
	private static final int STALL_LIMIT_TICKS = 20 * 40;
	/** 拠点がこれより近ければ、かまどは拠点のそばに置く。 */
	private static final int HOME_RANGE = 32;

	private final String item;
	private final int requested;
	private final Walker walker = new Walker(STUCK_TICKS);
	private boolean prepared;
	private String inputKey;
	private int count;
	private BlockPos furnace;
	private int inserted;
	private int done;
	private int stallTicks;
	private int wait;
	/** かまどの燃料の枠に入っているのは、自分が入れたものか（プレイヤーが入れた燃料は持ち帰らない）。 */
	private boolean fuelIsMine;

	/**
	 * @param item      焼いてできるもの
	 * @param requested 焼く数。0 なら、持っている材料を全部（何も持っていなければ1個）
	 */
	SmeltTask(String item, int requested) {
		this.item = item;
		this.requested = requested;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (!prepared) {
			Result notReady = prepare(nakama, level);
			if (notReady != null) {
				return notReady;
			}
			prepared = true;
		}
		if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity oven)) {
			return new Result.Failed("使っていたかまどがなくなった");
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(furnace));
		if (dist > REACH) {
			return walker.step(nakama, furnace, 1, 20, dist) ? RUNNING : new Result.Failed("かまどに近づけない");
		}
		nakama.getNavigation().stop();
		nakama.getLookControl().setLookAt(Vec3.atCenterOf(furnace));
		if (--wait > 0) {
			return RUNNING;
		}
		wait = CHECK_INTERVAL;
		stallTicks += CHECK_INTERVAL;
		return work(nakama, level, oven);
	}

	/** かまど・材料・燃料がそろっているかを確かめる。足りなければその結果、そろっていれば null。 */
	private Result prepare(NakamaEntity nakama, ServerLevel level) {
		Map<String, Integer> inv = Inv.counts(nakama.getInventory());
		var chosen = Smelting.chooseInput(item, inv);
		if (chosen.isEmpty()) {
			return new Result.Failed(Items.label(item) + "は、かまどで焼いて作るものではない");
		}
		inputKey = chosen.get();
		int have = Items.count(inv, inputKey);
		count = requested > 0 ? requested : Math.max(1, have);

		List<Command> first = new ArrayList<>();
		furnace = Storage.freeFurnace(level, Storage.centers(nakama)).orElse(null);
		if (furnace == null) {
			first.add(new Command.PlaceBlock("furnace", furnaceSpot(nakama, level)));
		}
		if (have < count) {
			Supplies.Plan supplies = Supplies.plan(Map.of(inputKey, count - have), nakama, level);
			if (!supplies.ok()) {
				return new Result.Failed(Items.label(item) + "を焼くのに" + Items.describeKey(inputKey) + "が" + (count - have)
					+ "個足りない（集め方を知らない。渡してくれたら焼けるよ）");
			}
			first.addAll(supplies.commands());
		}
		// 焼く分そのもの（木炭にする原木など）は、燃料に数えない
		int shortage = Smelting.fuelShortage(Smelting.without(inv, inputKey, count), count);
		if (shortage > 0) {
			first.addAll(Supplies.plan(Map.of(Items.ANY_LOG, Smelting.logsFor(shortage)), nakama, level).commands());
		}
		return first.isEmpty() ? null : new Result.Needs(first, Items.label(item) + "を焼く準備が足りない");
	}

	/** かまどを置く場所。拠点が近ければそのそば、なければ今いる場所のそば（材料を掘りに行く前の、地上の場所）。 */
	private static Place furnaceSpot(NakamaEntity nakama, ServerLevel level) {
		BlockPos home = nakama.home();
		BlockPos center = home != null && home.closerThan(nakama.blockPosition(), HOME_RANGE) ? home : nakama.blockPosition();
		return Terrain.standableNear(level, center, true)
			.<Place>map(p -> new Place.Coords(p.getX(), p.getY(), p.getZ()))
			.orElseGet(Place.Self::new);
	}

	/** かまどの前での作業：焼けたものを取り出し、材料と燃料を足す。 */
	private Result work(NakamaEntity nakama, ServerLevel level, AbstractFurnaceBlockEntity oven) {
		var inv = nakama.getInventory();
		ItemStack result = Storage.furnaceResult(oven);
		if (!result.isEmpty() && Inv.idOf(result.getItem()).equals(item)) {
			int n = result.getCount();
			nakama.swingArm();
			if (!Breaker.stash(nakama, level, List.of(Storage.takeFurnaceResult(oven)))) {
				done += n;
				return new Result.Failed("持ち物がいっぱいで、焼けたものを足元に置いた（「しまって」と言ってね）");
			}
			done += n;
			stallTicks = 0;
		}
		if (done >= count) {
			reclaimFuel(nakama, level, oven);
			return DONE;
		}
		if (inserted < count && Storage.furnaceInput(oven).isEmpty()) {
			int slot = Inv.findSlot(inv, inputKey);
			if (slot >= 0) {
				ItemStack stack = inv.getItem(slot);
				int n = Math.min(count - inserted, stack.getCount());
				Storage.setFurnaceInput(oven, stack.split(n));
				inv.setChanged();
				inserted += n;
				nakama.swingArm();
			} else if (inserted <= done) {
				return new Result.Failed("焼くつもりだった" + Items.describeKey(inputKey) + "がなくなった（" + done + "/" + count + "個）");
			}
		}
		// 燃料は、火が消える前に次の1つを入れておく（消えてから入れると、焼きかけが少し戻ってしまう）。使わなかった分は最後に持ち帰る
		boolean needsFuel = !Storage.furnaceInput(oven).isEmpty() && Storage.furnaceFuel(oven).isEmpty();
		if (needsFuel && !addFuel(nakama, oven) && !level.getBlockState(furnace).getValue(AbstractFurnaceBlock.LIT)) {
			// 途中で燃料が切れた：残りの分の原木を用意してから、残りの数だけやり直す（pinned）
			List<Command> fuel = Supplies.plan(Map.of(Items.ANY_LOG, Smelting.logsFor(count - done)), nakama, level).commands();
			return new Result.Needs(fuel, "燃料が足りなくなった");
		}
		if (stallTicks > STALL_LIMIT_TICKS) {
			return new Result.Failed("かまどで" + Items.label(item) + "が焼けない（" + done + "/" + count + "個）");
		}
		return RUNNING;
	}

	/** 燃料を1つ入れる。まだかまどに入れていない材料は、燃やさずに取っておく。 */
	private boolean addFuel(NakamaEntity nakama, AbstractFurnaceBlockEntity oven) {
		var inv = nakama.getInventory();
		Map<String, Integer> spare = Smelting.without(Inv.counts(inv), inputKey, count - inserted);
		var fuel = Smelting.pickFuel(spare, count - done);
		int slot = fuel.map(id -> Inv.findSlot(inv, id)).orElse(-1);
		if (slot < 0 || !inv.getItem(slot).has(DataComponents.COOKING_FUEL)) {
			return false;
		}
		Storage.setFurnaceFuel(oven, inv.getItem(slot).split(1));
		inv.setChanged();
		fuelIsMine = true;
		return true;
	}

	/** 自分が入れて、使われずに残った燃料を持ち帰る。 */
	private void reclaimFuel(NakamaEntity nakama, ServerLevel level, AbstractFurnaceBlockEntity oven) {
		if (fuelIsMine && !Storage.furnaceFuel(oven).isEmpty()) {
			Breaker.stash(nakama, level, List.of(Storage.takeFurnaceFuel(oven)));
		}
		fuelIsMine = false;
	}

	/** やり直すときは、まだ焼けていない数だけにする。 */
	@Override
	public Command pinned(Command original) {
		return prepared ? new Command.Smelt(item, Math.max(1, count - done)) : original;
	}

	/** 途中でやめるときは、かまどに入れたままの材料と、焼けた分を持ち帰る。 */
	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		if (!prepared || !(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity oven)) {
			return;
		}
		reclaimFuel(nakama, level, oven);
		List<ItemStack> back = new ArrayList<>();
		if (Inv.idOf(Storage.furnaceResult(oven).getItem()).equals(item)) {
			back.add(Storage.takeFurnaceResult(oven));
		}
		if (Items.matches(inputKey, Inv.idOf(Storage.furnaceInput(oven).getItem()))) {
			back.add(Storage.takeFurnaceInput(oven));
		}
		Breaker.stash(nakama, level, back);
	}

	@Override
	public String describe() {
		return Items.label(item) + "を焼く（" + done + "/" + Math.max(count, requested) + "）";
	}
}
