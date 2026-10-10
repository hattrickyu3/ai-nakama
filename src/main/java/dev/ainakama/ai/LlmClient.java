package dev.ainakama.ai;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** AIの接続先。返事は別のスレッドで届くので、ゲームを止めずに待てる。 */
public interface LlmClient {
	/**
	 * @param system       毎回同じ説明
	 * @param user         今回の状況と言葉
	 * @param responseSchema 返事の形（JSON Schema）。対応していない接続先では無視される
	 */
	record Request(String system, String user, String responseSchema) {
	}

	/** 返事の本文と、使ったトークン数（わからなければ -1）。 */
	record Response(String text, int inputTokens, int outputTokens, long millis) {
	}

	CompletableFuture<Response> complete(Request request);

	String describe();

	/**
	 * 設定に合った接続先を作る。
	 *
	 * @param workDir claude-cli のときに、コマンドを動かす場所
	 */
	static LlmClient create(AiConfig config, Path workDir) {
		return config.provider.equals(ClaudeCliClient.PROVIDER) ? new ClaudeCliClient(config, workDir) : new HttpLlmClient(config);
	}
}
