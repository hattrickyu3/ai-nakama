package dev.ainakama.core;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 決まった言い方の指示を、AIを使わずに命令にする。ここで読めなかった言葉だけをAIに回す。
 */
public final class QuickCommands {
	/** AIを通さずに処理する特別な指示（命令の列ではなく、仲間の設定を変えたり答えたりするもの）。 */
	public enum Special {
		SET_HOME, SHOW_INVENTORY, WHERE_HOME,
		/** 「見てて」：これから建てる建物を覚える準備をする。 */
		WATCH,
		/** 「これが〇〇だよ」：見せてもらった建物に名前を付けて覚える。arg が名前。 */
		NAME_BUILD
	}

	/** @param arg 特別な指示に付く言葉（NAME_BUILD の名前など）。なければ空文字 */
	public record Match(Optional<Plan> plan, Optional<Special> special, String arg) {
		static Match plan(Plan p) {
			return new Match(Optional.of(p), Optional.empty(), "");
		}

		static Match special(Special s) {
			return new Match(Optional.empty(), Optional.of(s), "");
		}

		static Match special(Special s, String arg) {
			return new Match(Optional.empty(), Optional.of(s), arg);
		}
	}

	private static final String END = "(よ|ね|ください|くれ|くれる|ほしい|欲しい)?[!！。?？]*$";
	private static final String NUM = "(" + Quantity.PATTERN + ")";
	private static final String UNIT = "(?:個|本|つ|コ|枚)?(?:くらい|ぐらい|ほど|分)?";

