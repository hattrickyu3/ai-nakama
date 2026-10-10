package dev.ainakama.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * PCに入っている Claude Code（claude コマンド）を呼んで返事をもらう接続先。
 * Claude Code のログイン（サブスク）をそのまま使うので、APIキーがいらない。
 * 呼ぶたびにコマンドを起動するので、返事まで4〜5秒かかる。
 * AIには計画を答えさせるだけで、PCのファイルやコマンドには触らせない（ツールをすべて切って呼ぶ）。
 */
public final class ClaudeCliClient implements LlmClient {
	public static final String PROVIDER = "claude-cli";

	/** サブスクではなくAPIの料金で動いてしまわないよう、コマンドには渡さない環境変数。 */
	private static final List<String> API_BILLING_ENV = List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN");

	private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "ainakama-claude-cli");
		t.setDaemon(true);
		return t;
	});

	private final AiConfig config;
	private final Path workDir;

	/** @param workDir コマンドを動かす場所。説明文と返事の一時ファイルを置く */
	public ClaudeCliClient(AiConfig config, Path workDir) {
		this.config = config;
		this.workDir = workDir.toAbsolutePath();
	}

	@Override
	public String describe() {
		return config.provider + " / " + config.model;
	}

	@Override
	public CompletableFuture<Response> complete(Request request) {
		return CompletableFuture.supplyAsync(() -> run(request), POOL);
	}

	private Response run(Request request) {
		long start = System.currentTimeMillis();
		Path reply = null;
		try {
			Files.createDirectories(workDir);
			// 説明文は長く、改行も多いので、引数ではなくファイルで渡す。仲間ごとに中身が違うので、中身ごとに分ける
			Path systemFile = workDir.resolve("system-" + Integer.toHexString(request.system().hashCode()) + ".txt");
			Files.writeString(systemFile, request.system(), StandardCharsets.UTF_8);
			reply = Files.createTempFile(workDir, "reply-", ".json");

			ProcessBuilder builder = new ProcessBuilder(command(config.claudeCommand, config.model, systemFile));
			builder.directory(workDir.toFile());
			builder.environment().keySet().removeAll(API_BILLING_ENV);
			builder.redirectOutput(reply.toFile());
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process process = builder.start();
			try (OutputStream stdin = process.getOutputStream()) {
				stdin.write(request.user().getBytes(StandardCharsets.UTF_8));
			}
			if (!process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new CliTimeoutException();
			}
			return read(Files.readString(reply, StandardCharsets.UTF_8), process.exitValue(), System.currentTimeMillis() - start);
		} catch (IOException e) {
			throw new UncheckedIOException("claude コマンドを動かせなかった（Claude Code が入っているか、claudeCommand の設定を確かめてね）", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HttpLlmClient.LlmException("claude コマンドを待っている間に中断された");
		} finally {
			deleteQuietly(reply);
		}
	}

	/** 道具・設定・MCP・スキルをすべて切って、1回だけ答えさせる。 */
	static List<String> command(String claudeCommand, String model, Path systemFile) {
		return List.of(claudeCommand, "-p",
			"--model", model,
			"--output-format", "json",
			"--tools", "",
			"--setting-sources", "",
			"--strict-mcp-config",
			"--disable-slash-commands",
			"--no-session-persistence",
			"--system-prompt-file", systemFile.toString());
	}

	/** コマンドの出力（JSON）から、返事の本文と使った量を取り出す。 */
	static Response read(String body, int exitCode, long millis) {
		JsonObject o;
		try {
			o = JsonParser.parseString(body).getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			throw new HttpLlmClient.LlmException("claude コマンドの返事を読めなかった（終了コード " + exitCode + "）: " + abbreviate(body));
		}
		String text = stringOr(o, "result");
		JsonElement isError = o.get("is_error");
		if (isError != null && !isError.isJsonNull() && isError.getAsBoolean()) {
			throw new HttpLlmClient.LlmException("claude コマンドがエラーを返した: " + abbreviate(text));
		}
		JsonObject usage = o.has("usage") && o.get("usage").isJsonObject() ? o.getAsJsonObject("usage") : new JsonObject();
		int in = Math.max(0, intOr(usage, "input_tokens")) + Math.max(0, intOr(usage, "cache_read_input_tokens"))
			+ Math.max(0, intOr(usage, "cache_creation_input_tokens"));
		return new Response(text, in, Math.max(0, intOr(usage, "output_tokens")), millis);
	}

	private static String stringOr(JsonObject o, String key) {
		JsonElement el = o.get(key);
		return el == null || el.isJsonNull() ? "" : el.getAsString();
	}

	private static int intOr(JsonObject o, String key) {
		JsonElement el = o.get(key);
		return el == null || el.isJsonNull() ? -1 : el.getAsInt();
	}

	private static String abbreviate(String s) {
		return s.length() <= 300 ? s : s.substring(0, 300) + "…";
	}

	private static void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			// 一時ファイルが残るだけなので、返事は捨てない
			file.toFile().deleteOnExit();
		}
	}

	/** 時間内に返事が来なかったとき。 */
	public static final class CliTimeoutException extends RuntimeException {
		public CliTimeoutException() {
			super("claude コマンドの返事が時間内に来なかった");
		}
	}
}
