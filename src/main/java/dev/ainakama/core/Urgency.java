package dev.ainakama.core;

import java.util.List;

/**
 * 作業中に新しい頼みが来たとき、今の作業をどうするか。
 * 前は、新しい頼みが来るたびに今の作業と残りの予定を全部やめていたので、
 * 「木と石を集めて」の途中で話しかけると石のことを忘れていた。
 */
public enum Urgency {
	/** 今の作業と残りの予定をやめて、切り替える（止まって・ついてきて・守って・帰って）。 */
	SWITCH,
	/** 先にこれをやって、終わったら今の作業の続きに戻る（渡して・しまって・敵を倒して）。 */
	NOW,
	/** 今の作業と残りの予定が終わってからやる。 */
	LATER;

	public static Urgency of(List<Command> commands) {
		if (commands.isEmpty()) {
			return LATER;
		}
		Command first = commands.get(0);
		if (first instanceof Command.Stop || first instanceof Command.Follow || first instanceof Command.Guard) {
			return SWITCH;
		}
		if (commands.size() == 1 && first instanceof Command.MoveTo) {
			return SWITCH;
		}
		return commands.stream().allMatch(Urgency::isQuick) ? NOW : LATER;
	}

	private static boolean isQuick(Command c) {
		return c instanceof Command.Give || c instanceof Command.Store || c instanceof Command.Fight
			|| c instanceof Command.Say || c instanceof Command.Remember;
	}
}