	private static final Pattern FOLLOW = Pattern.compile("^(ついてきて|ついて来て|ついてこい|follow( me)?|おいで|来て|きて|こっちに?(来て|きて))" + END);
	private static final Pattern STOP = Pattern.compile("^(止まって|とまって|ストップ|stop|待って|まって|やめて|もういいよ?)" + END);
	private static final Pattern GO_HOME = Pattern.compile("^(もう)?(拠点に|家に|homeに)?(帰って|戻って|かえって|もどって)(いい|いて)?" + END);
	private static final Pattern SET_HOME = Pattern.compile("^(ここを(拠点|home|家)に(して)?|ここが(拠点|home|家)(だ|です)?)" + END);
	private static final Pattern WATCH = Pattern.compile("^(今から|これから)?(.*(建てる|作る|つくる)から)?(見てて|みてて|見ていて|見てね|見て|みて|よく見て(て)?)" + END);
	private static final Pattern NAME_BUILD = Pattern.compile("^(これが|これは|今のが|いまのが)「?(.+?)」?(だよ|です|だ|っていうんだ|という建物)" + END);
	private static final Pattern NAME_BUILD2 = Pattern.compile("^(これを|今のを|いまのを)「?(.+?)」?(として|っていう名前で|という名前で)(覚えて|おぼえて)" + END);
	private static final Pattern REMEMBER = Pattern.compile("^(.{2,80}?)(んだ|なんだ|だよ|よ)?[。.]?(って|ということを|ことを|を)?(覚えておいて|覚えといて|おぼえておいて|覚えていて|忘れないで)" + END);
	private static final Pattern HOME_WORD = Pattern.compile("拠点|home|ホーム|家");
	private static final Pattern WHERE_WORD = Pattern.compile("どこ|座標|場所");
	private static final Pattern GIVE_WORD = Pattern.compile("(渡して|わたして|ちょうだい|頂戴|落として|ドロップして)" + END);
	/** 文の頭に付きがちな、意味のないつなぎ言葉。 */
	private static final Pattern FILLER = Pattern.compile("^(そしたら|そうしたら|じゃあ|じゃ|それじゃ|それでは|では|あと|ねえ|ねぇ|とりあえず|一旦|いったん|次は|今度は)+");
	private static final Pattern INVENTORY = Pattern.compile("^(持ち物(は|を見せて|を教えて)?|何(を)?持ってる(の)?|もちもの)" + END);
	private static final Pattern STORE = Pattern.compile("^((この|その|近くの)?チェストに)?(しまって|片付けて|かたづけて|入れて|いれて)" + END);
	private static final Pattern GIVE = Pattern.compile("^(.+?)を" + NUM + UNIT + "(私に|僕に|俺に|こっちに|目の前に)?(渡して|わたして|ちょうだい|頂戴|落として|ドロップして)" + END);
	private static final Pattern COLLECT = Pattern.compile("^(.+?)を" + NUM + UNIT + "(集めて|あつめて|取ってきて|とってきて|掘ってきて|掘って|切ってきて|切って)" + END);
	private static final Pattern CRAFT = Pattern.compile("^(.+?)を" + NUM + UNIT + "(作って|つくって|クラフトして)" + END);
	/** 「ここに」「この辺に」「拠点の近くに」のような、場所の言い方。拠点で始まれば拠点、ほかは見ているところ。 */
	private static final String WHERE = "((?:ここら辺|ここらへん|このへん|この辺り|この辺|このあたり|ここ|そこ|拠点(?:の(?:近く|そば|横|隣))?)(?:に|を|で))?";
	private static final Pattern BUILD = Pattern.compile("^" + WHERE + "(.+?)を(建てて|たてて|作って|つくって)" + END);
	private static final String AREA = NUM + "(?:マス|個|ブロック|つ)?(?:くらい|ぐらい|ほど|分)?";
	/** 「畑を作って」「ここに畑を12マス作って」「畑を20マス広げて」。 */
	private static final Pattern TILL = Pattern.compile("^" + WHERE + "(?:新しい|新しく)?(?:畑|はたけ)を" + AREA + "(作って|つくって|耕して|たがやして|広げて|ひろげて)" + END);
	/** 「ここを耕して」「この辺を6マス耕して」「耕して」。 */
	private static final Pattern TILL2 = Pattern.compile("^" + WHERE + "(?:(?:地面|土)を)?" + AREA + "(耕して|たがやして)" + END);
	private static final Pattern PLACE = Pattern.compile("^(ここに|そこに)(.+?)を(置いて|おいて|設置して|つけて|付けて)" + END);
	private static final String CHEST = "(?:近くの|この|その)?(?:チェスト|箱|樽)(?:の中)?から";
	private static final String TAKE_VERB = "(?:取り出して|とりだして|取ってきて|とってきて|持ってきて|もってきて|取って|とって|出して|だして)";
	/** 「チェストから石を10個出して」と「石をチェストから10個出して」の両方。 */
	private static final Pattern TAKE = Pattern.compile("^" + CHEST + "(.+?)を" + NUM + UNIT + TAKE_VERB + END);
	private static final Pattern TAKE2 = Pattern.compile("^(.+?)を" + CHEST + NUM + UNIT + TAKE_VERB + END);
	private static final Pattern SMELT = Pattern.compile("^(?:かまどで)?(.+?)を" + NUM + UNIT + "(?:かまどで)?(?:焼いてきて|焼いて|やいて|精錬して)" + END);
	private static final Pattern FARM = Pattern.compile("^(畑仕事(を)?(して|やって)|畑の(世話|手入れ)(を)?して|((畑|作物|小麦|野菜)(を|の))?(収穫(を)?して|刈り取って)|(種|たね)を(植えて|まいて|蒔いて))" + END);
	private static final Pattern FIGHT = Pattern.compile("^((近くの|まわりの|周りの|その|あの)?(敵|モンスター|ゾンビ|スケルトン|クモ|クリーパー)(たち)?を(倒して|たおして|やっつけて|退治して|攻撃して)|戦って|たたかって)" + END);
	/**
	 * 「守って」のほか、「これから洞窟に行くから、そばで守っていてね」のように理由が前に付いた言い方も受ける（小さいAIは follow と取り違えやすい）。
	 * 「木を集めてから守って」の「てから」は順番の指示なので、理由としては受けない（集めるほうを落としてしまうため）。
	 */
	private static final Pattern GUARD = Pattern.compile("^(.{1,30}?(?<![てで])(から|ので))?(そばで|近くで|ずっと|しっかり)*((私|僕|俺|わたし|ぼく|おれ)を)?(守って|まもって|護衛(を)?して)(いて|おいて|て)?" + END);

