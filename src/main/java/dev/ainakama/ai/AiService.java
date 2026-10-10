package dev.ainakama.ai;

import dev.ainakama.core.CallBudget;
import dev.ainakama.core.Plan;
import dev.ainakama.core.PlanParser;
import dev.ainakama.core.PromptBuilder;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AIに計画を聞く窓口。呼び出し回数の制限、返事の確認、間違っていたときの聞き直し（1回だけ）をまとめて行う。
 * Minecraft には依存しないので、接続先を差し替えてテストできる。
 */
public final class AiService {
	private static final Logger LOGGER = LoggerFactory.getLogger("ainakama");

	/** AIに聞いた結果。 */
	public sealed interface Outcome {
		/** 計画が得られた。errors が空でなければ、聞き直しても直らなかった間違いが残っている。 */
		record Success(Plan plan, List<String> errors) implements Outcome {
		}

		/** 呼び出し回数の制限で呼ばなかった。 */
		record Refused(CallBudget.Decision decision) implements Outcome {
		}

		/** 接続できなかった・時間切れなど。 */
		record Failed(String reason) implements Outcome {
		}
	}

	private final LlmClient client;
	private final CallBudget budget;
	private final PlanParser parser;
	private final UsageLog usage;
	private final String model;
	private final LongSupplier clock;

	public AiService(LlmClient client, CallBudget budget, PlanParser parser, UsageLog usage, String model, LongSupplier clock) {
		this.client = client;
		this.budget = budget;
		this.parser = parser;
		this.usage = usage;
		this.model = model;
		this.clock = clock;
	}

	/**
	 * @param caller  呼んだ仲間（呼びすぎの判定に使う）
	 * @param purpose 記録用の目的（message / failure）
	 */
	public CompletableFuture<Outcome> ask(UUID caller, String purpose, String system, String user) {
		return ask(caller, purpose, system, user, false);
	}

	/** @param ignoreCooldown 呼ぶ間隔の制限を見ない（失敗の立て直し用）。1日の上限は見る */
	public CompletableFuture<Outcome> ask(UUID caller, String purpose, String system, String user, boolean ignoreCooldown) {
		CallBudget.Decision decision = budget.tryAcquire(caller, clock.getAsLong(), ignoreCooldown);
		if (decision != CallBudget.Decision.OK) {
			return CompletableFuture.completedFuture(new Outcome.Refused(decision));
		}
		return call(purpose, system, user).thenCompose(first -> {
			if (first instanceof Attempt.Error err) {
				return CompletableFuture.completedFuture(new Outcome.Failed(err.reason()));
			}
			Attempt.Parsed parsed = (Attempt.Parsed) first;
			if (parsed.result().ok()) {
				return CompletableFuture.completedFuture(new Outcome.Success(parsed.result().plan(), List.of()));
			}
			// 間違いを添えて1回だけ聞き直す。直らなければ、読めた部分だけを使う
			String retryUser = PromptBuilder.userForRetry(user, parsed.raw(), parsed.result().errors());
			budget.recordExtra();
			return call(purpose + "-retry", system, retryUser).thenApply(second -> {
				if (second instanceof Attempt.Parsed p2 && (p2.result().ok() || !p2.result().plan().commands().isEmpty())) {
					return new Outcome.Success(p2.result().plan(), p2.result().errors());
				}
				return new Outcome.Success(parsed.result().plan(), parsed.result().errors());
			});
		});
	}

	private sealed interface Attempt {
		record Parsed(String raw, PlanParser.Result result) implements Attempt {
		}

		record Error(String reason) implements Attempt {
		}
	}

	private CompletableFuture<Attempt> call(String purpose, String system, String user) {
		return client.complete(new LlmClient.Request(system, user, PromptBuilder.RESPONSE_SCHEMA))
			.handle((res, err) -> {
				if (err != null) {
					Throwable cause = err.getCause() != null ? err.getCause() : err;
					usage.record(purpose, model, null, "error: " + cause.getClass().getSimpleName());
					return new Attempt.Error(describe(cause));
				}
				PlanParser.Result result = parser.parse(res.text());
				usage.record(purpose, model, res, result.ok() ? "ok" : "invalid");
				if (!result.ok()) {
					// モデル選びやプロンプト調整のために、どこが間違っていたかを残す
					LOGGER.info("AIの返事に間違い（{}）: {} / 返事: {}", purpose, result.errors(), res.text().replace('\n', ' '));
				}
				return new Attempt.Parsed(res.text(), result);
			});
	}

	private static String describe(Throwable t) {
		String name = t.getClass().getSimpleName();
		if (name.contains("Connect")) {
			return "AIにつながらない（Ollama が起動しているか確認してね）";
		}
		if (name.contains("Timeout")) {
			return "AIの返事が時間内に来なかった";
		}
		// 接続先のエラー本文にはAPIキーの一部などが含まれることがあるので、チャットには出さずログにだけ残す
		LOGGER.warn("AIとのやりとりでエラー: {}", t.toString());
		return "AIとのやりとりでエラーが起きた（詳しくはログ）";
	}

	public CallBudget budget() {
		return budget;
	}

	public UsageLog usage() {
		return usage;
	}

	public String describeClient() {
		return client.describe();
	}
}
