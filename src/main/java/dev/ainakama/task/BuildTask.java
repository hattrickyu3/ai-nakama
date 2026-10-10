package dev.ainakama.task;

import dev.ainakama.AiNakama;
import dev.ainakama.core.Blueprint;
import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.core.Place;
import dev.ainakama.core.Recipes;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Placer;
import dev.ainakama.world.SitePlanner;
import dev.ainakama.world.Terrain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 設計図どおりに建てる。順番は「場所を選ぶ → 材料をそろえる → 邪魔なものをどける → 足元を埋める → 置く → 足場を片付ける」。
 * どけるのは自然のもの（土・石・草・木など）だけ。プレイヤーが置いたらしいブロックは壊さず、そこは飛ばす。
 */
final class BuildTask implements Task {
	private static final double REACH = 4.5;
	private static final double ARRIVE_DISTANCE = 8;
	private static final int TICKS_PER_BLOCK = 4;
	private static final int STUCK_TICKS = 160;
	private static final int BLOCKED_GIVE_UP_TICKS = 100;
	/** 足元を埋める深さの上限。これより深い穴や崖は埋めない。 */
	private static final int MAX_FILL_DEPTH = 3;
	private static final int FILLER_BATCH = 16;
	/** 自分が邪魔なとき、よけるために歩く時間と、やり直す回数。 */
	private static final int EVADE_TICKS = 15;
	private static final int MAX_EVADE_TRIES = 5;

	private enum Stage {
		CLEAR, FOUNDATION, PLACE
	}

	private final Blueprint blueprint;
	private final BlockPos anchor;
	private final boolean exact;
	private final Walker walker = new Walker(STUCK_TICKS);
	private final Breaker breaker = new Breaker();
	private final Scaffold scaffold = new Scaffold();

	private BlockPos origin;
	private Stage stage = Stage.CLEAR;
	private List<BlockPos> work;
	private int index;
	private int wait;
	private int placed;
	private int skipped;
	private int blockedTicks;
	private int evadeTicks;
	private int evadeTries;

	/** @param exact anchor が建物の基準点そのものか（やり直しのとき）。false なら anchor のまわりで場所を選ぶ */
	BuildTask(Blueprint blueprint, BlockPos anchor, boolean exact) {
		this.blueprint = blueprint;
		this.anchor = anchor;
		this.exact = exact;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (origin == null) {
			// まず建てる場所まで行く。材料が足りないとき、建てる場所の近くで集められるようにするため
			double dist = Math.sqrt(nakama.distanceToSqr(Vec3.atCenterOf(anchor)));
			if (dist > ARRIVE_DISTANCE && walker.step(nakama, anchor, 3, 20, dist)) {
				return RUNNING;
			}
			int[] size = blueprint.size();
			origin = exact ? anchor : SitePlanner.choose(level, anchor, nakama.home(), size[0], size[1], size[2]);
			Result missing = checkMaterials(nakama, level);
			if (missing != null) {
				return missing;
			}
		}
		Result result = switch (stage) {
			case CLEAR -> clearStep(nakama, level);
			case FOUNDATION -> foundationStep(nakama, level);
			case PLACE -> placeStage(nakama, level);
		};
		if (result != RUNNING) {
			cleanUp(nakama, level);
		}
		return result;
	}

	// ---- 邪魔なものをどける ----

	private Result clearStep(NakamaEntity nakama, ServerLevel level) {
		if (work == null) {
			work = volumeTopDown();
			index = 0;
		}
		while (index < work.size()) {
			BlockPos pos = work.get(index);
			BlockState state = level.getBlockState(pos);
			if (Terrain.isOpen(state) || !Terrain.isNaturalClearable(level, pos, state) || isAlreadyBuilt(pos, state)) {
				index++;
				continue;
			}
			if (Breaker.lacksTool(nakama, state) && Terrain.isGround(state)) {
				return new Result.Needs(List.of(new Command.Craft("wooden_pickaxe", 1)), "石をどけるのにツルハシが要る");
			}
			Result approach = approach(nakama, level, pos);
			if (approach != null) {
				return approach;
			}
			List<ItemStack> drops = breaker.tick(nakama, level, pos);
			if (drops != null) {
				Breaker.stash(nakama, level, drops);
				index++;
			}
			return RUNNING;
		}
		next(Stage.FOUNDATION);
		return RUNNING;
	}

