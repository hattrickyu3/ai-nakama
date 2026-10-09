package dev.ainakama.task;

import dev.ainakama.core.Blueprint;
import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.core.Place;
import dev.ainakama.core.Recipes;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 設計図どおりにブロックを置く。
 * 始める前に材料を数え、足りなければ「先にクラフトして」と返す（クラフト側が原木集めまで面倒を見る）。
 * すでに何かがある場所には置かず、壊しもしない。
 */
final class BuildTask implements Task {
	/** 指定した場所から少しずらして建てる（プレイヤーや拠点の目印を埋めないため）。 */
	private static final int OFFSET = 2;
	private static final double REACH = 4.5;
	private static final int TICKS_PER_BLOCK = 4;
	private static final int STUCK_TICKS = 160;
	private static final int BLOCKED_GIVE_UP_TICKS = 100;

	private final Blueprint blueprint;
	private final BlockPos anchor;
	private final Walker walker = new Walker(STUCK_TICKS);
	private BlockPos origin;
	private int index;
	private int wait;
	private int placed;
	private int skipped;
	private int blockedTicks;

	BuildTask(Blueprint blueprint, BlockPos anchor) {
		this.blueprint = blueprint;
		this.anchor = anchor;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (origin == null) {
			// 高さは指定した場所（拠点やプレイヤーの足元）にそろえる。
			// 途中で材料を取りに行って建て直すときも同じ位置になるよう、地形からは計算しない
			origin = anchor.offset(OFFSET, 0, OFFSET);
			Result missing = checkMaterials(nakama, level);
			if (missing != null) {
				return missing;
			}
		}
		while (index < blueprint.parts().size()) {
			Blueprint.Part part = blueprint.parts().get(index);
			BlockPos pos = origin.offset(part.dx(), part.dy(), part.dz());
			if (!canPlaceAt(level.getBlockState(pos))) {
				// 何かがあるところ・水や溶岩のところは飛ばす（同じものが置いてあれば完成扱い）
				index++;
				skipped++;
				continue;
			}
			return placeStep(nakama, level, part, pos);
		}
		nakama.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		return DONE;
	}

	private Result placeStep(NakamaEntity nakama, ServerLevel level, Blueprint.Part part, BlockPos pos) {
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
		if (dist > REACH) {
			if (!walker.step(nakama, pos, 2, 20, dist)) {
				return new Result.Failed(blueprint.japaneseName() + "の建てる場所に近づけない");
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		if (--wait > 0) {
			return RUNNING;
		}
		wait = TICKS_PER_BLOCK;
		if (nakama.getBoundingBox().intersects(new AABB(pos))) {
			// 自分が立っている場所には置けないので、少し下がる
			nakama.getNavigation().moveTo(origin.getX() - 2, origin.getY(), origin.getZ() - 2, NakamaEntity.WALK_SPEED);
			return RUNNING;
		}
		if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> e != nakama && e.isAlive()).isEmpty()) {
			// プレイヤーや動物がいる場所に置くと埋めてしまうので、どくまで待つ。長く待ってもだめなら飛ばす
			if (++blockedTicks > BLOCKED_GIVE_UP_TICKS) {
				blockedTicks = 0;
				index++;
				skipped++;
			}
			return RUNNING;
		}
		blockedTicks = 0;
		int slot = Inv.findSlot(nakama.getInventory(), part.block());
		if (slot < 0) {
			// 途中で材料がなくなった：もう一度数え直す
			Result missing = checkMaterials(nakama, level);
			return missing != null ? missing : RUNNING;
		}
		ItemStack stack = nakama.getInventory().getItem(slot);
		if (!(stack.getItem() instanceof BlockItem blockItem)) {
			return new Result.Failed(Items.label(Inv.idOf(stack.getItem())) + "はブロックとして置けない");
		}
		nakama.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stack.copyWithCount(1));
		nakama.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		BlockState state = Block.updateFromNeighbourShapes(blockItem.getBlock().defaultBlockState(), level, pos);
		if (!level.setBlock(pos, state, Block.UPDATE_ALL)) {
			// 置けなかった（保護されている場所など）ので、材料は減らさずに飛ばす
			index++;
			skipped++;
			return RUNNING;
		}
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
		nakama.swingArm();
		stack.shrink(1);
		nakama.getInventory().setChanged();
		placed++;
		index++;
		return RUNNING;
	}

	/** まだ置いていない部分に必要な材料と持ち物を比べる。足りなければ Needs、そろっていれば null。 */
	private Result checkMaterials(NakamaEntity nakama, ServerLevel level) {
		Map<String, Integer> need = new java.util.LinkedHashMap<>();
		for (int i = index; i < blueprint.parts().size(); i++) {
			Blueprint.Part part = blueprint.parts().get(i);
			if (canPlaceAt(level.getBlockState(origin.offset(part.dx(), part.dy(), part.dz())))) {
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
			if (!Recipes.canCraft(concrete)) {
				return new Result.Failed(blueprint.japaneseName() + "の材料（" + Items.label(concrete) + "）が" + deficit + "個足りない");
			}
			first.add(new Command.Craft(concrete, deficit));
		}
		return first.isEmpty() ? null : new Result.Needs(first, blueprint.japaneseName() + "の材料が足りない");
	}

	/** 草や空気のところには置く。水・溶岩・ほかのブロックがあるところには置かない。 */
	private static boolean canPlaceAt(BlockState there) {
		return there.canBeReplaced() && there.getFluidState().isEmpty();
	}

	@Override
	public Command pinned(Command original) {
		return new Command.Build(blueprint.id(), new Place.Coords(anchor.getX(), anchor.getY(), anchor.getZ()));
	}

	@Override
	public String describe() {
		return blueprint.japaneseName() + "を建てる（" + placed + "/" + (blueprint.parts().size() - skipped) + "）";
	}
}
