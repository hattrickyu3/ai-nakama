package dev.ainakama.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AIを呼ぶたびに、送った量・返ってきた量・かかった時間を CSV に1行ずつ残す。
 * 実際の料金を確かめるための記録。今日の合計は /nakama usage で見られる。
 */
public final class UsageLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("ainakama");
	private static final String HEADER = "time,purpose,model,input_tokens,output_tokens,millis,result\n";

	public record Totals(LocalDate day, int calls, long inputTokens, long outputTokens, long millis) {
	}

	private final Path file;
	private Totals today = new Totals(LocalDate.now(), 0, 0, 0, 0);

	public UsageLog(Path file) {
		this.file = file;
	}

	public synchronized void record(String purpose, String model, LlmClient.Response res, String result) {
		LocalDate now = LocalDate.now();
		if (!now.equals(today.day())) {
			today = new Totals(now, 0, 0, 0, 0);
		}
		int in = res == null ? 0 : Math.max(0, res.inputTokens());
		int out = res == null ? 0 : Math.max(0, res.outputTokens());
		long ms = res == null ? 0 : res.millis();
		today = new Totals(now, today.calls() + 1, today.inputTokens() + in, today.outputTokens() + out, today.millis() + ms);
		String line = String.join(",",
			LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
			purpose, csv(model), String.valueOf(in), String.valueOf(out), String.valueOf(ms), csv(result)) + "\n";
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				Files.writeString(file, HEADER, StandardCharsets.UTF_8);
			}
			Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.warn("使用量の記録を書けませんでした: {}", e.getMessage());
		}
	}

	public synchronized Totals today() {
		if (!LocalDate.now().equals(today.day())) {
			today = new Totals(LocalDate.now(), 0, 0, 0, 0);
		}
		return today;
	}

	public Path file() {
		return file;
	}

	private static String csv(String s) {
		if (s == null) {
			return "";
		}
		String t = s.replace("\"", "'").replace("\n", " ");
		return t.contains(",") ? "\"" + t + "\"" : t;
	}
}