	/** 設計図どおりのブロックがもう置いてあるマスか（やり直しのとき、土や石でできた部分を壊さないため）。 */
	private boolean isAlreadyBuilt(BlockPos pos, BlockState state) {
		String id = Terrain.idOf(state);
		for (Blueprint.Part part : blueprint.parts()) {
			if (origin.offset(part.dx(), part.dy(), part.dz()).equals(pos)) {
				return Items.matches(part.block(), id);
			}
		}
		return false;
	}

	/** 建物が入る範囲のマスを、上の段から順に並べる（上から掘ると、砂や砂利が落ちてきても片付けやすい）。 */
	private List<BlockPos> volumeTopDown() {
		int[] size = blueprint.size();
		List<BlockPos> cells = new ArrayList<>();
		for (int y = size[1] - 1; y >= 0; y--) {
			for (int x = 0; x < size[0]; x++) {
				for (int z = 0; z < size[2]; z++) {
					cells.add(origin.offset(x, y, z));
				}
			}
		}
		return cells;
	}

	// ---- 足元を埋める ----

	private Result foundationStep(NakamaEntity nakama, ServerLevel level) {
		if (work == null) {
			work = gaps(level);
			index = 0;
		}
		while (index < work.size()) {
			BlockPos pos = work.get(index);
			if (!level.getBlockState(pos).canBeReplaced()) {
				index++;
				continue;
			}
			int slot = Scaffold.fillerSlot(nakama);
			if (slot < 0) {
				int left = Math.min(FILLER_BATCH, work.size() - index);
				return new Result.Needs(List.of(new Command.Collect("dirt", left)), "足元を埋める土が足りない");
			}
			Result approach = approach(nakama, level, pos);
			if (approach != null) {
				return approach;
			}
			if (--wait > 0) {
				return RUNNING;
			}
			wait = TICKS_PER_BLOCK;
			ItemStack stack = nakama.getInventory().getItem(slot);
			if (Placer.place(level, pos, stack, Direction.NORTH)) {
				nakama.getInventory().setChanged();
				nakama.swingArm();
			}
			index++;
			return RUNNING;
		}
		next(Stage.PLACE);
		return RUNNING;
	}

	/** 床の下の、空いているマス（下から順）。深すぎる穴は対象にしない。 */
	private List<BlockPos> gaps(ServerLevel level) {
		int[] size = blueprint.size();
		List<BlockPos> cells = new ArrayList<>();
		for (int depth = MAX_FILL_DEPTH; depth >= 1; depth--) {
			for (int x = 0; x < size[0]; x++) {
				for (int z = 0; z < size[2]; z++) {
					BlockPos pos = origin.offset(x, -depth, z);
					boolean bottomSolid = !level.getBlockState(origin.offset(x, -MAX_FILL_DEPTH - 1, z)).canBeReplaced();
					if (level.getBlockState(pos).canBeReplaced() && (bottomSolid || depth == 1)) {
						cells.add(pos);
					}
				}
			}
		}
		return cells;
	}

	// ---- 置く ----

	private Result placeStage(NakamaEntity nakama, ServerLevel level) {
		while (index < blueprint.parts().size()) {
			Blueprint.Part part = blueprint.parts().get(index);
			BlockPos pos = origin.offset(part.dx(), part.dy(), part.dz());
			BlockState there = level.getBlockState(pos);
			if (Terrain.isOpen(there)) {
				return placeStep(nakama, level, part, pos);
			}
			if (Items.matches(part.block(), Terrain.idOf(there))) {
				// もう同じものが置いてある
				index++;
				continue;
			}
			if (scaffold.contains(pos)) {
				// 自分の足場が建物のマスに残っている：片付けてから置く
				scaffold.removeAll(nakama, level);
				return RUNNING;
			}
			if (Terrain.isNaturalClearable(level, pos, there) && !Breaker.lacksTool(nakama, there)) {
				// 整地のあとに落ちてきた砂や、伸びてきた葉っぱなど：どけてから置く
				Result approach = approach(nakama, level, pos);
				if (approach != null) {
					return approach;
				}
				List<ItemStack> drops = breaker.tick(nakama, level, pos);
				if (drops != null) {
					Breaker.stash(nakama, level, drops);
				}
				return RUNNING;
			}
			skip(pos, "別のブロック（" + Terrain.idOf(there) + "）がある");
		}
		nakama.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		return DONE;
	}

