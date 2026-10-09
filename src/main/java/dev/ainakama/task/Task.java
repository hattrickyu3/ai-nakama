package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.entity.NakamaEntity;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** 1つの命令を、何tickもかけて実行するもの。毎tick呼ばれる。 */
public interface Task {
	sealed interface Result {
		/** まだ続く。 */
		record Running() implements Result {
		}

		/** 終わった。 */
		record Done() implements Result {
		}

		/** プログラムでは直せない失敗。reason はプレイヤーやAIに伝える説明。 */
		record Failed(String reason) implements Result {
		}

		/** 先にこれをやれば続けられる（材料や道具が足りないときなど）。実行側が前に差し込んでやり直す。 */
		record Needs(List<Command> first, String why) implements Result {
		}
	}

	Result RUNNING = new Result.Running();
	Result DONE = new Result.Done();

	Result tick(NakamaEntity nakama, ServerLevel level);

	/** 状況の要約に出す、今の作業の説明。 */
	String describe();

	/** やめるときの後片付け（ブロックを壊す途中の表示を消すなど）。 */
	default void cancel(NakamaEntity nakama, ServerLevel level) {
	}
}
