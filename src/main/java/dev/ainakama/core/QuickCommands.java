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
	/** 「ここまで来て」「ここに来て」：プレイヤーのところへ来る（ついてくるのではなく、1回来るだけ）。 */
	private static final Pattern COME = Pattern.compile("^(ここ|そこ|私のところ|僕のところ|俺のところ|わたしのところ)(まで|に|へ)?(来て|きて)" + END);
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

	private static final Pattern SHEAR = Pattern.compile("^(羊の?)?(毛|羊毛|ウール)を" + NUM + HEAD_UNIT + "(刈ってきて|刈って|刈り取って)" + END);
	private static final Pattern MILK = Pattern.compile("^(牛の?)?(乳しぼり|乳搾り|ちちしぼり)(を)?(してきて|して)" + END);
	private static final Pattern MILK2 = Pattern.compile("^(牛乳|ミルク)を" + NUM + "(?:杯|個|つ|本)?(?:くらい|ぐらい|ほど|分)?(しぼってきて|しぼって|搾ってきて|搾って|取ってきて|とってきて|もらってきて)" + END);
	/** 「牛を増やして」「ニワトリを2組繁殖させて」。 */
	private static final Pattern BREED = Pattern.compile("^(.+?)を(?:もっと|たくさん|少し)?" + NUM + "(?:頭|匹|羽|組|ペア)?(?:くらい|ぐらい|ほど|分)?(増やして|ふやして|繁殖させて|繁殖して)(おいて)?" + END);
	private static final Pattern FEED = Pattern.compile("^(.+?)に(エサ|えさ|餌)を(あげて|やって|与えて)" + END);
	/** 「石のツルハシがほしいな」「松明を8個用意して」。手に入れ方はプログラムが選ぶ。 */
	private static final Pattern OBTAIN = Pattern.compile("^(.+?)(?:を|が)" + NUM + UNIT
		+ "(手に入れてきて|手に入れて(?:おいて)?|用意して(?:おいて)?|調達してきて|調達して|ゲットして|準備して(?:おいて)?|(?:ほしい|欲しい)(?:な|んだ|です)?|必要(?:だ|なんだ|です)?)(?:ね|よ)?[!！。]*$");
	/** 「ケーキを作りたいから、牛乳を用意して」の「〜から、」のような、頼みの前に付いた理由。「〜てから」は順番なので外さない。 */
	private static final Pattern REASON = Pattern.compile("^.{2,40}?(?<![てで])(?:から|ので)[、,]\\s*(.+)$");

	/** 「ここを明るくして」「拠点を明るくして」「湧き潰しして」。 */
	private static final Pattern LIGHT = Pattern.compile("^" + WHERE + "(?:(?:まわり|周り|あたり|辺り)を)?(明るくして|照らして|湧き潰しして|湧きつぶしして|わきつぶしして)" + END);
	/** 「この辺に松明を置いて回って」。1本だけ置く「ここに松明を置いて」は PLACE が受ける。 */
	private static final Pattern LIGHT2 = Pattern.compile("^" + WHERE + "(松明|たいまつ)を(たくさん|いっぱい)?(置いて回って|置いてまわって|並べて|設置して回って|立てて回って)" + END);
	/** 「釣りをして」「魚を5匹釣ってきて」。 */
	private static final Pattern FISHING = Pattern.compile("^(釣り|つり|魚釣り)(を)?(してきて|して)" + END);
	private static final Pattern FISHING2 = Pattern.compile("^(魚|さかな)を" + NUM + "(?:匹|尾|個|つ)?(?:くらい|ぐらい|ほど|分)?(釣ってきて|釣って|つってきて|つって)" + END);
	private static final String TIMES = NUM + "(?:回|個|つ|枚|本)?(?:くらい|ぐらい|ほど|分)?";
	private static final Pattern SELL = Pattern.compile("^(?:村人に)?(.+?)を" + TIMES + "(?:村人に)?(売ってきて|売って)" + END);
	private static final Pattern BUY = Pattern.compile("^(?:村人から|村人と)?(.+?)を" + TIMES + "(?:村人から|村人と)?(買ってきて|買って|交換してきて|交換して)" + END);
	private static final Pattern SHOW_TRADES = Pattern.compile("^(村人の?|近くの村人の?)?(取引|交易|売り物|売っているもの)(の内容|内容)?を?(見せて|教えて|確認して|見てきて)" + END);

	private QuickCommands() {
	}

	public static Optional<Match> match(String text, Predicate<String> itemExists) {
		Optional<Match> direct = matchWhole(text, itemExists);
		if (direct.isPresent()) {
			return direct;
		}
		// 理由が前に付いているだけなら、理由を外した残りを読む
		Matcher reason = REASON.matcher(text.trim());
		return reason.matches() ? matchWhole(reason.group(1), itemExists) : Optional.empty();
	}

	private static Optional<Match> matchWhole(String text, Predicate<String> itemExists) {
		String s = normalize(text);
		if (FOLLOW.matcher(s).matches()) {
			return plan("ついていくね！", new Command.Follow());
		}
		if (COME.matcher(s).matches()) {
			return plan("今そっちに行くね！", new Command.MoveTo(new Place.NearPlayer()));
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
		for (Pattern p : List.of(LIGHT, LIGHT2)) {
			Matcher light = p.matcher(s);
			if (light.matches()) {
				return plan("松明を置いて、明るくするね！", new Command.Light(placeOf(light.group(1))));
			}
		}
		if (SHOW_TRADES.matcher(s).matches()) {
			return plan("村人に聞いてくるね。", new Command.Trade(null, false, 1));
		}
		for (Pattern p : List.of(SELL, BUY)) {
			Matcher trade = p.matcher(s);
			if (trade.matches()) {
				var item = Items.resolve(trade.group(1), itemExists);
				if (item.isPresent()) {
					int times = Quantity.parse(trade.group(2)).map(QuickCommands::clamp).orElse(1);
					return plan("村人と取引してくるね！", new Command.Trade(item.get(), p == SELL, times));
				}
			}
		}
		if (FISHING.matcher(s).matches()) {
			return plan("釣りをしてくるね！", new Command.Fish(Command.Fish.DEFAULT_COUNT));
		}
		Matcher fishing = FISHING2.matcher(s);
		if (fishing.matches()) {
			return plan("釣りをしてくるね！", new Command.Fish(Quantity.parse(fishing.group(2)).map(QuickCommands::clamp).orElse(Command.Fish.DEFAULT_COUNT)));
		}
		Matcher care = SHEAR.matcher(s);
		if (care.matches()) {
			return plan("毛を刈ってくるね！", new Command.Shear(Quantity.parse(care.group(3)).map(QuickCommands::clamp).orElse(Command.Shear.DEFAULT_COUNT)));
		}
		if (MILK.matcher(s).matches()) {
			return plan("乳しぼりしてくるね！", new Command.Milk(1));
		}
		care = MILK2.matcher(s);
		if (care.matches()) {
			return plan("乳しぼりしてくるね！", new Command.Milk(Quantity.parse(care.group(2)).map(QuickCommands::clamp).orElse(1)));
		}
		for (Pattern p : List.of(BREED, FEED)) {
			care = p.matcher(s);
			if (care.matches()) {
				var prey = Hunting.resolve(care.group(1), itemExists).filter(a -> a.item() == null && !Hunting.ANY.equals(a.animal()));
				if (prey.isPresent()) {
					int pairs = p == BREED ? Quantity.parse(care.group(2)).map(QuickCommands::clamp).orElse(1) : 1;
					return plan("エサをあげてくるね！", new Command.Breed(prey.get().animal(), pairs));
				}
			}
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
		m = OBTAIN.matcher(s);
		if (m.matches()) {
			var item = Items.resolve(m.group(1), itemExists);
			if (item.isPresent() && Obtaining.canObtain(item.get())) {
				return plan("用意するね！", new Command.Obtain(item.get(), clamp(Quantity.parse(m.group(2)).orElse(1))));
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
