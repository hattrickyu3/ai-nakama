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
