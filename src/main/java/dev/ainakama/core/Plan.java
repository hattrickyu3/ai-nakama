package dev.ainakama.core;

import java.util.List;

/**
 * AI（または決まった言い方の指示）から得た計画。
 *
 * @param say      仲間の返事。空文字なら何も言わない
 * @param commands 実行する命令の並び
 * @param memo     覚えておく事実。なければ null
 * @param ask      やり方がわからないときにプレイヤーへ聞く質問。なければ null
 * @param teach    プレイヤーに教わった技（名前と手順）。なければ null
 * @param rule     プレイヤーに教わった「〇〇したら△△する」の決まり。なければ null
 */
public record Plan(String say, List<Command> commands, String memo, String ask, Skill teach, Rule rule) {
	/** 名前の付いた手順。一度覚えれば、AIを使わずに実行できる。 */
	public record Skill(String name, List<Command> steps) {
		public Skill {
			steps = List.copyOf(steps);
		}
	}

	/** きっかけ（when）が起きたら steps を実行する決まり。 */
	public record Rule(Trigger when, List<Command> steps) {
		public Rule {
			steps = List.copyOf(steps);
		}
	}

	public Plan {
		say = say == null ? "" : say;
		commands = List.copyOf(commands);
	}

	public Plan(String say, List<Command> commands, String memo) {
		this(say, commands, memo, null, null, null);
	}

	public static Plan of(Command... commands) {
		return new Plan("", List.of(commands), null);
	}
}
