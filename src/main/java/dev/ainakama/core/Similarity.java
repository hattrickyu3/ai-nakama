package dev.ainakama.core;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 2つの文がどのくらい似ているかを、2文字ずつの重なりで測る（日本語は単語の区切りがないので、この方法が手軽で強い）。
 * 「今回の頼みに関係がありそうな技・知識だけをAIに渡す」ために使う。
 */
public final class Similarity {
	private Similarity() {
	}

	/** 0（まったく違う）〜1（同じ）。query の2文字組のうち、text にも出てくる割合。 */
	public static double score(String query, String text) {
		Set<String> q = bigrams(query);
		if (q.isEmpty()) {
			return 0;
		}
		Set<String> t = bigrams(text);
		long hit = q.stream().filter(t::contains).count();
		return (double) hit / q.size();
	}

	/** items のうち query に似ているものを、似ている順に最大 max 個。まったく重ならないものは入れない。 */
	public static List<String> top(String query, List<String> items, int max) {
		return items.stream()
			.filter(i -> score(i, query) > 0 || score(query, i) > 0)
			.sorted(Comparator.comparingDouble((String i) -> -Math.max(score(i, query), score(query, i))))
			.limit(max)
			.toList();
	}

	private static Set<String> bigrams(String s) {
		String n = s.replaceAll("[\\s、。!！?？「」]", "");
		Set<String> set = new HashSet<>();
		for (int i = 0; i + 2 <= n.length(); i++) {
			set.add(n.substring(i, i + 2));
		}
		return set;
	}
}
