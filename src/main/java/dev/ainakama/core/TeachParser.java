package dev.ainakama.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「〇〇っていうのは、△△して□□すること」「夜になったら〇〇して」のような、教える言い方を読む。
 * 小さいAIは「教わった」と気づけないことが多いので、名前ときっかけはここで確実に読み取り、
 * 手順も、決まった言い方（{@link QuickCommands}）で読めるならAIを使わずに命令にする。
 */
public final class TeachParser {
	/**
	 * 読み取った「教え」。
	 *
	 * @param name        技の名前（決まりのときは null）
	 * @param trigger     決まりのきっかけ（技のときは null）
	 * @param explanation やり方の説明（元の言葉のまま）
	 * @param steps       説明を命令にしたもの。全部は読めなかったら空（AIに直してもらう）
	 */
	public record Teaching(String name, Trigger trigger, String explanation, List<Command> steps) {
		public boolean complete() {
			return !steps.isEmpty();
		}
	}

	private static final Pattern SKILL = Pattern.compile(
		"^「?(.{1,30}?)」?(っていうのは|というのは|って言うのは|と言うのは|っていうのはね|のやり方は|のやりかたは|は次のようにやる)[、,:：]?(.+)$");
	private static final Pattern CLAUSE_SPLIT = Pattern.compile("、|。|,|そのあと|その後|それから|次に|つぎに|してから|(?<=[てで])から");
	/** 「木を集めて作業台を作る」のように読点なしで続く文を、頼む形の動詞のあとで区切るためのもの。長い形を先に並べる。 */
	private static final Pattern JOINED = Pattern.compile(
		"(取ってきて|掘ってきて|切ってきて|ついてきて|集めて|あつめて|掘って|切って|作って|つくって|建てて|置いて|渡して|しまって|入れて|帰って|戻って)(?=.)");
	private static final Pattern CLAUSE_HEAD = Pattern.compile("^(から|そして|あと|で|、)+");
	private static final Pattern TAIL = Pattern.compile("(っていうこと|ということ|ってこと|こと)?(だよ|なんだよ|なんだ|です|だ|ね|よ|んだよ)?[。!！]*$");

	private static final Map<Pattern, Trigger> TRIGGERS = new LinkedHashMap<>();
	/** 「集める」「作ること」のような言い切りの形を、頼む形（〜て）に直す。 */
	private static final Map<String, String> TO_REQUEST = new LinkedHashMap<>();

	static {
		trigger("^(夜|暗く)になったら", Trigger.NIGHT);
		trigger("^(朝|明るく)になったら", Trigger.MORNING);
		trigger("^雨が(降ったら|ふったら|降ってきたら)", Trigger.RAIN);
		trigger("^(敵|モンスター|ゾンビ)が(来たら|きたら|近づいたら|出たら|でたら|いたら)", Trigger.ENEMY_NEAR);
		trigger("^(持ち物|荷物|インベントリ)が(いっぱい|満タン)に?なったら", Trigger.INVENTORY_FULL);
		trigger("^(私|僕|俺|わたし|ぼく|プレイヤー)が(遠くに行ったら|遠くへ行ったら|離れたら|いなくなったら)", Trigger.PLAYER_FAR);
		trigger("^(暇|ひま)に?なったら|^やることが(なくなったら|ないときは)|^手が(空いたら|あいたら)", Trigger.IDLE);

		TO_REQUEST.put("集める", "集めて");
		TO_REQUEST.put("あつめる", "あつめて");
		TO_REQUEST.put("取ってくる", "取ってきて");
		TO_REQUEST.put("掘ってくる", "掘ってきて");
		TO_REQUEST.put("掘る", "掘って");
		TO_REQUEST.put("切る", "切って");
		TO_REQUEST.put("作る", "作って");
		TO_REQUEST.put("つくる", "つくって");
		TO_REQUEST.put("建てる", "建てて");
		TO_REQUEST.put("置く", "置いて");
		TO_REQUEST.put("渡す", "渡して");
		TO_REQUEST.put("しまう", "しまって");
		TO_REQUEST.put("入れる", "入れて");
		TO_REQUEST.put("帰る", "帰って");
		TO_REQUEST.put("戻る", "戻って");
		TO_REQUEST.put("止まる", "止まって");
		TO_REQUEST.put("ついてくる", "ついてきて");
	}

	private TeachParser() {
	}

	private static void trigger(String regex, Trigger t) {
		TRIGGERS.put(Pattern.compile(regex), t);
	}

	/** 教える言い方なら、名前（またはきっかけ）と手順を返す。 */
	public static Optional<Teaching> parse(String text, Predicate<String> itemExists) {
		String s = QuickCommands.normalize(text.replace("、", "\u0001")).replace("\u0001", "、");
		for (var e : TRIGGERS.entrySet()) {
			Matcher m = e.getKey().matcher(s);
			if (m.find()) {
				String rest = s.substring(m.end()).replaceFirst("^[、,]", "");
				return rest.isBlank() ? Optional.empty()
					: Optional.of(new Teaching(null, e.getValue(), rest, steps(rest, itemExists)));
			}
		}
		Matcher m = SKILL.matcher(s);
		if (m.matches()) {
			String name = m.group(1).replaceFirst("^(あのね|えっと)", "").strip();
			String explanation = m.group(3).strip();
			if (!name.isBlank() && !explanation.isBlank()) {
				return Optional.of(new Teaching(name, null, explanation, steps(explanation, itemExists)));
			}
		}
		return Optional.empty();
	}

	/**
	 * 説明を「、」や「それから」で区切り、1つずつ決まった言い方として読む。
	 * 1つでも読めないものがあれば空を返す（中途半端な手順を覚えないため）。
	 */
	public static List<Command> steps(String explanation, Predicate<String> itemExists) {
		List<Command> commands = new ArrayList<>();
		String separated = JOINED.matcher(explanation).replaceAll("$1、");
		for (String clause : CLAUSE_SPLIT.split(separated)) {
			String request = toRequest(CLAUSE_HEAD.matcher(clause.strip()).replaceFirst(""));
			if (request.isBlank()) {
				continue;
			}
			var match = QuickCommands.match(request, itemExists);
			if (match.isEmpty() || match.get().plan().isEmpty()) {
				return List.of();
			}
			commands.addAll(match.get().plan().get().commands());
		}
		return commands.size() > PlanParser.MAX_STEPS ? List.of() : List.copyOf(commands);
	}

	/** 「作業台を1個作ることだよ」→「作業台を1個作って」。 */
	static String toRequest(String clause) {
		String s = TAIL.matcher(clause.strip()).replaceFirst("");
		for (var e : TO_REQUEST.entrySet()) {
			if (s.endsWith(e.getKey())) {
				return s.substring(0, s.length() - e.getKey().length()) + e.getValue();
			}
		}
		return s;
	}
}
