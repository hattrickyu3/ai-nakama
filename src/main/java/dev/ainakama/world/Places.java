package dev.ainakama.world;

import dev.ainakama.core.Place;
import dev.ainakama.entity.NakamaEntity;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/** 命令の場所（home / player / here / 座標）を、ゲームの中の座標にする。 */
public final class Places {
	private static final double PLAYER_SEARCH_RANGE = 128;

	private Places() {
	}

	public static Optional<BlockPos> resolve(Place place, NakamaEntity nakama, ServerLevel level) {
		return switch (place) {
			case Place.Home h -> Optional.ofNullable(nakama.home());
			case Place.Here h -> Optional.of(nakama.blockPosition());
			case Place.Coords c -> Optional.of(new BlockPos(c.x(), c.y(), c.z()));
			case Place.NearPlayer p -> nearestPlayer(nakama, level).map(Player::blockPosition);
		};
	}

	public static Optional<Player> nearestPlayer(NakamaEntity nakama, ServerLevel level) {
		Player speaker = nakama.lastSpeaker(level);
		if (speaker != null) {
			return Optional.of(speaker);
		}
		return Optional.ofNullable(level.getNearestPlayer(nakama, PLAYER_SEARCH_RANGE));
	}
}