	private static final String HEAD_UNIT = "(?:頭|匹|羽|体|個|つ|枚)?(?:くらい|ぐらい|ほど|分)?";
	/** 「牛を3頭狩って」。相手が動物（か、動物の落とすもの）のときだけ受ける。モンスターは FIGHT が先に受ける。 */
	private static final Pattern HUNT = Pattern.compile("^(.+?)を" + NUM + HEAD_UNIT + "(狩ってきて|狩って|倒してきて|倒して|たおして|仕留めて|しとめて)" + END);
	/** 「牛肉を5個取ってきて」「肉を集めて」。動物の落とすもののときだけ受ける。 */
	private static final Pattern HUNT_ITEM = Pattern.compile("^(.+?)を" + NUM + HEAD_UNIT + "(集めてきて|集めて|あつめて|取ってきて|とってきて|持ってきて|手に入れて|用意して)" + END);
	private static final Pattern PICKUP = Pattern.compile("^(落ちて(い)?る|落とした|落ちた|そこの|その|近くの|まわりの|周りの|地面の)*(もの|物|アイテム|やつ|ドロップ)?を?(全部|ぜんぶ)?"
		+ "(拾ってきて|拾っておいて|拾って|ひろって|回収してきて|回収して)" + END);

	private QuickCommands() {
	}

