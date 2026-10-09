package dev.ainakama.command;

import dev.ainakama.entity.ModEntities;
import dev.ainakama.entity.NakamaEntity;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * チャットの言葉を、呼びかけられた仲間に届ける。
 * 「@ミナ 小屋を建てて」または「ミナ、小屋を建てて」の形で呼びかける。「@みんな」で近くの全員。
 */
public final class ChatRouter {
	private static final double SEARCH_RANGE = 128;
	private static final List<String> EVERYONE = List.of("みんな", "all", "everyone");

	/** 仲間の名前のあとに来てよい区切り。 */
	private static final String SEPARATORS = " 　、,，:：!！";

	private ChatRouter() {
	}

	public static void onChat(ServerPlayer player, String raw) {
		ServerLevel level = player.level();
		String text = raw.strip();
		List<NakamaEntity> near = level.getEntitiesOfClass(NakamaEntity.class,
			player.getBoundingBox().inflate(SEARCH_RANGE), e -> e.getType() == ModEntities.NAKAMA && e.isAlive());
		if (near.isEmpty()) {
			return;
		}
		boolean at = text.startsWith("@") || text.startsWith("＠");
		String body = at ? text.substring(1) : text;

		for (String all : EVERYONE) {
			Optional<String> rest = afterName(body, all, at);
			if (rest.isPresent()) {
				near.forEach(n -> deliver(n, player, rest.get()));
				return;
			}
		}
		for (NakamaEntity n : near) {
			Optional<String> rest = afterName(body, n.baseName(), at);
			if (rest.isPresent()) {
				deliver(n, player, rest.get());
				return;
			}
		}
	}

	/** body が name で始まり、その後に区切りがあれば、残りの言葉を返す。@付きなら区切りなしでもよい。 */
	static Optional<String> afterName(String body, String name, boolean at) {
		if (name.isBlank() || !body.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))) {
			return Optional.empty();
		}
		String rest = body.substring(name.length());
		if (rest.isEmpty()) {
			return Optional.empty();
		}
		boolean separated = SEPARATORS.indexOf(rest.charAt(0)) >= 0;
		if (!separated && !at) {
			return Optional.empty();
		}
		String message = rest.replaceFirst("^[" + SEPARATORS + "]+", "").strip();
		return message.isEmpty() ? Optional.empty() : Optional.of(message);
	}

	private static void deliver(NakamaEntity nakama, ServerPlayer player, String message) {
		// チャット欄にプレイヤーの発言が出たあとで返事をするよう、次のtickに回す
		player.level().getServer().execute(() -> nakama.onPlayerMessage(player, message));
	}
}
