package dev.ainakama.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 暗いところを明るくするときの、松明を置く場所の選び方。
 * 松明の明かり（14）は1マス離れるごとに1ずつ弱くなる。モンスターは明かりのまったくない場所にわくので、
 * どの場所も松明から {@link #SPACING} マス未満（縦横高さの合計）になるように並べれば、明るさは {@link #MIN_LIGHT} 以上になり、わかなくなる。
 * 間隔を7にしたときは、25マス四方に24本も置いてしまった。
 */
public final class Lighting {
	/** 松明どうしの間隔。これより近い場所には、もう1本は置かない。 */
	public static final int SPACING = 10;
	/** 1回に置く松明の数の上限。 */
	public static final int MAX_TORCHES = 24;
	/**
	 * 明るさがこれより低い場所を「暗い」とみなす。松明から SPACING - 1 マス離れた場所の明るさと同じにしてある
	 * （置き終わったあとでもう一度頼まれても、同じ場所に置き足さないように）。
	 */
	public static final int MIN_LIGHT = 14 - (SPACING - 1);

	public record Spot(int x, int y, int z) {
		int distanceTo(Spot other) {
			return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
		}
	}

	private Lighting() {
	}

	/**
	 * 暗い場所の中から、松明を置く場所を選ぶ。中心に近い順に選び、もう選んだ場所の近くは飛ばす。
	 *
	 * @param dark 暗くて、松明を置ける場所
	 */
	public static List<Spot> choose(List<Spot> dark, Spot center) {
		List<Spot> sorted = dark.stream().sorted(Comparator.comparingInt(center::distanceTo)).toList();
		List<Spot> chosen = new ArrayList<>();
		for (Spot spot : sorted) {
			if (chosen.size() >= MAX_TORCHES) {
				break;
			}
			if (chosen.stream().noneMatch(c -> c.distanceTo(spot) < SPACING)) {
				chosen.add(spot);
			}
		}
		return chosen;
	}
}
