package dev.ainakama.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 仲間の記憶。教わった知識（最大200件、古いものから消える）と、最近の会話（最大3往復）。
 * 変更すると新しいインスタンスを返す。
 * 知識は全部をAIに渡さず、今回の言葉に関係がありそうなものと新しいものだけを選んで渡す（トークンを増やさないため）。
 */
public record Memory(List<String> notes, List<Exchange> recent) {
	public static final int MAX_NOTES = 200;
	public static final int MAX_RECENT = 3;
	/** 1回のやりとりでAIに渡す知識の数。 */
	public static final int NOTES_PER_PROMPT = 6;
	private static final int ALWAYS_RECENT_NOTES = 2;

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
		if (note == null || note.isBlank() || notes.contains(note.strip())) {
			return this;
		}
		List<String> next = new ArrayList<>(notes);
		next.add(note.strip());
		while (next.size() > MAX_NOTES) {
			next.remove(0);
		}
		return new Memory(next, recent);
	}

	public Memory withoutNote(String note) {
		List<String> next = new ArrayList<>(notes);
		return next.remove(note) ? new Memory(next, recent) : this;
	}

	public Memory withExchange(String player, String reply) {
		List<Exchange> next = new ArrayList<>(recent);
		next.add(new Exchange(player, reply));
		while (next.size() > MAX_RECENT) {
			next.remove(0);
		}
		return new Memory(notes, next);
	}

	/** 今回の言葉に関係がありそうな知識と、いちばん新しい知識を合わせて、最大 {@link #NOTES_PER_PROMPT} 件。 */
	public List<String> relevantNotes(String query) {
		if (notes.size() <= NOTES_PER_PROMPT) {
			return notes;
		}
		Set<String> picked = new LinkedHashSet<>(Similarity.top(query, notes, NOTES_PER_PROMPT - ALWAYS_RECENT_NOTES));
		for (int i = notes.size() - 1; i >= 0 && picked.size() < NOTES_PER_PROMPT; i--) {
			picked.add(notes.get(i));
		}
		return List.copyOf(picked);
	}
}
