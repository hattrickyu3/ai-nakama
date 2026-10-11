package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.entity.NakamaEntity;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * 命令の列を1つずつ実行する。
 * タスクが「先にこれをやって」と返したら、それを前に差し込んでからやり直す（材料集めなどの自動の立て直し）。
 * 差し込みが深くなりすぎたら失敗として扱い、仲間に知らせる。
 */
public final class TaskRunner {
	/**
	 * 差し込みの深さの上限。何も持たずに鉄の道具を頼まれたときがいちばん深く、
	 * 鉄の道具 ← 焼く ← かまどを置く ← かまどを作る ← 丸石 ← 木のツルハシ ← 原木、で6段。
	 * 「〇〇を用意して」（ObtainTask）から始まると、その分で1段増える。
	 */
	public static final int MAX_DEPTH = 7;

	/**
	 * 同じ命令を「先にやること」のあとでやり直す回数の上限。材料集めが空回りし続けないようにする。
	 * 建築は「材料 → ツルハシ → 足元の土 → 足場の土」と別々の理由で何度かやり直すことがあるので、少し多めにしている。
	 */
	public static final int MAX_RETRIES = 4;

	/**
	 * @param depth   差し込みの深さ
	 * @param retries この命令をやり直した回数
	 */
	private record Entry(Command command, int depth, int retries) {
	}

	/** 失敗や完了を受け取る側（仲間）。 */
	public interface Listener {
		void onTaskFailed(Command command, String reason);

		void onAllDone(boolean didWork);
	}

	private final Deque<Entry> queue = new ArrayDeque<>();
	private Entry currentEntry;
	private Task current;
	private boolean didWork;

	/** 今の作業をやめて、新しい命令の列に入れ替える。 */
	public void replace(List<Command> commands, NakamaEntity nakama, ServerLevel level) {
		clear(nakama, level);
		for (Command c : commands) {
			queue.addLast(new Entry(c, 0, 0));
		}
	}

	/** 今の作業と残りの予定はそのままに、そのあとにやることを足す。 */
	public void append(List<Command> commands) {
		for (Command c : commands) {
			queue.addLast(new Entry(c, 0, 0));
		}
	}

	/** 今の作業をいったん止めて commands を先にやる。終わったら、今の作業の続きに戻る。 */
	public void interrupt(List<Command> commands, NakamaEntity nakama, ServerLevel level) {
		if (current != null) {
			Entry entry = currentEntry;
			// 続きから再開できるタスクは、残りの分だけの命令に置き換わる（集める数の残りなど）
			Command rest = current.pinned(entry.command());
			current.cancel(nakama, level);
			finishCurrent();
			queue.addFirst(new Entry(rest, entry.depth(), entry.retries()));
			nakama.getNavigation().stop();
		}
		for (int i = commands.size() - 1; i >= 0; i--) {
			queue.addFirst(new Entry(commands.get(i), 0, 0));
		}
	}

	public void clear(NakamaEntity nakama, ServerLevel level) {
		if (current != null) {
			current.cancel(nakama, level);
		}
		current = null;
		currentEntry = null;
		queue.clear();
		didWork = false;
		nakama.getNavigation().stop();
	}

	public boolean isIdle() {
		return current == null && queue.isEmpty();
	}

	public String describeCurrent() {
		if (current == null) {
			return "なし";
		}
		int left = queue.size();
		return current.describe() + (left > 0 ? "（このあと" + left + "個）" : "");
	}

	public void tick(NakamaEntity nakama, ServerLevel level, Listener listener) {
		if (current == null) {
			if (queue.isEmpty()) {
				return;
			}
			currentEntry = queue.pollFirst();
			current = Tasks.create(currentEntry.command(), nakama, level);
		}
		Task.Result result = current.tick(nakama, level);
		switch (result) {
			case Task.Result.Running r -> {
			}
			case Task.Result.Done d -> {
				if (!(currentEntry.command() instanceof Command.Say) && !(currentEntry.command() instanceof Command.Remember)) {
					didWork = true;
				}
				finishCurrent();
				if (queue.isEmpty()) {
					boolean worked = didWork;
					didWork = false;
					listener.onAllDone(worked);
				}
			}
			case Task.Result.Needs n -> {
				Entry entry = currentEntry;
				// 「ここ」「プレイヤーのそば」は最初に決まった座標に固定する（やり直しで別の場所に建てないため）
				Command pinned = current.pinned(entry.command());
				current.cancel(nakama, level);
				finishCurrent();
				if (entry.depth() >= MAX_DEPTH || entry.retries() >= MAX_RETRIES) {
					fail(entry, n.why(), nakama, level, listener);
					return;
				}
				// 元の命令をやり直すために先頭へ戻し、その前に「先にやること」を並べる
				queue.addFirst(new Entry(pinned, entry.depth(), entry.retries() + 1));
				List<Command> first = n.first();
				for (int i = first.size() - 1; i >= 0; i--) {
					queue.addFirst(new Entry(first.get(i), entry.depth() + 1, 0));
				}
			}
			case Task.Result.Failed f -> fail(currentEntry, f.reason(), nakama, level, listener);
		}
	}

	private void fail(Entry entry, String reason, NakamaEntity nakama, ServerLevel level, Listener listener) {
		// 失敗した命令の元になった計画全体をやめて、判断を仲間（必要ならAI）に任せる
		Command root = entry.command();
		clear(nakama, level);
		listener.onTaskFailed(root, reason);
	}

	private void finishCurrent() {
		current = null;
		currentEntry = null;
	}
}
