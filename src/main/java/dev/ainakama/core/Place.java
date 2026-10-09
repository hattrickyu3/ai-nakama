package dev.ainakama.core;

import java.util.Locale;
import java.util.Optional;

/** 命令で使う場所。座標の解決はゲーム側（world パッケージ）で行う。 */
public sealed interface Place {
	String describe();

	record Home() implements Place {
		public String describe() {
			return "home";
		}
	}

	record NearPlayer() implements Place {
		public String describe() {
			return "player";
		}
	}

	record Here() implements Place {
		public String describe() {
			return "here";
		}
	}

	record Coords(int x, int y, int z) implements Place {
		public String describe() {
			return x + " " + y + " " + z;
		}
	}

	/** "home" "拠点" "player" "12 64 -30" などを読む。読めなければ空。 */
	static Optional<Place> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String s = raw.trim().toLowerCase(Locale.ROOT);
		switch (s) {
			case "home", "base", "拠点", "家" -> {
				return Optional.of(new Home());
			}
			case "player", "プレイヤー", "あなた", "you" -> {
				return Optional.of(new NearPlayer());
			}
			case "here", "ここ", "self", "" -> {
				return Optional.of(new Here());
			}
			default -> {
				return parseCoords(s);
			}
		}
	}

	private static Optional<Place> parseCoords(String s) {
		String[] parts = s.replace(",", " ").trim().split("\\s+");
		if (parts.length != 3) {
			return Optional.empty();
		}
		try {
			return Optional.of(new Coords(
				(int) Math.floor(Double.parseDouble(parts[0])),
				(int) Math.floor(Double.parseDouble(parts[1])),
				(int) Math.floor(Double.parseDouble(parts[2]))));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}
}
