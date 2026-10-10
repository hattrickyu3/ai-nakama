package dev.ainakama.core;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日本語の数の言い方を読む。「10」「十」「二十五」「一スタック」「半スタック」「2スタック」など。
 * 1スタックは64個として数える。
 */
public final class Quantity {
	public static final int STACK = 64;

	/** 数の部分にあたる正規表現（QuickCommands のパターンに埋め込んで使う）。 */
	public static final String PATTERN = "(?:\\d{1,4}|[一二三四五六七八九十百]+|半)?(?:スタック)?";

	private static final Pattern PARTS = Pattern.compile("^(\\d{1,4}|[一二三四五六七八九十百]+|半)?(スタック)?$");
	private static final String DIGITS = "一二三四五六七八九";

	private Quantity() {
	}

	/** 読めなければ空。空文字も空（数の指定なし）。 */
	public static Optional<Integer> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		Matcher m = PARTS.matcher(raw.trim());
		if (!m.matches()) {
			return Optional.empty();
		}
		String num = m.group(1);
		boolean stack = m.group(2) != null;
		if (num == null) {
			return stack ? Optional.of(STACK) : Optional.empty();
		}
		if (num.equals("半")) {
			return stack ? Optional.of(STACK / 2) : Optional.empty();
		}
		int n = Character.isDigit(num.charAt(0)) ? Integer.parseInt(num) : kanji(num);
		return n <= 0 ? Optional.empty() : Optional.of(stack ? n * STACK : n);
	}

	/** 一〜九百九十九までの漢数字。 */
	private static int kanji(String s) {
		int total = 0;
		int current = 0;
		for (char c : s.toCharArray()) {
			int d = DIGITS.indexOf(c);
			if (d >= 0) {
				current = d + 1;
			} else if (c == '十') {
				total += (current == 0 ? 1 : current) * 10;
				current = 0;
			} else if (c == '百') {
				total += (current == 0 ? 1 : current) * 100;
				current = 0;
			}
		}
		return total + current;
	}
}
