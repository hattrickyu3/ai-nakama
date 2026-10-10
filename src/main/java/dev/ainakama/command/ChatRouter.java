package dev.ainakama.command;

import dev.ainakama.core.Addressee;
import dev.ainakama.core.Command;
import dev.ainakama.core.Plan;
import dev.ainakama.core.QuickCommands;
import dev.ainakama.core.Roster;
import dev.ainakama.entity.ModEntities;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Whereabouts;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * チャットの言葉を、呼びかけられた仲間に届ける。だれに向けた言葉かは {@link Addressee} が読み取る。
 * プレイヤーが1人だけのときは、名前を付けなくても届く（「ついてきて」だけでよい）。
 * 相手は、最後に話しかけた仲間。まだだれにも話しかけていなければ、いちばん近くの仲間。
 * ほかのプレイヤーがいるときは、プレイヤーどうしの会話に反応しないよう、名前で始めた言葉だけを届ける。
 * 声が届くのは {@link #SEARCH_RANGE} マスまで。もっと遠くの仲間に話しかけたときは、黙ったままにせず、
 * 遠くて聞こえないことを知らせる。「こっちに来て」と言われたら、そばへ呼び寄せる。
 */
public final class ChatRouter {
	private static final double SEARCH_RANGE = 128;

	/** 「来て」のほかに、遠くの仲間を呼び寄せる言い方。 */
	private static final Pattern CALL = Pattern.compile("(tp|ｔｐ|テレポ|ワープ)", Pattern.CASE_INSENSITIVE);

	/** プレイヤーごとの、最後に話しかけた仲間。 */
	private static final Map<UUID, List<UUID>> LAST_TALKED = new HashMap<>();

	private ChatRouter() {
	}

	public static void onChat(ServerPlayer player, String raw) {
		ServerLevel level = player.level();
		List<NakamaEntity> near = level.getEntitiesOfClass(NakamaEntity.class,
			player.getBoundingBox().inflate(SEARCH_RANGE), e -> e.getType() == ModEntities.NAKAMA && e.isAlive());
		Map<UUID, Roster.Entry> far = farFrom(near);
		List<String> names = Stream.concat(near.stream().map(NakamaEntity::baseName), far.values().stream().map(Roster.Entry::name))
			.distinct().toList();
		if (names.isEmpty()) {
			return;
		}
		Optional<Addressee.Target> target = Addressee.parse(raw, names,
			canOmitName(player), text -> QuickCommands.match(text, Inv::itemExists).isPresent());
		if (target.isEmpty()) {
			return;
		}
		String message = target.get().message();
		List<NakamaEntity> listeners = listeners(target.get(), player, near);
		if (!listeners.isEmpty()) {
			LAST_TALKED.put(player.getUUID(), listeners.stream().map(NakamaEntity::getUUID).toList());
			listeners.forEach(n -> deliver(n, player, message));
			return;
		}
		Map<UUID, Roster.Entry> away = farListeners(target.get(), player, far);
		if (away.isEmpty()) {
			return;
		}
		LAST_TALKED.put(player.getUUID(), List.copyOf(away.keySet()));
		boolean call = isCall(message);
		away.forEach((id, entry) -> {
			if (call) {
				Whereabouts.summon(player, id, entry, message);
			} else {
				Whereabouts.tell(player, tooFar(player, entry));
			}
		});
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

	/** 名簿にいる仲間のうち、声の届くところにいない仲間。 */
	private static Map<UUID, Roster.Entry> farFrom(List<NakamaEntity> near) {
		Map<UUID, Roster.Entry> far = new LinkedHashMap<>(Whereabouts.roster().all());
		near.forEach(n -> far.remove(n.getUUID()));
		return far;
	}

	/** 声の届かないところにいる仲間のうち、この言葉の相手。 */
	private static Map<UUID, Roster.Entry> farListeners(Addressee.Target target, ServerPlayer player, Map<UUID, Roster.Entry> far) {
		Map<UUID, Roster.Entry> found = new LinkedHashMap<>();
		switch (target.kind()) {
			case EVERYONE -> found.putAll(far);
			case NAMED -> far.entrySet().stream().filter(e -> e.getValue().name().equals(target.name())).limit(1)
				.forEach(e -> found.put(e.getKey(), e.getValue()));
			case UNNAMED -> {
				List<UUID> last = LAST_TALKED.getOrDefault(player.getUUID(), List.of());
				far.entrySet().stream().filter(e -> last.contains(e.getKey())).forEach(e -> found.put(e.getKey(), e.getValue()));
				if (found.isEmpty()) {
					far.entrySet().stream()
						.min(Comparator.comparingInt(e -> e.getValue().blocksFrom(player.getX(), player.getZ())))
						.ifPresent(e -> found.put(e.getKey(), e.getValue()));
				}
			}
		}
		return found;
	}

	/** 「こっちに来て」「ついてきて」「tpして」のように、そばに来てほしいという言葉か。 */
	private static boolean isCall(String message) {
		List<Command> commands = QuickCommands.match(message, Inv::itemExists)
			.flatMap(QuickCommands.Match::plan).map(Plan::commands).orElse(List.of());
		return commands.equals(List.of(new Command.Follow())) || CALL.matcher(message).find();
	}

	private static String tooFar(ServerPlayer player, Roster.Entry entry) {
		return entry.name() + "は遠くにいて聞こえないみたい（座標 " + entry.x() + " " + entry.y() + " " + entry.z()
			+ "、ここから約" + entry.blocksFrom(player.getX(), player.getZ()) + "マス）。「こっちに来て」と言うと、そばに呼べるよ。";
	}

	private static void deliver(NakamaEntity nakama, ServerPlayer player, String message) {
		// チャット欄にプレイヤーの発言が出たあとで返事をするよう、次のtickに回す
		player.level().getServer().execute(() -> nakama.onPlayerMessage(player, message));
	}
}
