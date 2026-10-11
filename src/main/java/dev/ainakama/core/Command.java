package dev.ainakama.core;

/**
 * 仲間が実行できる命令。AIの計画も、決まった言い方の指示も、最後はこの形になる。
 * 細かい手順（道探し・途中の材料のクラフトなど）は実行側が受け持つので、命令は作業のまとまり単位にしている。
 */
public sealed interface Command {
	/** 計画を人が読める形で表す（失敗の報告やAIへの説明に使う）。 */
	String describe();

	record Follow() implements Command {
		public String describe() {
			return "follow";
		}
	}

	record Stop() implements Command {
		public String describe() {
			return "stop";
		}
	}

	record MoveTo(Place place) implements Command {
		public String describe() {
			return "move_to " + place.describe();
		}
	}

	/** item は Minecraft のID。原木（*_log）はどの種類の木でもよい扱い。 */
	record Collect(String item, int count) implements Command {
		public String describe() {
			return "collect " + item + " " + count;
		}
	}

	record Craft(String item, int count) implements Command {
		public String describe() {
			return "craft " + item + " " + count;
		}
	}

	record Build(String blueprint, Place place) implements Command {
		public String describe() {
			return "build " + blueprint + " at " + place.describe();
		}
	}

	/** プレイヤーに持ち物を渡す。item が null なら道具以外を全部、count が 0 ならあるだけ全部。 */
	record Give(String item, int count) implements Command {
		public String describe() {
			return "give " + (item == null ? "all" : item + (count > 0 ? " " + count : ""));
		}
	}

	/** ブロックを1つ置く（扉・松明・作業台など）。 */
	record PlaceBlock(String item, Place place) implements Command {
		public String describe() {
			return "place " + item + " at " + place.describe();
		}
	}

	/** 覚えた技を使う。実行の前に、その技の手順に置き換えられる。 */
	record UseSkill(String name) implements Command {
		public String describe() {
			return "skill " + name;
		}
	}

	record Store() implements Command {
		public String describe() {
			return "store";
		}
	}

	/** 近くのチェストから取り出す。count が 0 ならあるだけ全部。 */
	record Take(String item, int count) implements Command {
		public String describe() {
			return "take " + item + (count > 0 ? " " + count : "");
		}
	}

	/** かまどで焼く。item は焼いてできるもののID。count が 0 なら、持っている材料を全部。 */
	record Smelt(String item, int count) implements Command {
		public String describe() {
			return "smelt " + item + (count > 0 ? " " + count : "");
		}
	}

	/** 畑の世話。育ちきった作物を収穫して、同じものを植え直す。 */
	record Farm() implements Command {
		public String describe() {
			return "farm";
		}
	}

	/** 近くの敵を倒す。いなくなったら終わり。 */
	record Fight() implements Command {
		public String describe() {
			return "fight";
		}
	}

	/** プレイヤーについていき、近づく敵を倒す。stop か新しい指示が来るまで続く。 */
	record Guard() implements Command {
		public String describe() {
			return "guard";
		}
	}

	/**
	 * 動物を狩って、落としたものを拾う。
	 *
	 * @param animal 動物のID（cow など）か {@link Hunting#ANY}
	 * @param item   ほしいもの（beef, leather など。{@link Hunting#MEAT} はどの肉でも）。null なら count は動物の数
	 * @param count  item があればその個数、なければ動物の数
	 */
	record Hunt(String animal, String item, int count) implements Command {
		public String describe() {
			return "hunt " + (item == null ? animal : item) + " " + count;
		}
	}

	/** 地面を耕して、畑にする。count は耕すマスの数。種をまくのは {@link Farm}。 */
	record Till(Place place, int count) implements Command {
		/** 数を言われなかったときに耕すマスの数（3×3）。 */
		public static final int DEFAULT_COUNT = 9;
		public static final int MAX_COUNT = 64;

		public String describe() {
			return "till at " + place.describe() + " " + count;
		}
	}

	/**
	 * アイテムを count 個持っている状態にする。手に入れ方（チェストから出す・集める・狩る・焼く・クラフトする）は
	 * {@link Obtaining} と実行側が選ぶ。
	 */
	record Obtain(String item, int count) implements Command {
		public String describe() {
			return "get " + item + " " + count;
		}
	}

	/** ハサミで羊の毛を刈る。count は集める羊毛の数。 */
	record Shear(int count) implements Command {
		public static final int DEFAULT_COUNT = 3;

		public String describe() {
			return "shear " + count;
		}
	}

	/** バケツで牛の乳をしぼる。count はバケツの杯数。 */
	record Milk(int count) implements Command {
		public String describe() {
			return "milk " + count;
		}
	}

	/** 動物にエサをやって増やす。count は増やす数（エサをやる組の数）。 */
	record Breed(String animal, int count) implements Command {
		public String describe() {
			return "breed " + animal + " " + count;
		}
	}

	/** 釣りをする。count は釣る魚の数。 */
	record Fish(int count) implements Command {
		public static final int DEFAULT_COUNT = 3;

		public String describe() {
			return "fish " + count;
		}
	}

	/** まわりの暗いところに松明を置いて、明るくする。 */
	record Light(Place place) implements Command {
		public String describe() {
			return "light at " + place.describe();
		}
	}

	/**
	 * 村人と取引する。
	 *
	 * @param item  sell なら売るもの、そうでなければ買うもの。null なら、近くの村人の取引の一覧を言うだけ
	 * @param count 取引する回数
	 */
	record Trade(String item, boolean sell, int count) implements Command {
		public String describe() {
			return item == null ? "trades" : (sell ? "sell " : "buy ") + item + " " + count;
		}
	}

	/** 近くに落ちているアイテムを拾う。 */
	record Pickup() implements Command {
		public String describe() {
			return "pickup";
		}
	}

	record Say(String text) implements Command {
		public String describe() {
			return "say " + text;
		}
	}

	record Remember(String note) implements Command {
		public String describe() {
			return "remember " + note;
		}
	}
}
