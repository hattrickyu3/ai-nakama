package dev.ainakama.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 仲間の記憶。覚えたメモ（最大20件、古いものから消える）と、最近の会話（最大3往復）。
 * 変更すると新しいインスタンスを返す。
 */
public record Memory(List<String> notes, List<Exchange> recent) {
	public static final int MAX_NOTES = 20;
	public static final int MAX_RECENT = 3;

	/** 会話の1往復。reply は仲間の返事。 */
	public record Exchange(String player, String reply) {
	}

	public Memory {
		notes = List.copyOf(notes);
		recent = List.copyOf(recent);
	}

	public static Memory empty() {
		return new Memory(List.of(), List.of());
	}

	public Memory withNote(String note) {
		if (note == null || note.isBlank() || notes.contains(note)) {
			return this;
		}
		List<String> next = new ArrayList<>(notes);
		next.add(note.strip());
		while (next.size() > MAX_NOTES) {
			next.remove(0);
		}
		return new Memory(next, recent);
	}

	public Memory withExchange(String player, String reply) {
		List<Exchange> next = new ArrayList<>(recent);
		next.add(new Exchange(player, reply));
		while (next.size() > MAX_RECENT) {
			next.remove(0);
		}
		return new Memory(notes, next);
	}
}
