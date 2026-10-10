package dev.ainakama.command;

import dev.ainakama.core.Addressee;
import dev.ainakama.core.QuickCommands;
import dev.ainakama.entity.ModEntities;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * チャットの言葉を、呼びかけられた仲間に届ける。だれに向けた言葉かは {@link Addressee} が読み取る。
 * プレイヤーが1人だけのときは、名前を付けなくても届く（「ついてきて」だけでよい）。
 * 相手は、最後に話しかけた仲間。まだだれにも話しかけていなければ、いちばん近くの仲間。
 * ほかのプレイヤーがいるときは、プレイヤーどうしの会話に反応しないよう、名前で始めた言葉だけを届ける。
 */
public final class ChatRouter {
	private static final double SEARCH_RANGE = 128;

	/** プレイヤーごとの、最後に話しかけた仲間。 */
	private static final Map<UUID, List<UUID>> LAST_TALKED = new HashMap<>();

	private ChatRouter() {
	}

	public static void onChat(ServerPlayer player, String raw) {
		ServerLevel level = player.level();
		List<NakamaEntity> near = level.getEntitiesOfClass(NakamaEntity.class,
			player.getBoundingBox().inflate(SEARCH_RANGE), e -> e.getType() == ModEntities.NAKAMA && e.isAlive());
		if (near.isEmpty()) {
			return;
		}
		Optional<Addressee.Target> target = Addressee.parse(raw, near.stream().map(NakamaEntity::baseName).toList(),
			canOmitName(player), text -> QuickCommands.match(text, Inv::itemExists).isPresent());
		if (target.isEmpty()) {
			return;
		}
		List<NakamaEntity> listeners = listeners(target.get(), player, near);
		LAST_TALKED.put(player.getUUID(), listeners.stream().map(NakamaEntity::getUUID).toList());
		listeners.forEach(n -> deliver(n, player, target.get().message()));
	}

	/** 名前を付けずに話しかけてよいか（プレイヤーが1人だけのとき）。 */
	public static boolean canOmitName(ServerPlayer player) {
		return player.level().getServer().getPlayerCount() == 1;
	}

	private static List<NakamaEntity> listeners(Addressee.Target target, ServerPlayer player, List<NakamaEntity> near) {
		return switch (target.kind()) {
			case EVERYONE -> near;
			case NAMED -> near.stream().filter(n -> n.baseName().equals(target.name())).limit(1).toList();
			case UNNAMED -> talkingTo(player, near);
		};
	}

	/** 最後に話しかけた仲間（近くにいる分）。いなければ、いちばん近くの仲間。 */
	private static List<NakamaEntity> talkingTo(ServerPlayer player, List<NakamaEntity> near) {
		List<UUID> last = LAST_TALKED.getOrDefault(player.getUUID(), List.of());
		List<NakamaEntity> known = near.stream().filter(n -> last.contains(n.getUUID())).toList();
		if (!known.isEmpty()) {
			return known;
		}
		return near.stream().min(Comparator.comparingDouble(n -> n.distanceToSqr(player))).stream().toList();
	}

	private static void deliver(NakamaEntity nakama, ServerPlayer player, String message) {
		// チャット欄にプレイヤーの発言が出たあとで返事をするよう、次のtickに回す
		player.level().getServer().execute(() -> nakama.onPlayerMessage(player, message));
	}
}
