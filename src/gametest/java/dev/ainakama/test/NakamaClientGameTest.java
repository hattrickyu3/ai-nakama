package dev.ainakama.test;

import dev.ainakama.ModServices;
import dev.ainakama.command.ChatRouter;
import dev.ainakama.core.Command;
import dev.ainakama.core.Place;
import dev.ainakama.entity.ModEntities;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 実際にゲームを起動して、仲間が集める・作る・建てる・話しかけに応えるかを確かめる。
 * 平らなテスト用ワールドに木と石を置き、決まった計画を渡して結果を見る。
 */
public class NakamaClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("ainakama-test");
	private static final int LONG = 20 * 60 * 4;

	private UUID nakamaId;
	private BlockPos base;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			var server = world.getServer();
			server.runCommand("time set day");
			server.runCommand("weather clear");
			context.waitTicks(40);

			server.runOnServer(this::setUp);
			context.waitTicks(20);
			context.takeScreenshot("ainakama-01-spawned");

			step(context, world, "原木を4本集める", List.of(new Command.Collect("oak_log", 4)),
				n -> Inv.count(n.getInventory(), "#log") >= 4, LONG);

			step(context, world, "木のツルハシを作る（板材・棒・作業台を自動で作る）", List.of(new Command.Craft("wooden_pickaxe", 1)),
				n -> Inv.count(n.getInventory(), "wooden_pickaxe") >= 1, LONG);

			step(context, world, "丸石を3個集める（石を掘る）", List.of(new Command.Collect("cobblestone", 3)),
				n -> Inv.count(n.getInventory(), "cobblestone") >= 3, LONG);

			step(context, world, "拠点に小屋を建てる（足りない原木を自分で集める）", List.of(new Command.Build("hut", new Place.Home())),
				n -> n.isIdle() && hutBlocks(n) >= 60, LONG * 2);
			int hut = server.computeOnServer(s -> hutBlocks(nakama(s)));
			LOG.info("[ainakama-test] 小屋のブロック数: {}", hut);
			int wall = server.computeOnServer(s -> {
				int n = 0;
				for (int i = 0; i < 4; i++) {
					if (player(s).level().getBlockState(base.offset(-10, 0, i)).is(Blocks.OAK_LOG)) {
						n++;
					}
				}
				return n;
			});
			if (wall != 4) {
				throw new AssertionError("プレイヤーの原木の壁が切られた（残り " + wall + "/4）");
			}
			LOG.info("[ainakama-test] OK: プレイヤーの原木の壁は残っている");
			context.takeScreenshot("ainakama-02-hut");

			// 決まった言い方（AIを使わない）
			server.runOnServer(s -> ChatRouter.onChat(player(s), "ミナ、ついてきて"));
			server.waitFor(s -> nakama(s).currentJob().contains("ついていく"), 100);
			String job = server.computeOnServer(s -> nakama(s).currentJob());
			LOG.info("[ainakama-test] OK: 決まった言い方「ついてきて」→ {}", job);

			// 自由な言い方（AIを使う。Ollama が動いていないと失敗する）
			int callsBefore = server.computeOnServer(s -> ModServices.ai().usage().today().calls());
			server.runOnServer(s -> ChatRouter.onChat(player(s), "@ミナ 疲れたでしょ、ちょっと休んでいいよ。そのあと木を3本だけ取ってきてくれる？"));
			server.waitFor(s -> nakama(s).isThinking(), 200);
			server.waitFor(s -> !nakama(s).isThinking(), 20 * 120);
			int callsAfter = server.computeOnServer(s -> ModServices.ai().usage().today().calls());
			LOG.info("[ainakama-test] AI呼び出し {} → {} 回、今の作業: {}", callsBefore, callsAfter,
				server.computeOnServer(s -> nakama(s).currentJob()));
			if (callsAfter <= callsBefore) {
				throw new AssertionError("AIが呼ばれなかった");
			}
			context.waitTicks(200);
			context.takeScreenshot("ainakama-03-after-ai");
			LOG.info("[ainakama-test] ALL PASSED");
		}
	}

	private void step(ClientGameTestContext context, TestSingleplayerContext world, String name, List<Command> plan,
		Predicate<NakamaEntity> done, int timeout) {
		LOG.info("[ainakama-test] START: {}", name);
		var server = world.getServer();
		server.runOnServer(s -> nakama(s).runPlan(plan));
		int ticks = server.waitFor(s -> done.test(nakama(s)), timeout);
		LOG.info("[ainakama-test] OK: {}（{}tick） 持ち物: {}", name, ticks,
			server.computeOnServer(s -> Inv.counts(nakama(s).getInventory())));
	}

	private void setUp(MinecraftServer server) {
		ServerPlayer player = player(server);
		ServerLevel level = player.level();
		base = player.blockPosition();

		// 木：拠点から12〜16マス離れたところに、高さ3の幹＋葉っぱを12本（原木36本分）
		for (int i = 0; i < 12; i++) {
			BlockPos trunk = base.offset(12 + (i % 3) * 2, 0, -6 + (i / 3) * 3);
			for (int h = 0; h < 3; h++) {
				level.setBlockAndUpdate(trunk.above(h), Blocks.OAK_LOG.defaultBlockState());
			}
			level.setBlockAndUpdate(trunk.above(3), Blocks.OAK_LEAVES.defaultBlockState());
		}
		// プレイヤーが原木で作った壁（葉っぱなし）。木より近いが、切られてはいけない
		for (int i = 0; i < 4; i++) {
			level.setBlockAndUpdate(base.offset(-10, 0, i), Blocks.OAK_LOG.defaultBlockState());
		}
		// 石：拠点の反対側の地面に5個
		for (int i = 0; i < 5; i++) {
			level.setBlockAndUpdate(base.offset(-12, -1, i), Blocks.STONE.defaultBlockState());
		}
		NakamaEntity nakama = ModEntities.NAKAMA.create(level, EntitySpawnReason.COMMAND);
		nakama.snapTo(base.getX() + 0.5, base.getY(), base.getZ() + 3.5, 180, 0);
		nakama.setup("ミナ", base);
		level.addFreshEntity(nakama);
		nakamaId = nakama.getUUID();
	}

	private int hutBlocks(NakamaEntity n) {
		ServerLevel level = (ServerLevel) n.level();
		int count = 0;
		BlockPos origin = base.offset(2, 0, 2);
		for (BlockPos p : BlockPos.betweenClosed(origin, origin.offset(4, 3, 4))) {
			if (Inv.idOf(level.getBlockState(p).getBlock().asItem()).endsWith("_planks")) {
				count++;
			}
		}
		return count;
	}

	private NakamaEntity nakama(MinecraftServer server) {
		return (NakamaEntity) server.overworld().getEntity(nakamaId);
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}
}
