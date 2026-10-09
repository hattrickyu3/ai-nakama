package dev.ainakama.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ainakama.core.CallBudget;
import dev.ainakama.core.Command;
import dev.ainakama.core.PlanParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiServiceTest {
	@TempDir
	Path tmp;

	/** 決めた返事を順に返す、テスト用の接続先。 */
	static final class FakeClient implements LlmClient {
		final Deque<Object> replies = new ArrayDeque<>();
		int calls;

		@Override
		public CompletableFuture<Response> complete(Request request) {
			calls++;
			Object r = replies.removeFirst();
			if (r instanceof Throwable t) {
				return CompletableFuture.failedFuture(t);
			}
			return CompletableFuture.completedFuture(new Response((String) r, 1000, 100, 50));
		}

		@Override
		public String describe() {
			return "fake";
		}
	}

	private AiService service(FakeClient client, int dailyLimit) {
		return new AiService(client,
			new CallBudget(5000, dailyLimit, ZoneId.of("Asia/Tokyo")),
			new PlanParser(Set.of("oak_log", "stick")::contains),
			new UsageLog(tmp.resolve("usage.csv")),
			"fake-model",
			() -> 1_800_000_000_000L);
	}

	@Test
	void validReplyIsUsedDirectly() {
		FakeClient c = new FakeClient();
		c.replies.add("{\"say\":\"了解\",\"plan\":[{\"do\":\"collect\",\"item\":\"oak_log\",\"count\":5}]}");

		var out = service(c, 10).ask(UUID.randomUUID(), "message", "sys", "user").join();

		var success = assertInstanceOf(AiService.Outcome.Success.class, out);
		assertEquals(new Command.Collect("oak_log", 5), success.plan().commands().get(0));
		assertEquals(1, c.calls);
	}

	@Test
	void invalidReplyIsRetriedOnce() {
		FakeClient c = new FakeClient();
		c.replies.add("{\"say\":\"了解\",\"plan\":[{\"do\":\"collect\",\"item\":\"unobtainium\",\"count\":5}]}");
		c.replies.add("{\"say\":\"了解\",\"plan\":[{\"do\":\"collect\",\"item\":\"oak_log\",\"count\":5}]}");

		var out = service(c, 10).ask(UUID.randomUUID(), "message", "sys", "user").join();

		var success = assertInstanceOf(AiService.Outcome.Success.class, out);
		assertTrue(success.errors().isEmpty());
		assertEquals(2, c.calls);
	}

	@Test
	void connectionErrorBecomesFailure() {
		FakeClient c = new FakeClient();
		c.replies.add(new java.net.ConnectException("refused"));

		var out = service(c, 10).ask(UUID.randomUUID(), "message", "sys", "user").join();

		var failed = assertInstanceOf(AiService.Outcome.Failed.class, out);
		assertTrue(failed.reason().contains("Ollama"));
	}

	@Test
	void dailyLimitStopsCalls() throws Exception {
		FakeClient c = new FakeClient();
		c.replies.add("{\"say\":\"\",\"plan\":[]}");
		AiService s = service(c, 1);

		s.ask(UUID.randomUUID(), "message", "sys", "user").join();
		var out = s.ask(UUID.randomUUID(), "message", "sys", "user").join();

		assertInstanceOf(AiService.Outcome.Refused.class, out);
		assertEquals(1, c.calls);
		assertTrue(Files.readString(tmp.resolve("usage.csv")).contains("fake-model,1000,100,50,ok"));
	}

	@Test
	void readsEachProviderFormat() {
		AiConfig cfg = new AiConfig();
		cfg.provider = "ollama";
		var r = new HttpLlmClient(cfg).read("{\"message\":{\"content\":\"{}\"},\"prompt_eval_count\":145,\"eval_count\":82}", 10);
		assertEquals(145, r.inputTokens());
		assertEquals(82, r.outputTokens());

		cfg.provider = "anthropic";
		r = new HttpLlmClient(cfg).read("{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],\"usage\":{\"input_tokens\":20,\"cache_read_input_tokens\":700,\"output_tokens\":90}}", 10);
		assertEquals("{}", r.text());
		assertEquals(720, r.inputTokens());

		cfg.provider = "openai";
		r = new HttpLlmClient(cfg).read("{\"choices\":[{\"message\":{\"content\":\"{}\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}", 10);
		assertEquals(11, r.inputTokens());
	}

	@Test
	void configFixesBadValues() {
		AiConfig cfg = new AiConfig();
		cfg.provider = "skynet";
		cfg.timeoutSeconds = 0;
		var problems = cfg.validate();
		assertEquals("ollama", cfg.provider);
		assertEquals(10, cfg.timeoutSeconds);
		assertTrue(problems.stream().anyMatch(p -> p.contains("skynet")));
	}
}
