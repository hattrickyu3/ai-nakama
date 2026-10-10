package dev.ainakama.world;

import dev.ainakama.core.Command;
import dev.ainakama.core.Place;
import dev.ainakama.entity.NakamaEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** 命令の場所（home / player / here / self / 座標）を、ゲームの中の座標にする。 */
public final class Places {
	private static final double PLAYER_SEARCH_RANGE = 128;
	/** 「ここ」と言ったときに、プレイヤーの視線の先として見る距離。 */
	private static final double LOOK_RANGE = 8;

	private Places() {
	}

	public static Optional<BlockPos> resolve(Place place, NakamaEntity nakama, ServerLevel level) {
		return switch (place) {
			case Place.Home h -> Optional.ofNullable(nakama.home());
			case Place.Self s -> Optional.of(nakama.blockPosition());
			case Place.Coords c -> Optional.of(new BlockPos(c.x(), c.y(), c.z()));
			case Place.Exact e -> Optional.of(new BlockPos(e.x(), e.y(), e.z()));
			case Place.NearPlayer p -> nearestPlayer(nakama, level).map(Player::blockPosition);
			case Place.Looking l -> nearestPlayer(nakama, level).map(Places::lookedAt);
		};
	}

	/** プレイヤーが見ているブロックの、手前の空いているマス。何も見ていなければ足元。 */
	private static BlockPos lookedAt(Player player) {
		HitResult hit = player.pick(LOOK_RANGE, 1.0F, false);
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			return block.getBlockPos().relative(block.getDirection());
		}
		return player.blockPosition();
	}

	public static Optional<Player> nearestPlayer(NakamaEntity nakama, ServerLevel level) {
		Player speaker = nakama.lastSpeaker(level);
		if (speaker != null) {
			return Optional.of(speaker);
		}
		return Optional.ofNullable(level.getNearestPlayer(nakama, PLAYER_SEARCH_RANGE));
	}

	/**
	 * 「ここ」「プレイヤーのそば」を、言われた瞬間の座標に置き換える。
	 * 材料を集めに行っている間にプレイヤーが動いても、言われた場所に建てられるようにするため。
	 * 「プレイヤーのところへ行く」（move_to player）だけは、行く時点の場所を使いたいのでそのままにする。
	 */
	public static List<Command> pin(List<Command> commands, NakamaEntity nakama, ServerLevel level) {
		return commands.stream().map(c -> pin(c, nakama, level)).toList();
	}

	private static Command pin(Command command, NakamaEntity nakama, ServerLevel level) {
		return switch (command) {
			case Command.Build b when isRelative(b.place()) -> new Command.Build(b.blueprint(), fixed(b.place(), nakama, level));
			case Command.PlaceBlock p when isRelative(p.place()) -> new Command.PlaceBlock(p.item(), fixed(p.place(), nakama, level));
			case Command.MoveTo m when m.place() instanceof Place.Looking -> new Command.MoveTo(fixed(m.place(), nakama, level));
			default -> command;
		};
	}

	private static boolean isRelative(Place place) {
		return place instanceof Place.Looking || place instanceof Place.NearPlayer || place instanceof Place.Self;
	}

	private static Place fixed(Place place, NakamaEntity nakama, ServerLevel level) {
		return resolve(place, nakama, level)
			.<Place>map(p -> new Place.Coords(p.getX(), p.getY(), p.getZ()))
			.orElse(place);
	}
}
