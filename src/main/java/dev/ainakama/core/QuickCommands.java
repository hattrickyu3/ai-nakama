package dev.ainakama.core;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 決まった言い方の指示を、AIを使わずに命令にする。ここで読めなかった言葉だけをAIに回す。
 */
public final class QuickCommands {
	/** AIを通さずに処理する特別な指示（命令の列ではなく、仲間の設定を変えるもの）。 */
	public enum Special {
		SET_HOME, SHOW_INVENTORY
	}

	public record Match(Optional<Plan> plan, Optional<Special> special) {
		static Match plan(Plan p) {
			return new Match(Optional.of(p), Optional.empty());
		}

		static Match special(Special s) {
			return new Match(Optional.empty(), Optional.of(s));
		}
	}

	private static final Pattern FOLLOW = Pattern.compile("^(ついてきて|ついて来て|ついてこい|follow( me)?|おいで|来て|きて)[!！。]*$");
	private static final Pattern STOP = Pattern.compile("^(止まって|とまって|ストップ|stop|待って|まって|やめて)[!！。]*$");
	private static final Pattern GO_HOME = Pattern.compile("^(拠点に|家に)?(帰って|戻って|かえって|もどって)[!！。]*$");
	private static final Pattern SET_HOME = Pattern.compile("^(ここを拠点に(して)?|ここが拠点(だよ)?)[!！。]*$");
	private static final Pattern INVENTORY = Pattern.compile("^(持ち物(は)?|何持ってる[?？]?|もちもの)[!！。?？]*$");
	private static final Pattern STORE = Pattern.compile("^(チェストに)?(しまって|片付けて|かたづけて)[!！。]*$");
	private static final Pattern COLLECT = Pattern.compile("^(.+?)を(\\d+)(個|本|つ|コ)?(集めて|あつめて|取ってきて|とってきて|掘って|切って)[!！。]*$");
	private static final Pattern CRAFT = Pattern.compile("^(.+?)を(\\d+)?(個|本|つ|コ)?(作って|つくって|クラフトして)[!！。]*$");
	private static final Pattern BUILD = Pattern.compile("^(ここに|拠点に)?(.+?)を(建てて|たてて|作って|つくって)[!！。]*$");

	private QuickCommands() {
	}

	public static Optional<Match> match(String text, Predicate<String> itemExists) {
		String s = text.trim().replace("　", " ");
		if (FOLLOW.matcher(s).matches()) {
			return Optional.of(Match.plan(withSay("ついていくね！", new Command.Follow())));
		}
		if (STOP.matcher(s).matches()) {
			return Optional.of(Match.plan(withSay("わかった、止まるね。", new Command.Stop())));
		}
		if (GO_HOME.matcher(s).matches()) {
			return Optional.of(Match.plan(withSay("拠点に戻るね。", new Command.MoveTo(new Place.Home()))));
		}
		if (SET_HOME.matcher(s).matches()) {
			return Optional.of(Match.special(Special.SET_HOME));
		}
		if (INVENTORY.matcher(s).matches()) {
			return Optional.of(Match.special(Special.SHOW_INVENTORY));
		}
		if (STORE.matcher(s).matches()) {
			return Optional.of(Match.plan(withSay("チェストにしまってくるね。", new Command.Store())));
		}
		Matcher m = BUILD.matcher(s);
		if (m.matches()) {
			var bp = Blueprint.find(m.group(2));
			if (bp.isPresent()) {
				Place place = "拠点に".equals(m.group(1)) ? new Place.Home() : new Place.NearPlayer();
				return Optional.of(Match.plan(withSay(bp.get().japaneseName() + "を建てるね！",
					new Command.Build(bp.get().id(), place))));
			}
		}
		m = COLLECT.matcher(s);
		if (m.matches()) {
			var item = Items.resolve(m.group(1), itemExists);
			if (item.isPresent()) {
				int n = clamp(Integer.parseInt(m.group(2)));
				return Optional.of(Match.plan(withSay("集めてくるね！", new Command.Collect(item.get(), n))));
			}
		}
		m = CRAFT.matcher(s);
		if (m.matches()) {
			var item = Items.resolve(m.group(1), itemExists);
			if (item.isPresent() && Recipes.canCraft(item.get())) {
				int n = m.group(2) == null ? 1 : clamp(Integer.parseInt(m.group(2)));
				return Optional.of(Match.plan(withSay("作ってみるね！", new Command.Craft(item.get(), n))));
			}
		}
		return Optional.empty();
	}

	private static Plan withSay(String say, Command c) {
		return new Plan(say, java.util.List.of(c), null);
	}

	private static int clamp(int n) {
		return Math.max(1, Math.min(PlanParser.MAX_COUNT, n));
	}
}
