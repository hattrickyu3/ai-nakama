package dev.ainakama.test;

import dev.ainakama.ModServices;
import dev.ainakama.command.ChatRouter;
import dev.ainakama.core.Blueprints;
import dev.ainakama.core.Command;
import dev.ainakama.core.Place;
import dev.ainakama.core.Plan;
import dev.ainakama.core.Trigger;
import dev.ainakama.entity.ModEntities;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Terrain;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 実際にゲームを起動して、仲間が集める・作る・掘り下げる・整地して建てる・渡す・置く・取り出す・焼く・畑仕事・戦う・覚える、ができるかを確かめる。
 * 平らなテスト用ワールドに木・丘・でこぼこの地面を作り、決まった計画やチャットの言葉を渡して結果を見る。
 */
public class NakamaClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("ainakama-test");
	private static final int LONG = 20 * 60 * 4;
	private static final int PROGRESS_INTERVAL = 200;

	private UUID nakamaId;
	private BlockPos base;
	private TestServerContext server;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			server = world.getServer();
			server.runCommand("time set day");
			server.runCommand("weather clear");
			context.waitTicks(40);
			server.runOnServer(this::setUp);
			context.waitTicks(20);
			context.takeScreenshot("ainakama-01-spawned");

			step("原木を4本集める", List.of(new Command.Collect("oak_log", 4)),
				n -> Inv.count(n.getInventory(), "#log") >= 4, LONG);
			// 木は高さ3。頼まれた4本に届いても、切りかけの木は上まで切りきる（幹が宙に残らない）
			server.waitFor(s -> nakama(s).isIdle(), LONG);
			check("木を切り残していない（残っている原木は、手つかずの木の分だけ）",
				server.computeOnServer(s -> count(nakama(s), base.offset(14, 0, 0), 10, "oak_log")) % 3 == 0);
			// 近くに木がない場所（いちばん近い木まで約70マス）から頼むと、まわりを歩いて探しに行く
			int logsBeforeExplore = server.computeOnServer(s -> Inv.count(nakama(s).getInventory(), "#log"));
			server.runOnServer(s -> nakama(s).snapTo(base.getX() + 14.5, base.getY(), base.getZ() + 80.5, 0, 0));
			context.waitTicks(10);
			step("近くに木がないので、遠くまで探しに行って原木を3本集める", List.of(new Command.Collect("oak_log", 3)),
				n -> n.isIdle() && Inv.count(n.getInventory(), "#log") >= logsBeforeExplore + 3, LONG);
			server.runOnServer(s -> nakama(s).snapTo(base.getX() + 0.5, base.getY(), base.getZ() + 3.5, 180, 0));
			context.waitTicks(10);
			step("木のツルハシを作る（本体のレシピで、板材・棒・作業台を自動で作る）", List.of(new Command.Craft("wooden_pickaxe", 1)),
				n -> Inv.count(n.getInventory(), "wooden_pickaxe") >= 1, LONG);
			step("はしごを作る（手書きの表にはなかったレシピ）", List.of(new Command.Craft("ladder", 3)),
				n -> Inv.count(n.getInventory(), "ladder") >= 3, LONG);
			check("本体のレシピを読み込めている", server.computeOnServer(s -> dev.ainakama.core.Recipes.count()) > 300);

			// 丘の上（石は土に埋まっていて見えない）で、掘り下げて丸石を取る
			BlockPos hilltop = base.offset(-16, 8, 16);
			server.runOnServer(s -> nakama(s).snapTo(hilltop.getX() + 0.5, hilltop.getY(), hilltop.getZ() + 0.5, 0, 0));
			context.waitTicks(10);
			step("見えていない石を、掘り下げて丸石を6個取る", List.of(new Command.Collect("cobblestone", 6)),
				n -> Inv.count(n.getInventory(), "cobblestone") >= 6, LONG);
			check("掘り下げて地面より下にいる", server.computeOnServer(s -> nakama(s).blockPosition().getY()) < hilltop.getY());

			// でこぼこ・穴・木のある場所に小屋を建てる
			BlockPos site = base.offset(4, 0, -18);
			step("整地して小屋を建てる（扉つき。足りない原木は自分で集める）",
				List.of(new Command.Build("hut", new Place.Coords(site.getX(), site.getY(), site.getZ()))),
				n -> n.isIdle() && count(n, site, 10, "_planks") >= 71, LONG * 3);
			int[] hut = server.computeOnServer(s -> new int[] {
				count(nakama(s), site, 10, "_planks"), count(nakama(s), site, 10, "oak_door"), floorHoles(nakama(s), site)});
			LOG.info("[ainakama-test] 小屋: 板材 {}、扉のブロック {}、床下の穴 {}", hut[0], hut[1], hut[2]);
			check("小屋に扉がある（上下2ブロック）", hut[1] == 2);
			check("小屋の下に穴が残っていない", hut[2] == 0);
			check("プレイヤーの原木の壁は切られていない", server.computeOnServer(s -> count(nakama(s), base.offset(-10, 0, 1), 3, "oak_log")) == 4);
			context.takeScreenshot("ainakama-02-hut");

			// 置く・渡す
			BlockPos tablePos = base.offset(0, 0, 6);
			step("作業台を指定の場所に置く", List.of(new Command.PlaceBlock("crafting_table", new Place.Coords(tablePos.getX(), tablePos.getY(), tablePos.getZ()))),
				n -> n.level().getBlockState(tablePos).is(Blocks.CRAFTING_TABLE), LONG);
			step("持ち物をプレイヤーに渡す", List.of(new Command.Give(null, 0)),
				n -> Inv.count(player(n).getInventory(), "cobblestone") >= 6, LONG);

			// チェストから取り出す・かまどで焼く・畑仕事・戦う（どれも決まった言い方なので、AIは使わない）
			int callsBeforeNew = aiCalls();
			say("チェストから鉄の原石を3個取り出す", "ミナ、チェストから鉄の原石を3個出して",
				n -> Inv.count(n.getInventory(), "raw_iron") >= 3, LONG);
			say("鉄の原石を焼く（かまどはチェストの丸石で作って置く。燃料の原木は自分で集める）", "ミナ、鉄の原石を3個焼いて",
				n -> n.isIdle() && Inv.count(n.getInventory(), "iron_ingot") >= 3, LONG * 2);
			check("かまどが置いてある", server.computeOnServer(s -> count(nakama(s), base, 6, "furnace")) >= 1);
			check("チェストの丸石を使った", server.computeOnServer(s -> Inv.count(chest(s), "cobblestone")) == 0);
			step("鉄のツルハシを作る（焼いた鉄インゴットを使う）", List.of(new Command.Craft("iron_pickaxe", 1)),
				n -> Inv.count(n.getInventory(), "iron_pickaxe") >= 1, LONG);

			BlockPos field = base.offset(-9, 0, -6);
			server.runOnServer(s -> plantField(s, field));
			check("テスト用の畑ができている（育ちきった小麦が4マス）", server.computeOnServer(s -> ripeCrops(nakama(s), field)) == 4);
			server.runOnServer(s -> nakama(s).getInventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT_SEEDS, 8)));
			say("畑の世話をする（育った小麦を収穫して植え直し、空いた畑に種を植える）", "ミナ、畑仕事して",
				n -> n.isIdle() && Inv.count(n.getInventory(), "wheat") >= 4, LONG);
			int[] crops = server.computeOnServer(s -> new int[] {count(nakama(s), field, 4, "wheat"), ripeCrops(nakama(s), field)});
			LOG.info("[ainakama-test] 畑: 小麦 {}マス、育ちきったもの {}マス", crops[0], crops[1]);
			check("収穫したあとに植え直し、空いていた畑にも植えた（6マス全部に小麦）", crops[0] == 6);
			check("育ちきった小麦は残っていない", crops[1] == 0);

			// 新しい畑：何もない草地を耕して、持っている種をまく
			BlockPos newField = base.offset(8, 0, -6);
			step("新しい畑を耕して種をまく（クワは自分で作る）",
				List.of(new Command.Till(new Place.Coords(newField.getX(), newField.getY(), newField.getZ()), 6), new Command.Farm()),
				n -> n.isIdle() && count(n, newField, 6, "farmland") >= 6 && count(n, newField, 6, "wheat") >= 6, LONG);
			check("クワを自分で作った", server.computeOnServer(s -> Inv.count(nakama(s).getInventory(), "wooden_hoe")) >= 1);

			// 動物を狩る：牛が4頭。また増やせるよう2頭は残す決まりなので、3頭頼んでも2頭までしか狩らない
			for (int i = 0; i < 4; i++) {
				server.runCommand("summon minecraft:cow " + (base.getX() + 6 + i) + " " + base.getY() + " " + (base.getZ() + 9));
			}
			context.waitTicks(10);
			check("テスト用の牛が4頭いる", server.computeOnServer(s -> animals(nakama(s), "cow")) == 4);
			say("牛を狩って、落とした肉を拾う（2頭は残す）", "牛を3頭狩って",
				n -> n.isIdle() && animals(n, "cow") == 2 && Inv.count(n.getInventory(), "beef") >= 2, LONG);
			// 落ちているものを拾う
			BlockPos dropPos = base.offset(2, 0, 2);
			server.runOnServer(s -> s.overworld().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(s.overworld(),
				dropPos.getX() + 0.5, dropPos.getY() + 0.5, dropPos.getZ() + 0.5,
				new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FEATHER, 3))));
			say("落ちているものを拾う", "落ちてるものを拾って", n -> n.isIdle() && Inv.count(n.getInventory(), "feather") >= 3, LONG);

			// 動物の世話：エサをやる・乳をしぼる・毛を刈る（道具はテストで渡す）
			say("牛2頭にエサをやる（持っている小麦を使う）", "牛を増やして",
				n -> n.isIdle() && Inv.count(n.getInventory(), "wheat") == 2, LONG);
			give(net.minecraft.world.item.Items.BUCKET, 1);
			say("バケツで牛の乳をしぼる", "乳しぼりして", n -> n.isIdle() && Inv.count(n.getInventory(), "milk_bucket") >= 1, LONG);
			for (int i = 0; i < 2; i++) {
				server.runCommand("summon minecraft:sheep " + (base.getX() + 5 + i) + " " + base.getY() + " " + (base.getZ() + 7));
			}
			give(net.minecraft.world.item.Items.SHEARS, 1);
			context.waitTicks(10);
			say("ハサミで羊の毛を刈る（羊は倒さない）", "羊の毛を刈って",
				n -> n.isIdle() && Inv.count(n.getInventory(), "white_wool") >= 2 && animals(n, "sheep") == 2, LONG);

			// 「用意して」：手に入れ方を自分で選ぶ（棒は原木から作る。革はもう持っているので何もしない）
			say("棒を8本用意する（原木 → 板材 → 棒）", "棒を8本用意して", n -> n.isIdle() && Inv.count(n.getInventory(), "stick") >= 8, LONG);
			step("革はもう持っているので、何もしないで終わる", List.of(new Command.Obtain("leather", 1)), NakamaEntity::isIdle, LONG);

			// 釣り：小さな池を作って、釣り竿を渡す
			BlockPos pond = base.offset(4, -1, 13);
			server.runOnServer(s -> {
				for (BlockPos p : BlockPos.betweenClosed(pond, pond.offset(1, 0, 1))) {
					s.overworld().setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
				}
			});
			give(net.minecraft.world.item.Items.FISHING_ROD, 1);
			step("池で魚を1匹釣る", List.of(new Command.Fish(1)),
				n -> n.isIdle() && fishCount(n) >= 1, LONG);

			// 村人との取引：小麦2個とエメラルド1個を交換してくれる村人
			server.runCommand("summon minecraft:villager " + (base.getX() + 3) + " " + base.getY() + " " + (base.getZ() + 10)
				+ " {NoAI:1b,VillagerData:{profession:\"minecraft:farmer\",level:2,type:\"minecraft:plains\"},"
				+ "Offers:{Recipes:[{buy:{id:\"minecraft:wheat\",count:2},sell:{id:\"minecraft:emerald\",count:1},maxUses:99}]}}");
			context.waitTicks(10);
			say("村人に小麦を売って、エメラルドをもらう", "小麦を売って",
				n -> n.isIdle() && Inv.count(n.getInventory(), "emerald") >= 1 && Inv.count(n.getInventory(), "wheat") == 0, LONG);

			// 暗いところに松明を置く（松明はテストで渡す）
			BlockPos darkPlace = base.offset(34, 0, -30);
			give(net.minecraft.world.item.Items.TORCH, 24);
			step("まわりの暗い地面に、間をあけて松明を置く",
				List.of(new Command.Light(new Place.Coords(darkPlace.getX(), darkPlace.getY(), darkPlace.getZ()))),
				n -> n.isIdle() && count(n, darkPlace, 13, "torch") >= 6, LONG);
			server.runOnServer(s -> nakama(s).snapTo(base.getX() + 0.5, base.getY(), base.getZ() + 3.5, 180, 0));
			context.waitTicks(10);

			// ミナが倒す前にプレイヤーが倒されると、テストごとゲームが終わってしまうので、ダメージを受けないようにする
			server.runCommand("effect give @p minecraft:resistance infinite 4 true");
			server.runCommand("difficulty easy");
			UUID huskId = server.computeOnServer(s -> spawnHusk(s, base.offset(-4, 0, 4)));
			context.waitTicks(10);
			check("敵（ハスク）が出ている", server.computeOnServer(s -> s.overworld().getEntity(huskId) != null));
			say("近くの敵を倒す", "ミナ、敵を倒して", n -> {
				var husk = ((ServerLevel) n.level()).getEntity(huskId);
				return husk == null || !husk.isAlive();
			}, LONG);
			server.runCommand("difficulty peaceful");
			chat("ミナ、守って");
			server.waitFor(s -> nakama(s).currentJob().contains("守る"), 100);
			LOG.info("[ainakama-test] OK: 「守って」が通じた");
			check("取り出す・焼く・畑・狩る・拾う・世話・用意・釣り・取引・明かり・戦う・守るで、AIを1回も呼んでいない", aiCalls() == callsBeforeNew);

			// 建物を見せて覚える
			BlockPos demo = base.offset(3, 0, 3);
			chat("ミナ、見てて");
			context.waitTicks(10);
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				level.setBlockAndUpdate(demo, Blocks.COBBLESTONE.defaultBlockState());
				level.setBlockAndUpdate(demo.above(), Blocks.COBBLESTONE.defaultBlockState());
				level.setBlockAndUpdate(demo.above(2), Blocks.OAK_FENCE.defaultBlockState());
				// 見せている間に近くの畑の小麦が育っても、建物には数えない
				level.setBlockAndUpdate(field, Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 5));
			});
			context.waitTicks(10);
			chat("ミナ、これが目印だよ");
			context.waitTicks(10);
			check("見せた建物「目印」を覚えた（3ブロック）",
				Blueprints.find("目印").map(b -> b.parts().size()).orElse(0) == 3);
			BlockPos markSite = base.offset(-4, 0, -8);
			server.runOnServer(s -> player(s).getInventory().clearContent());
			server.runOnServer(s -> nakama(s).getInventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 4)));
			chat("ミナ、ついてきて");
			context.waitTicks(20);
			step("覚えた「目印」を別の場所に建てる（柵は自分で作る）",
				List.of(new Command.Build("目印", new Place.Coords(markSite.getX(), markSite.getY(), markSite.getZ()))),
				n -> n.isIdle() && count(n, markSite, 8, "cobblestone") >= 2 && count(n, markSite, 8, "oak_fence") >= 1, LONG * 2);

			// 覚えた技と決まり（AIを使わない）
			int callsBefore = aiCalls();
			server.runOnServer(s -> ModServices.skills().learn(new Plan.Skill("木こり", List.of(new Command.Collect("oak_log", 2)))));
			int logsBefore = server.computeOnServer(s -> Inv.count(nakama(s).getInventory(), "#log"));
			chat("ミナ木こりして");
			server.waitFor(s -> Inv.count(nakama(s).getInventory(), "#log") >= logsBefore + 2, LONG);
			LOG.info("[ainakama-test] OK: 覚えた技「木こり」を、区切りなしの呼びかけで実行");
			server.runOnServer(s -> ModServices.skills().setRule(new Plan.Rule(Trigger.NIGHT, List.of(new Command.Remember("夜が来た")))));
			server.runCommand("time set night");
			server.waitFor(s -> nakama(s).memory().notes().contains("夜が来た"), 200);
			LOG.info("[ainakama-test] OK: 「夜になったら」の決まりが動いた");
			server.runCommand("time set day");
			chat("皆ついてきて");
			server.waitFor(s -> nakama(s).currentJob().contains("ついていく"), 100);
			LOG.info("[ainakama-test] OK: 「皆ついてきて」が通じた");
			// プレイヤーが1人だけのときは、名前を付けなくても届く
			chat("止まって");
			server.waitFor(s -> !nakama(s).currentJob().contains("ついていく"), 100);
			chat("ついてきて");
			server.waitFor(s -> nakama(s).currentJob().contains("ついていく"), 100);
			LOG.info("[ainakama-test] OK: 名前なしの「止まって」「ついてきて」が通じた");
			// 作業中に別のことを頼んでも、今の作業は続け、頼まれたことはそのあとにやる
			chat("止まって");
			server.waitFor(s -> nakama(s).isIdle(), 100);
			chat("木を6本集めて");
			server.waitFor(s -> nakama(s).currentJob().contains("集める"), 100);
			chat("松明を4個作って");
			server.waitFor(s -> nakama(s).currentJob().contains("集める") && nakama(s).currentJob().contains("このあと1個"), 100);
			LOG.info("[ainakama-test] OK: 作業中の頼みは、今の作業のあとに足された（{}）", job());
			chat("止まって");
			server.waitFor(s -> nakama(s).isIdle(), 100);
			LOG.info("[ainakama-test] OK: 「止まって」で、足した分もまとめてやめた");
			// 言葉で教える（プログラムが読み取るので、AIは使わない）
			int logsBeforeCamp = server.computeOnServer(s -> Inv.count(nakama(s).getInventory(), "#log"));
			chat("ミナ、キャンプの準備っていうのは、木を3本集めて、作業台を1個作ることだよ");
			context.waitTicks(10);
			check("教えた技「キャンプの準備」を覚えた", server.computeOnServer(s -> ModServices.skills().knows("キャンプの準備")));
			server.waitFor(s -> nakama(s).isIdle() && Inv.count(nakama(s).getInventory(), "crafting_table") >= 1, LONG);
			LOG.info("[ainakama-test] OK: 覚えた直後に「キャンプの準備」を実行した（原木 {} → {}）", logsBeforeCamp, server.computeOnServer(s -> Inv.count(nakama(s).getInventory(), "#log")));
			chat("ミナ、雨が降ったら拠点に帰って");
			context.waitTicks(10);
			check("教えた決まり「雨が降ったら」を覚えた", server.computeOnServer(s -> ModServices.skills().rule(Trigger.RAIN).isPresent()));
			check("ここまでAIを1回も呼んでいない", aiCalls() == callsBefore);

			// 自由な言い方（AIを使う。Ollama が動いていないと失敗する）
			talkToAi(context, "@ミナ 疲れたでしょ、ちょっと休んでいいよ。そのあと木を3本だけ取ってきてくれる？");
			talkToAi(context, "ミナ、冬支度をして");
			context.waitTicks(20);
			chat("ミナ、木を2本集めて、松明を4個作って");
			context.waitTicks(20);
			List<String> learned = server.computeOnServer(s -> ModServices.skills().all().stream().map(Plan.Skill::name).toList());
			LOG.info("[ainakama-test] 最後に覚えている技: {}（「冬支度」が入っていれば、AIが聞き返して、その答えを覚えられた）", learned);
			context.waitTicks(100);
			context.takeScreenshot("ainakama-03-after-ai");

			// 遠く（読み込まれていない土地）にいる仲間：言葉は届かないが、「こっちに来て」で呼び寄せられる
			chat("止まって");
			context.waitTicks(10);
			server.runCommand("tp @p " + (base.getX() + 400) + " " + base.getY() + " " + base.getZ());
			context.waitTicks(200);
			LOG.info("[ainakama-test] プレイヤーが400マス先へ行ったあと、置いていかれた仲間は読み込まれて{}",
				server.computeOnServer(s -> nakama(s) == null) ? "いない" : "いる");
			chat("松明を4個作って");
			context.waitTicks(20);
			check("遠くの仲間には言葉が届かず、その場から動かない", server.computeOnServer(s -> {
				NakamaEntity n = nakama(s);
				return n == null || (n.isIdle() && n.distanceToSqr(player(s)) > 300 * 300);
			}));
			chat("こっちに来て");
			server.waitFor(s -> {
				NakamaEntity n = nakama(s);
				return n != null && n.distanceToSqr(player(s)) < 10 * 10;
			}, 400);
			server.waitFor(s -> nakama(s).currentJob().contains("ついていく"), 100);
			LOG.info("[ainakama-test] OK: 「こっちに来て」で、遠くの仲間がそばに来てついてきた");
			chat("止まって");
			context.waitTicks(10);
			LOG.info("[ainakama-test] ALL PASSED");
		}
	}

	private void talkToAi(ClientGameTestContext context, String text) {
		int before = aiCalls();
		chat(text);
		server.waitFor(s -> nakama(s).isThinking(), 200);
		server.waitFor(s -> !nakama(s).isThinking(), 20 * 120);
		context.waitTicks(40);
		int after = aiCalls();
		LOG.info("[ainakama-test] 「{}」→ AI {}回、今の作業: {}", text, after - before, job());
		check("AIが呼ばれた", after > before);
	}

	private void step(String name, List<Command> plan, Predicate<NakamaEntity> done, int timeout) {
		LOG.info("[ainakama-test] START: {}", name);
		server.runOnServer(s -> nakama(s).runPlan(plan));
		int ticks = waitWithProgress(done, timeout);
		LOG.info("[ainakama-test] OK: {}（{}tick） 持ち物: {}", name, ticks,
			server.computeOnServer(s -> Inv.counts(nakama(s).getInventory())));
	}

	/** 終わるのを待つ。10秒ごとに、今の作業と場所を記録に残す（止まったときに原因を調べられるように）。 */
	private int waitWithProgress(Predicate<NakamaEntity> done, int timeout) {
		int waited = 0;
		while (true) {
			try {
				return waited + server.waitFor(s -> done.test(nakama(s)), PROGRESS_INTERVAL);
			} catch (AssertionError timedOut) {
				waited += PROGRESS_INTERVAL;
				String state = server.computeOnServer(s -> nakama(s).currentJob() + " @ " + nakama(s).blockPosition().toShortString()
					+ " 持ち物 " + Inv.counts(nakama(s).getInventory()));
				LOG.info("[ainakama-test]   …{}tick: {}", waited, state);
				if (waited >= timeout) {
					throw timedOut;
				}
			}
		}
	}

	/** チャットで頼んで、終わるのを待つ。 */
	private void say(String name, String text, Predicate<NakamaEntity> done, int timeout) {
		LOG.info("[ainakama-test] START: {}（「{}」）", name, text);
		chat(text);
		int ticks = waitWithProgress(done, timeout);
		LOG.info("[ainakama-test] OK: {}（{}tick） 持ち物: {}", name, ticks,
			server.computeOnServer(s -> Inv.counts(nakama(s).getInventory())));
	}

	/** 3×2マスの畑。4マスは育ちきった小麦、1マスは育っている途中、1マスは空き。 */
	private static void plantField(MinecraftServer server, BlockPos corner) {
		ServerLevel level = server.overworld();
		BlockState soil = Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmlandBlock.MOISTURE, 7);
		int i = 0;
		for (BlockPos p : BlockPos.betweenClosed(corner, corner.offset(2, 0, 1))) {
			level.setBlockAndUpdate(p.below(), soil);
			if (i < 5) {
				level.setBlockAndUpdate(p, Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, i < 4 ? 7 : 2));
			}
			i++;
		}
	}

	private static int ripeCrops(NakamaEntity n, BlockPos center) {
		int count = 0;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-4, -1, -4), center.offset(4, 1, 4))) {
			if (Terrain.isRipeCrop(n.level().getBlockState(p))) {
				count++;
			}
		}
		return count;
	}

	/** 仲間のまわりにいる、その種類の動物の数。 */
	private static int animals(NakamaEntity n, String id) {
		return n.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, n.getBoundingBox().inflate(64),
			a -> a.isAlive() && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).getPath().equals(id)).size();
	}

	/** 仲間の持ち物に、テスト用の道具や材料を入れる。 */
	private void give(net.minecraft.world.item.Item item, int count) {
		server.runOnServer(s -> nakama(s).getInventory().addItem(new net.minecraft.world.item.ItemStack(item, count)));
	}

	private static int fishCount(NakamaEntity n) {
		return Inv.count(n.getInventory(), "cod") + Inv.count(n.getInventory(), "salmon")
			+ Inv.count(n.getInventory(), "pufferfish") + Inv.count(n.getInventory(), "tropical_fish");
	}

	private static UUID spawnHusk(MinecraftServer server, BlockPos pos) {
		ServerLevel level = server.overworld();
		var husk = net.minecraft.world.entity.EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
		husk.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
		husk.setPersistenceRequired();
		level.addFreshEntity(husk);
		return husk.getUUID();
	}

	/** 拠点のそばに置いたチェスト（鉄の原石4個と丸石8個が入っている）。 */
	private net.minecraft.world.Container chest(MinecraftServer server) {
		return (net.minecraft.world.Container) server.overworld().getBlockEntity(base.offset(-2, 0, 6));
	}

	private static void check(String what, boolean ok) {
		if (!ok) {
			throw new AssertionError("NG: " + what);
		}
		LOG.info("[ainakama-test] OK: {}", what);
	}

	private void chat(String text) {
		server.runOnServer(s -> ChatRouter.onChat(player(s), text));
	}

	private int aiCalls() {
		return server.computeOnServer(s -> ModServices.ai().usage().today().calls());
	}

	private String job() {
		return server.computeOnServer(s -> nakama(s).currentJob());
	}

	private void setUp(MinecraftServer server) {
		ServerPlayer player = player(server);
		ServerLevel level = player.level();
		base = player.blockPosition();
		Blueprints.clearLearned();
		ModServices.skills().all().forEach(sk -> ModServices.skills().forget(sk.name()));

		// 木：拠点から12〜16マス離れたところに、高さ3の幹＋葉っぱを21本（原木63本分）
		for (int i = 0; i < 21; i++) {
			BlockPos trunk = base.offset(12 + (i % 3) * 2, 0, -9 + (i / 3) * 3);
			for (int h = 0; h < 3; h++) {
				level.setBlockAndUpdate(trunk.above(h), Blocks.OAK_LOG.defaultBlockState());
			}
			level.setBlockAndUpdate(trunk.above(3), naturalLeaves());
		}
		// プレイヤーが原木で作った壁（葉っぱなし）。木より近いが、切られてはいけない
		for (int i = 0; i < 4; i++) {
			level.setBlockAndUpdate(base.offset(-10, 0, i), Blocks.OAK_LOG.defaultBlockState());
		}
		// 丘：外側1マスは土、中は石。石は外から見えない
		for (BlockPos p : BlockPos.betweenClosed(base.offset(-22, 0, 10), base.offset(-10, 7, 22))) {
			boolean shell = p.getX() == base.getX() - 22 || p.getX() == base.getX() - 10
				|| p.getZ() == base.getZ() + 10 || p.getZ() == base.getZ() + 22 || p.getY() == base.getY() + 7;
			level.setBlockAndUpdate(p, (shell ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
		}
		// 小屋を建てる場所：土のでこぼこ・深さ2の穴・木
		BlockPos site = base.offset(4, 0, -18);
		level.setBlockAndUpdate(site.offset(1, 0, 1), Blocks.DIRT.defaultBlockState());
		level.setBlockAndUpdate(site.offset(2, 0, 0), Blocks.DIRT.defaultBlockState());
		level.setBlockAndUpdate(site.offset(-1, 0, 2), Blocks.DIRT.defaultBlockState());
		level.setBlockAndUpdate(site.offset(0, -1, 0), Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(site.offset(0, -2, 0), Blocks.AIR.defaultBlockState());
		for (int h = 0; h < 3; h++) {
			level.setBlockAndUpdate(site.offset(-2, h, -1), Blocks.OAK_LOG.defaultBlockState());
		}
		level.setBlockAndUpdate(site.offset(-2, 3, -1), naturalLeaves());

		// チェスト：鉄の原石4個と丸石8個（取り出す・かまどを作る、のテスト用）
		BlockPos chestPos = base.offset(-2, 0, 6);
		level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
		var chest = (net.minecraft.world.Container) level.getBlockEntity(chestPos);
		chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RAW_IRON, 4));
		chest.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 8));

		NakamaEntity nakama = ModEntities.NAKAMA.create(level, EntitySpawnReason.COMMAND);
		nakama.snapTo(base.getX() + 0.5, base.getY(), base.getZ() + 3.5, 180, 0);
		nakama.setup("ミナ", base);
		level.addFreshEntity(nakama);
		nakamaId = nakama.getUUID();
	}

	/** 本物の木と同じ状態の葉っぱ（原木のとなりにあるので消えない）。初期状態のままだと、時間がたつと消えてしまう。 */
	private static BlockState naturalLeaves() {
		return Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 1);
	}

	/** center のまわり radius マスにある、IDが suffix で終わるブロックの数。 */
	private static int count(NakamaEntity n, BlockPos center, int radius, String suffix) {
		int count = 0;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 8, radius))) {
			if (Terrain.idOf(n.level().getBlockState(p)).endsWith(suffix)) {
				count++;
			}
		}
		return count;
	}

	/** 小屋（板材）の真下で、地面が抜けているマスの数。 */
	private static int floorHoles(NakamaEntity n, BlockPos center) {
		int holes = 0;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-10, 0, -10), center.offset(10, 0, 10))) {
			BlockState state = n.level().getBlockState(p);
			if (Terrain.idOf(state).endsWith("_planks") && n.level().getBlockState(p.below()).canBeReplaced()) {
				holes++;
			}
		}
		return holes;
	}

	private NakamaEntity nakama(MinecraftServer server) {
		return (NakamaEntity) server.overworld().getEntity(nakamaId);
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static ServerPlayer player(NakamaEntity n) {
		return ((ServerLevel) n.level()).getServer().getPlayerList().getPlayers().get(0);
	}
}
