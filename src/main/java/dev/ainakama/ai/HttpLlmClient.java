package dev.ainakama.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * HTTPでつなぐ接続先（Ollama / Anthropic / OpenAI互換）。
 * 違うのは送る形と返事の読み方だけなので、1つのクラスにまとめている。
 */
public final class HttpLlmClient implements LlmClient {
	private static final String ANTHROPIC_VERSION = "2023-06-01";

	private final AiConfig config;
	private final HttpClient http;

	public HttpLlmClient(AiConfig config) {
		this.config = config;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	@Override
	public String describe() {
		return config.provider + " / " + config.model;
	}

	@Override
	public CompletableFuture<Response> complete(Request request) {
		long start = System.currentTimeMillis();
		HttpRequest httpRequest;
		try {
			httpRequest = build(request);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}
		return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
			.thenApply(res -> {
				if (res.statusCode() / 100 != 2) {
					throw new LlmException("HTTP " + res.statusCode() + ": " + abbreviate(res.body()));
				}
				return read(res.body(), System.currentTimeMillis() - start);
			});
	}

	private HttpRequest build(Request request) {
		String base = config.baseUrl.endsWith("/") ? config.baseUrl.substring(0, config.baseUrl.length() - 1) : config.baseUrl;
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.timeout(Duration.ofSeconds(config.timeoutSeconds))
			.header("Content-Type", "application/json");
		JsonObject body = new JsonObject();
		switch (config.provider) {
			case "anthropic" -> {
				b.uri(URI.create(base + "/v1/messages"))
					.header("x-api-key", config.effectiveApiKey())
					.header("anthropic-version", ANTHROPIC_VERSION);
				body.addProperty("model", config.model);
				body.addProperty("max_tokens", config.maxOutputTokens);
				body.addProperty("temperature", config.temperature);
				// 毎回同じ system はキャッシュ対象にする（短いうちは効かないが、害はない）
				JsonArray system = new JsonArray();
				JsonObject sys = new JsonObject();
				sys.addProperty("type", "text");
				sys.addProperty("text", request.system());
				JsonObject cache = new JsonObject();
				cache.addProperty("type", "ephemeral");
				sys.add("cache_control", cache);
				system.add(sys);
				body.add("system", system);
				body.add("messages", messages(null, request.user()));
			}
			case "openai" -> {
				b.uri(URI.create(base + "/v1/chat/completions"))
					.header("Authorization", "Bearer " + config.effectiveApiKey());
				body.addProperty("model", config.model);
				body.addProperty("temperature", config.temperature);
				body.addProperty("max_tokens", config.maxOutputTokens);
				JsonObject format = new JsonObject();
				format.addProperty("type", "json_object");
				body.add("response_format", format);
				body.add("messages", messages(request.system(), request.user()));
			}
			default -> {
				b.uri(URI.create(base + "/api/chat"));
				body.addProperty("model", config.model);
				body.addProperty("stream", false);
				// qwen3.5 などは考える過程を出すと遅くなるので切る
				body.addProperty("think", false);
				JsonObject options = new JsonObject();
				options.addProperty("temperature", config.temperature);
				options.addProperty("num_predict", config.maxOutputTokens);
				body.add("options", options);
				if (request.responseSchema() != null) {
					body.add("format", JsonParser.parseString(request.responseSchema()));
				}
				body.add("messages", messages(request.system(), request.user()));
			}
		}
		return b.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
	}

	private static JsonArray messages(String system, String user) {
		JsonArray arr = new JsonArray();
		if (system != null) {
			arr.add(message("system", system));
		}
		arr.add(message("user", user));
		return arr;
	}

	private static JsonObject message(String role, String content) {
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}

	Response read(String body, long millis) {
		JsonObject o = JsonParser.parseString(body).getAsJsonObject();
		return switch (config.provider) {
			case "anthropic" -> {
				StringBuilder text = new StringBuilder();
				for (JsonElement part : o.getAsJsonArray("content")) {
					JsonObject p = part.getAsJsonObject();
					if ("text".equals(stringOr(p, "type"))) {
						text.append(stringOr(p, "text"));
					}
				}
				JsonObject usage = o.getAsJsonObject("usage");
				int in = intOr(usage, "input_tokens") + Math.max(0, intOr(usage, "cache_read_input_tokens"))
					+ Math.max(0, intOr(usage, "cache_creation_input_tokens"));
				yield new Response(text.toString(), in, intOr(usage, "output_tokens"), millis);
			}
			case "openai" -> {
				String text = o.getAsJsonArray("choices").get(0).getAsJsonObject()
					.getAsJsonObject("message").get("content").getAsString();
				JsonObject usage = o.getAsJsonObject("usage");
				yield new Response(text, intOr(usage, "prompt_tokens"), intOr(usage, "completion_tokens"), millis);
			}
			default -> new Response(
				o.getAsJsonObject("message").get("content").getAsString(),
				intOr(o, "prompt_eval_count"), intOr(o, "eval_count"), millis);
		};
	}

	private static String stringOr(JsonObject o, String key) {
		JsonElement el = o == null ? null : o.get(key);
		return el == null || el.isJsonNull() ? "" : el.getAsString();
	}

	private static int intOr(JsonObject o, String key) {
		JsonElement el = o == null ? null : o.get(key);
		return el == null || el.isJsonNull() ? -1 : el.getAsInt();
	}

	private static String abbreviate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() <= 300 ? s : s.substring(0, 300) + "…";
	}

	/** 接続先がエラーを返したとき。 */
	public static final class LlmException extends RuntimeException {
		public LlmException(String message) {
			super(message);
		}
	}
}
