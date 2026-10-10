package dev.ainakama.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeCliClientTest {
	@Test
	void readsTheReplyAndTokenCounts() {
		String body = "{\"type\":\"result\",\"is_error\":false,\"result\":\"{\\\"say\\\":\\\"うん！\\\",\\\"plan\\\":[]}\","
			+ "\"usage\":{\"input_tokens\":2,\"cache_creation_input_tokens\":1169,\"cache_read_input_tokens\":30,\"output_tokens\":74}}";

		LlmClient.Response res = ClaudeCliClient.read(body, 0, 4200);

		assertEquals("{\"say\":\"うん！\",\"plan\":[]}", res.text());
		assertEquals(1201, res.inputTokens());
		assertEquals(74, res.outputTokens());
		assertEquals(4200, res.millis());
	}

	@Test
	void errorReplyIsAFailure() {
		String body = "{\"type\":\"result\",\"is_error\":true,\"result\":\"Not logged in · Please run /login\"}";

		var e = assertThrows(HttpLlmClient.LlmException.class, () -> ClaudeCliClient.read(body, 1, 100));

		assertTrue(e.getMessage().contains("Not logged in"));
	}

	@Test
	void emptyOrBrokenOutputIsAFailure() {
		assertThrows(HttpLlmClient.LlmException.class, () -> ClaudeCliClient.read("", 1, 100));
		assertThrows(HttpLlmClient.LlmException.class, () -> ClaudeCliClient.read("Error: something", 1, 100));
	}

	@Test
	void commandTurnsOffToolsAndUserSettings() {
		List<String> command = ClaudeCliClient.command("claude", "haiku", Path.of("work", "system.txt"));

		assertEquals(List.of("claude", "-p"), command.subList(0, 2));
		assertEquals("haiku", after(command, "--model"));
		assertEquals("json", after(command, "--output-format"));
		// AIには計画を答えさせるだけ。PCのファイルやコマンドには触らせない
		assertEquals("", after(command, "--tools"));
		// ふだんの Claude Code の設定（フックなど）を読み込まない
		assertEquals("", after(command, "--setting-sources"));
		assertTrue(command.containsAll(List.of("--strict-mcp-config", "--disable-slash-commands", "--no-session-persistence")));
		assertEquals(Path.of("work", "system.txt").toString(), after(command, "--system-prompt-file"));
		assertFalse(command.contains("--bare"));
	}

	@Test
	void configAcceptsClaudeCliWithoutApiKey() {
		AiConfig config = new AiConfig();
		config.provider = "claude-cli";
		config.model = "haiku";

		List<String> problems = new ArrayList<>(config.validate());

		assertEquals(List.of(), problems);
		assertEquals("claude-cli", config.provider);
		assertInstanceOf(ClaudeCliClient.class, LlmClient.create(config, Path.of("work")));
		assertEquals("claude-cli / haiku", LlmClient.create(config, Path.of("work")).describe());
	}

	private static String after(List<String> command, String flag) {
		return command.get(command.indexOf(flag) + 1);
	}
}
