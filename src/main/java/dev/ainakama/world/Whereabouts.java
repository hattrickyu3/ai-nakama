package dev.ainakama.world;

import dev.ainakama.AiNakama;
import dev.ainakama.core.Roster;
import dev.ainakama.entity.NakamaEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 仲間の居場所を覚えておき、遠くにいる仲間をプレイヤーのそばへ呼び寄せる。
 * 遠くの仲間は、まわりの土地ごと読み込まれていないことがある。そのときは、最後にいた場所の土地を
 * 一時的に読み込んで、仲間が現れるのを待ってから連れてくる。
 */
public final class Whereabouts {
	private static final String FILE = "ainakama-roster.json";
	private static final int SAVE_INTERVAL = 20 * 30;
	private static final int SUMMON_TIMEOUT = 20 * 10;

	/** 呼び寄せている途中の仲間。forced は、この呼び寄せのために土地を読み込んだままにしているか。 */
	private record Pending(UUID nakama, UUID player, String dimension, int chunkX, int chunkZ, boolean forced, int deadline,
		String name, String message) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();
	private static Roster roster = new Roster();

	private Whereabouts() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(Whereabouts::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			PENDING.forEach(p -> release(server, p));
			PENDING.clear();
			save(server);
		});
		ServerTickEvents.END_SERVER_TICK.register(Whereabouts::tick);
	}

	public static Roster roster() {
		return roster;
	}

	/** 仲間が今いる場所を覚える。 */
	public static void seen(NakamaEntity nakama) {
		roster.put(nakama.getUUID(), new Roster.Entry(nakama.baseName(), dimensionOf(nakama.level()),
			nakama.blockPosition().getX(), nakama.blockPosition().getY(), nakama.blockPosition().getZ()));
	}

	/** いなくなった仲間を名簿から消す。 */
	public static void forget(UUID id) {
		roster.remove(id);
	}

	/**
	 * 遠くにいる仲間を、プレイヤーのそばへ呼ぶ。着いたら message を、話しかけられた言葉として渡す。
	 */
	public static void summon(ServerPlayer player, UUID id, Roster.Entry entry, String message) {
		ServerLevel level = levelOf(player.level().getServer(), entry.dimension());
		if (level == null || level != player.level()) {
			tell(player, entry.name() + "は別の世界にいるので、ここには呼べないみたい。");
			return;
		}
		if (arrive(level, id, player, message)) {
			return;
		}
		if (PENDING.stream().anyMatch(p -> p.nakama().equals(id))) {
			return;
		}
		int chunkX = entry.x() >> 4;
		int chunkZ = entry.z() >> 4;
		boolean forced = level.setChunkForced(chunkX, chunkZ, true);
		PENDING.add(new Pending(id, player.getUUID(), entry.dimension(), chunkX, chunkZ, forced,
			level.getServer().getTickCount() + SUMMON_TIMEOUT, entry.name(), message));
		tell(player, entry.name() + "を呼んでいるよ…");
	}

	public static void tell(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text));
	}

	private static void tick(MinecraftServer server) {
		for (Iterator<Pending> it = PENDING.iterator(); it.hasNext();) {
			Pending p = it.next();
			ServerLevel level = levelOf(server, p.dimension());
			ServerPlayer player = server.getPlayerList().getPlayer(p.player());
			boolean gaveUp = level == null || player == null || server.getTickCount() > p.deadline();
			if (!gaveUp && !arrive(level, p.nakama(), player, p.message())) {
				continue;
			}
			it.remove();
			release(server, p);
			if (gaveUp && player != null) {
				tell(player, p.name() + "を呼べなかった…。最後にいた場所に見当たらないみたい。");
			}
		}
		if (server.getTickCount() % SAVE_INTERVAL == 0 && roster.takeDirty()) {
			save(server);
		}
	}

	/** 仲間が読み込まれていれば、プレイヤーのそばへ移して言葉を渡す。 */
	private static boolean arrive(ServerLevel level, UUID id, ServerPlayer player, String message) {
		if (player.level() != level || !(level.getEntity(id) instanceof NakamaEntity nakama) || !nakama.isAlive()) {
			return false;
		}
		nakama.getNavigation().stop();
		nakama.snapTo(player.getX(), player.getY(), player.getZ(), nakama.getYRot(), nakama.getXRot());
		seen(nakama);
		nakama.onPlayerMessage(player, message);
		return true;
	}

	private static void release(MinecraftServer server, Pending p) {
		ServerLevel level = levelOf(server, p.dimension());
		if (p.forced() && level != null) {
			level.setChunkForced(p.chunkX(), p.chunkZ(), false);
		}
	}

	private static String dimensionOf(net.minecraft.world.level.Level level) {
		return level.dimension().toString();
	}

	private static ServerLevel levelOf(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (dimensionOf(level).equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
	}

	private static void load(MinecraftServer server) {
		PENDING.clear();
		Path file = file(server);
		try {
			roster = Files.exists(file) ? Roster.fromJson(Files.readString(file, StandardCharsets.UTF_8)) : new Roster();
		} catch (IOException e) {
			AiNakama.LOGGER.warn("仲間の名簿を読めませんでした: {}", e.toString());
			roster = new Roster();
		}
	}

	private static void save(MinecraftServer server) {
		try {
			Files.writeString(file(server), roster.toJson(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AiNakama.LOGGER.warn("仲間の名簿を保存できませんでした: {}", e.toString());
		}
	}
}
