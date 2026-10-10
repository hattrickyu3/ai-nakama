package dev.ainakama.core;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * チャットの言葉が、だれに向けたものかを読み取る。
 * 「ミナ、小屋を建てて」「ミナ小屋を建てて」「@ミナ 小屋を建てて」のように、名前で始めると、その仲間に届く。
 * 「みんな」「皆」「全員」で近くの全員。
 * 名前を省いてよいとき（プレイヤーが1人だけのとき）は、名前のない言葉も仲間に向けたものとして受け取る。
 */
public final class Addressee {
	private static final List<String> EVERYONE = List.of("みんな", "皆", "全員", "all", "everyone");

	/** 仲間の名前のあとに来てよい区切り。 */
	private static final String SEPARATORS = " 　、,，:：!！";

	public enum Kind {
		/** 近くの全員。 */
		EVERYONE,
		/** 名前で呼ばれた仲間。 */
		NAMED,
		/** 名前なし。いま話している相手に届ける。 */
		UNNAMED
	}

	/** name は NAMED のときだけ入る（仲間の名前）。 */
	public record Target(Kind kind, String name, String message) {
	}

	private Addressee() {
	}

	/**
	 * @param names 近くにいる仲間の名前
	 * @param nameOptional 名前を省いてよいか
	 * @param isQuickCommand 決まった言い方かどうか
	 */
	public static Optional<Target> parse(String raw, List<String> names, boolean nameOptional, Predicate<String> isQuickCommand) {
		String text = raw.strip();
		boolean at = text.startsWith("@") || text.startsWith("＠");
		String body = at ? text.substring(1).strip() : text;

		for (String all : EVERYONE) {
			if (!startsWithName(body, all)) {
				continue;
			}
			String rest = afterName(body, all);
			if (rest.isEmpty()) {
				return Optional.empty();
			}
			// 「皆で行こう」「everyone is here」のような普通の会話に全員が反応しないよう、
			// 区切りがないときは、決まった言い方（「皆ついてきて」など）のときだけ届ける
			if (at || hasSeparator(body, all) || isQuickCommand.test(rest)) {
				return Optional.of(new Target(Kind.EVERYONE, "", rest));
			}
		}
		// 「ミナ」と「ミナト」がいるとき、「ミナト、…」がミナに届かないよう、長い名前から確かめる
		List<String> longestFirst = names.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
		for (String name : longestFirst) {
			if (!startsWithName(body, name)) {
				continue;
			}
			// 「ミナ石を掘って」のように、区切りなしで名前に続けて話しかけてもよい
			String rest = afterName(body, name);
			return rest.isEmpty() ? Optional.empty() : Optional.of(new Target(Kind.NAMED, name, rest));
		}
		return nameOptional && !body.isEmpty() ? Optional.of(new Target(Kind.UNNAMED, "", body)) : Optional.empty();
	}

	private static boolean startsWithName(String body, String name) {
		return !name.isBlank() && body.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT));
	}

	/** 名前と、そのあとの区切りを取り除いた残り。 */
	private static String afterName(String body, String name) {
		return body.substring(name.length()).replaceFirst("^[" + SEPARATORS + "]+", "").strip();
	}

	private static boolean hasSeparator(String body, String name) {
		return body.length() > name.length() && SEPARATORS.indexOf(body.charAt(name.length())) >= 0;
	}
}
