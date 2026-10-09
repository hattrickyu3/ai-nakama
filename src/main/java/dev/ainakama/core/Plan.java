package dev.ainakama.core;

import java.util.List;

/**
 * AI（または決まった言い方の指示）から得た計画。
 *
 * @param say      仲間の返事。空文字なら何も言わない
 * @param commands 実行する命令の並び
 * @param memo     覚えておく事実。なければ null
 */
public record Plan(String say, List<Command> commands, String memo) {
	public Plan {
		say = say == null ? "" : say;
		commands = List.copyOf(commands);
	}

	public static Plan of(Command... commands) {
		return new Plan("", List.of(commands), null);
	}
}
