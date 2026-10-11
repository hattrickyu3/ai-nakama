package dev.ainakama.world;

import dev.ainakama.core.Items;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** 仲間の持ち物（SimpleContainer）を、core の「IDごとの数」の形で扱うための道具。 */
public final class Inv {
	private Inv() {
	}

	public static String idOf(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	public static boolean itemExists(String id) {
		Identifier key = Identifier.tryParse("minecraft:" + id);
		return key != null && BuiltInRegistries.ITEM.containsKey(key);
	}

	public static Optional<Item> item(String id) {
		Identifier key = Identifier.tryParse("minecraft:" + id);
		if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
			return Optional.empty();
		}
		return Optional.of(BuiltInRegistries.ITEM.getValue(key));
	}

	public static Map<String, Integer> counts(Container inv) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) {
				m.merge(idOf(s.getItem()), s.getCount(), Integer::sum);
			}
		}
		return m;
	}

	public static int count(Container inv, String key) {
		return Items.count(counts(inv), key);
	}

	/** id ごとに amount 個取り除く。足りなければ取れた分だけ。 */
	public static int remove(Container inv, String id, int amount) {
		int left = amount;
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && idOf(s.getItem()).equals(id)) {
				int n = Math.min(left, s.getCount());
				s.shrink(n);
				left -= n;
			}
		}
		inv.setChanged();
		return amount - left;
	}

	/** 入れる。入りきらなかった分を返す。 */
	public static ItemStack add(SimpleContainer inv, ItemStack stack) {
		return inv.addItem(stack);
	}

	/** key（IDまたはグループ）に当てはまる最初のスタックの位置。なければ -1。 */
	public static int findSlot(Container inv, String key) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && Items.matches(key, idOf(s.getItem()))) {
				return i;
			}
		}
		return -1;
	}

	/** そのブロックに合う道具のうち、いちばん速く掘れるもの（木より石、石より鉄）。なければ空のスタック。 */
	public static ItemStack bestToolFor(Container inv, BlockState state) {
		ItemStack best = ItemStack.EMPTY;
		float bestSpeed = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !s.isCorrectToolForDrops(state)) {
				continue;
			}
			float speed = s.getDestroySpeed(state);
			if (speed > bestSpeed) {
				best = s;
				bestSpeed = speed;
			}
		}
		return best;
	}

	public static boolean hasPickaxe(Container inv) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && idOf(s.getItem()).endsWith("_pickaxe")) {
				return true;
			}
		}
		return false;
	}

	/** 武器の材質。あとに書いたものほど強い。 */
	private static final List<String> WEAPON_TIERS = List.of("wooden", "golden", "stone", "copper", "iron", "diamond", "netherite");

	/** 持っている中でいちばん強い剣か斧。なければ空のスタック（素手）。同じ材質なら剣を選ぶ。 */
	public static ItemStack bestWeapon(Container inv) {
		ItemStack best = ItemStack.EMPTY;
		int bestScore = Integer.MIN_VALUE;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			String id = s.isEmpty() ? "" : idOf(s.getItem());
			boolean sword = id.endsWith("_sword");
			if (!sword && !id.endsWith("_axe")) {
				continue;
			}
			int score = WEAPON_TIERS.indexOf(id.substring(0, id.lastIndexOf('_'))) * 2 + (sword ? 1 : 0);
			if (score > bestScore) {
				best = s;
				bestScore = score;
			}
		}
		return best;
	}

	public static boolean isTool(ItemStack s) {
		String id = idOf(s.getItem());
		return id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel") || id.endsWith("_sword")
			|| id.endsWith("_hoe") || id.equals("crafting_table");
	}
}