	private Result placeStep(NakamaEntity nakama, ServerLevel level, Blueprint.Part part, BlockPos pos) {
		if (evadeTicks > 0) {
			// よけている途中。ここで「近づく」処理をすると、歩くのを止めてしまう
			evadeTicks--;
			return RUNNING;
		}
		Result approach = approach(nakama, level, pos);
		if (approach != null) {
			return approach;
		}
		if (--wait > 0) {
			return RUNNING;
		}
		wait = TICKS_PER_BLOCK;
		if (nakama.getBoundingBox().intersects(new AABB(pos))) {
			// 自分が立っている場所には置けないので、建物の外へよける
			var aside = Terrain.standableNear(level, pos, true);
			if (aside.isEmpty() || ++evadeTries > MAX_EVADE_TRIES) {
				// 歩いてはよけられない（狭いところにいる）ので、建物の外へ出てから置き直す
				evadeTries = 0;
				var outside = Terrain.standableNear(level, origin.offset(-2, 0, -2));
				if (outside.isEmpty()) {
					skip(pos, "自分が立っていて、よけられない");
					return RUNNING;
				}
				BlockPos o = outside.get();
				nakama.getNavigation().stop();
				nakama.snapTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, nakama.getYRot(), nakama.getXRot());
				return RUNNING;
			}
			BlockPos p = aside.get();
			nakama.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, NakamaEntity.WALK_SPEED);
			evadeTicks = EVADE_TICKS;
			return RUNNING;
		}
		if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> e != nakama && e.isAlive()).isEmpty()) {
			// プレイヤーや動物がいる場所に置くと埋めてしまうので、どくまで待つ。長く待ってもだめなら飛ばす
			if (++blockedTicks > BLOCKED_GIVE_UP_TICKS) {
				blockedTicks = 0;
				skip(pos, "プレイヤーか生き物がいる");
			}
			return RUNNING;
		}
		blockedTicks = 0;
		evadeTries = 0;
		ItemStack stack = ItemStack.EMPTY;
		if (!part.free()) {
			int slot = Inv.findSlot(nakama.getInventory(), part.block());
			if (slot < 0) {
				// 途中で材料がなくなった：もう一度数え直す
				Result missing = checkMaterials(nakama, level);
				return missing != null ? missing : RUNNING;
			}
			stack = nakama.getInventory().getItem(slot);
			nakama.setItemSlot(EquipmentSlot.MAINHAND, stack.copyWithCount(1));
		}
		if (put(nakama, level, part, pos, stack)) {
			nakama.getInventory().setChanged();
			nakama.swingArm();
			placed++;
			index++;
		} else {
			skip(pos, Items.describeKey(part.block()) + "を置けなかった");
		}
		return RUNNING;
	}

	/** そのマスを飛ばして先へ進む。あとで原因を調べられるように記録に残す。 */
	private void skip(BlockPos pos, String why) {
		AiNakama.LOGGER.info("{}: {} のマスを飛ばしました（{}）", blueprint.japaneseName(), pos.toShortString(), why);
		index++;
		skipped++;
	}

	private static boolean put(NakamaEntity nakama, ServerLevel level, Blueprint.Part part, BlockPos pos, ItemStack stack) {
		if (part.state() != null) {
			var state = Placer.read(level, part.state());
			if (state.isEmpty() || !Placer.placeExact(level, pos, state.get())) {
				return false;
			}
			stack.shrink(1);
			return true;
		}
		Direction facing = Direction.getApproximateNearest(pos.getX() + 0.5 - nakama.getX(), 0, pos.getZ() + 0.5 - nakama.getZ());
		return Placer.place(level, pos, stack, facing);
	}

	// ---- 共通 ----

	/**
	 * pos に手が届くところまで近づく。届いていれば null を返す。
	 * 高さだけが足りないときは足場を積む。足場の材料がなければ先に土を集める。
	 */
	private Result approach(NakamaEntity nakama, ServerLevel level, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		double dist = nakama.getEyePosition().distanceTo(center);
		if (dist <= REACH) {
			nakama.getNavigation().stop();
			return null;
		}
		breaker.cancel(nakama, level);
		double dx = center.x - nakama.getX();
		double dz = center.z - nakama.getZ();
		boolean highAbove = Math.sqrt(dx * dx + dz * dz) <= 3.0 && center.y > nakama.getEyeY();
		if (highAbove) {
			if (scaffold.raise(nakama, level)) {
				return RUNNING;
			}
			if (!Scaffold.hasFiller(nakama)) {
				return new Result.Needs(List.of(new Command.Collect("dirt", FILLER_BATCH)), "高いところに届く足場の土が足りない");
			}
		}
		if (!walker.step(nakama, pos, 2, 20, dist)) {
			// どうしても近づけない1マスは飛ばして先へ進む
			skip(pos, "近づけない");
		}
		return RUNNING;
	}

	private void next(Stage nextStage) {
		stage = nextStage;
		work = null;
		index = 0;
	}

	/** まだ置いていない部分に必要な材料と持ち物を比べる。足りなければ Needs、そろっていれば null。 */
	private Result checkMaterials(NakamaEntity nakama, ServerLevel level) {
		Map<String, Integer> need = new LinkedHashMap<>();
		int from = stage == Stage.PLACE ? index : 0;
		for (int i = from; i < blueprint.parts().size(); i++) {
			Blueprint.Part part = blueprint.parts().get(i);
			BlockPos pos = origin.offset(part.dx(), part.dy(), part.dz());
			BlockState there = level.getBlockState(pos);
			boolean willBeFree = Terrain.isOpen(there) || Terrain.isNaturalClearable(level, pos, there);
			if (!part.free() && willBeFree) {
				need.merge(part.block(), 1, Integer::sum);
			}
		}
		Map<String, Integer> inv = Inv.counts(nakama.getInventory());
		List<Command> first = new ArrayList<>();
		for (var e : need.entrySet()) {
			int deficit = e.getValue() - Items.count(inv, e.getKey());
			if (deficit <= 0) {
				continue;
			}
			String concrete = e.getKey().equals(Items.ANY_PLANKS) ? "oak_planks" : e.getKey();
			if (Items.isCollectable(concrete)) {
				first.add(new Command.Collect(concrete, deficit));
			} else if (Recipes.canCraft(concrete)) {
				first.add(new Command.Craft(concrete, deficit));
			} else {
				return new Result.Failed(blueprint.japaneseName() + "の材料（" + Items.label(concrete) + "）が" + deficit + "個足りない。渡してくれたら建てられるよ");
			}
		}
		return first.isEmpty() ? null : new Result.Needs(first, blueprint.japaneseName() + "の材料が足りない");
	}

	private void cleanUp(NakamaEntity nakama, ServerLevel level) {
		breaker.cancel(nakama, level);
		scaffold.removeAll(nakama, level);
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		cleanUp(nakama, level);
	}

	@Override
	public Command pinned(Command original) {
		BlockPos p = origin != null ? origin : anchor;
		return origin != null || exact
			? new Command.Build(blueprint.id(), new Place.Exact(p.getX(), p.getY(), p.getZ()))
			: original;
	}

	@Override
	public String describe() {
		String what = switch (stage) {
			case CLEAR -> "の場所を整えている";
			case FOUNDATION -> "の足元を埋めている";
			case PLACE -> "を建てている（" + placed + "/" + (blueprint.parts().size() - skipped) + "）";
		};
		return blueprint.japaneseName() + what;
	}
}