	public static Optional<Match> match(String text, Predicate<String> itemExists) {
		String s = normalize(text);
		if (FOLLOW.matcher(s).matches()) {
			return plan("ついていくね！", new Command.Follow());
		}
		if (STOP.matcher(s).matches()) {
			return plan("わかった、止まるね。", new Command.Stop());
		}
		if (SET_HOME.matcher(s).matches()) {
			return Optional.of(Match.special(Special.SET_HOME));
		}
		if (GO_HOME.matcher(s).matches()) {
			return plan("拠点に戻るね。", new Command.MoveTo(new Place.Home()));
		}
		if (WATCH.matcher(s).matches()) {
			return Optional.of(Match.special(Special.WATCH));
		}
		for (Pattern p : List.of(NAME_BUILD, NAME_BUILD2)) {
			Matcher nm = p.matcher(s);
			if (nm.matches() && !nm.group(2).isBlank()) {
				return Optional.of(Match.special(Special.NAME_BUILD, nm.group(2)));
			}
		}
		if (INVENTORY.matcher(s).matches()) {
			return Optional.of(Match.special(Special.SHOW_INVENTORY));
		}
		if (STORE.matcher(s).matches()) {
			return plan("チェストにしまってくるね。", new Command.Store());
		}
		if (FARM.matcher(s).matches()) {
			return plan("畑の世話をしてくるね！", new Command.Farm());
		}
		for (Pattern p : List.of(TILL, TILL2)) {
			Matcher till = p.matcher(s);
			if (till.matches()) {
				int n = Math.min(Command.Till.MAX_COUNT, Quantity.parse(till.group(2)).map(QuickCommands::clamp).orElse(Command.Till.DEFAULT_COUNT));
				// 耕したら、持っている種をそのまままく
				return Optional.of(Match.plan(new Plan("耕して、種をまくね！", List.of(new Command.Till(placeOf(till.group(1)), n), new Command.Farm()), null)));
			}
		}
		if (FIGHT.matcher(s).matches()) {
			return plan("まかせて、やっつけてくる！", new Command.Fight());
		}
		if (GUARD.matcher(s).matches()) {
			return plan("まかせて、そばで守るね！", new Command.Guard());
		}
		if (PICKUP.matcher(s).matches()) {
			return plan("拾ってくるね！", new Command.Pickup());
		}
		Matcher hm = HUNT.matcher(s);
		if (hm.matches()) {
			var prey = Hunting.resolve(hm.group(1), itemExists);
			if (prey.isPresent()) {
				return plan("狩りに行ってくるね！", hunt(prey.get(), hm.group(2)));
			}
		}
		hm = HUNT_ITEM.matcher(s);
		if (hm.matches()) {
			var prey = Hunting.resolve(hm.group(1), itemExists).filter(p -> p.item() != null);
			if (prey.isPresent()) {
				return plan("狩りに行ってくるね！", hunt(prey.get(), hm.group(2)));
			}
		}
		for (Pattern p : List.of(TAKE, TAKE2)) {
			Matcher tm = p.matcher(s);
			if (tm.matches()) {
				var item = Items.resolve(tm.group(1), itemExists);
				if (item.isPresent()) {
					return plan("チェストから取ってくるね。", new Command.Take(item.get(), count(tm.group(2))));
				}
			}
		}
		Matcher m = SMELT.matcher(s);
		if (m.matches()) {
			var result = Items.resolve(m.group(1), itemExists).flatMap(Smelting::resultFor);
			if (result.isPresent()) {
				return plan("かまどで焼いてくるね！", new Command.Smelt(result.get(), count(m.group(2))));
			}
		}
		m = PLACE.matcher(s);
		if (m.matches()) {
			var item = Items.resolve(m.group(2), itemExists);
			if (item.isPresent()) {
				return plan("ここに置くね！", new Command.PlaceBlock(item.get(), new Place.Looking()));
			}
		}
		m = BUILD.matcher(s);
		if (m.matches()) {
			var bp = Blueprints.find(m.group(2));
			if (bp.isPresent()) {
				Place place = placeOf(m.group(1));
				return plan(bp.get().japaneseName() + "を建てるね！", new Command.Build(bp.get().id(), place));
			}
		}
		m = GIVE.matcher(s);
		if (m.matches()) {
			var item = Items.resolve(m.group(1), itemExists);
			if (item.isPresent()) {
				return plan("はい、どうぞ！", new Command.Give(item.get(), Quantity.parse(m.group(2)).orElse(0)));
			}
		}
		if (GIVE_WORD.matcher(s).find()) {
			// 何を渡すかが読み取れないときは、道具以外を全部渡す
			return plan("はい、どうぞ！", new Command.Give(null, 0));
		}
		m = COLLECT.matcher(s);
		if (m.matches()) {
			var item = Items.resolve(m.group(1), itemExists);
			var n = Quantity.parse(m.group(2));
			if (item.isPresent() && Items.isCollectable(item.get()) && n.isPresent()) {
				return plan("集めてくるね！", new Command.Collect(item.get(), clamp(n.get())));
			}
		}
		m = CRAFT.matcher(s);
		if (m.matches()) {
			var item = Items.resolve(m.group(1), itemExists);
			int n = clamp(Quantity.parse(m.group(2)).orElse(1));
			if (item.isPresent() && Recipes.canCraft(item.get())) {
				return plan("作ってみるね！", new Command.Craft(item.get(), n));
			}
			if (item.isPresent() && Smelting.isProduct(item.get())) {
				// ガラスや木炭のように、クラフトではなく焼いて作るもの
				return plan("かまどで焼いて作るね！", new Command.Smelt(item.get(), n));
			}
		}
		if (HOME_WORD.matcher(s).find() && WHERE_WORD.matcher(s).find()) {
			return Optional.of(Match.special(Special.WHERE_HOME));
		}
		m = REMEMBER.matcher(s);
		if (m.matches()) {
			return plan("うん、覚えておくね！", new Command.Remember(m.group(1) + (m.group(2) == null ? "" : m.group(2))));
		}
		return Optional.empty();
	}

	/** 空白と読点を取り、頭のつなぎ言葉を外す。 */
	static String normalize(String text) {
		String s = text.trim().replace("　", "").replace(" ", "").replace("、", "").replace(",", "");
		return FILLER.matcher(s).replaceFirst("");
	}

	private static Optional<Match> plan(String say, Command c) {
		return Optional.of(Match.plan(new Plan(say, List.of(c), null)));
	}

	/** WHERE で読んだ言葉を場所にする。 */
	private static Place placeOf(String where) {
		return where != null && where.startsWith("拠点") ? new Place.Home() : new Place.Looking();
	}

	private static Command hunt(Hunting.Prey prey, String quantity) {
		int n = Quantity.parse(quantity).map(QuickCommands::clamp).orElse(Hunting.DEFAULT_COUNT);
		return new Command.Hunt(prey.animal(), prey.item(), n);
	}

	/** 数の指定があればその数、なければ 0（あるだけ全部）。 */
	private static int count(String quantity) {
		return Quantity.parse(quantity).map(QuickCommands::clamp).orElse(0);
	}

	private static int clamp(int n) {
		return Math.max(1, Math.min(PlanParser.MAX_COUNT, n));
	}
}
