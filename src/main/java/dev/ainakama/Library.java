package dev.ainakama;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ainakama.core.Blueprint;
import dev.ainakama.core.Blueprints;
import dev.ainakama.core.PlanParser;
import dev.ainakama.core.SkillBook;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 覚えた技・決まり・設計図を、ファイルに保存したり読み込んだりする。
 * 置き場所は config/ainakama/ なので、どのワールドでも同じものを使える。
 */
public final class Library {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String SKILLS_FILE = "skills.json";
	private static final String BLUEPRINT_DIR = "blueprints";

	private final Path dir;

	public Library(Path dir) {
		this.dir = dir;
	}

	public void loadSkills(SkillBook book, PlanParser parser) {
		Path file = dir.resolve(SKILLS_FILE);
		if (!Files.exists(file)) {
			return;
		}
		try {
			book.load(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject(), parser);
		} catch (IOException | JsonParseException | IllegalStateException e) {
			AiNakama.LOGGER.warn("覚えた技を読み込めませんでした: {}", e.getMessage());
		}
	}

	public void saveSkills(SkillBook book) {
		write(dir.resolve(SKILLS_FILE), book.toJson());
	}

	public void loadBlueprints() {
		Path folder = dir.resolve(BLUEPRINT_DIR);
		if (!Files.isDirectory(folder)) {
			return;
		}
		try (Stream<Path> files = Files.list(folder)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
				try {
					readBlueprint(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject())
						.ifPresent(Blueprints::learn);
				} catch (IOException | JsonParseException | IllegalStateException e) {
					AiNakama.LOGGER.warn("設計図 {} を読み込めませんでした: {}", file.getFileName(), e.getMessage());
				}
			}
		} catch (IOException e) {
			AiNakama.LOGGER.warn("設計図のフォルダを読めませんでした: {}", e.getMessage());
		}
	}

	public void saveBlueprint(Blueprint bp) {
		JsonObject root = new JsonObject();
		root.addProperty("name", bp.id());
		JsonArray parts = new JsonArray();
		for (Blueprint.Part p : bp.parts()) {
			JsonObject o = new JsonObject();
			o.addProperty("x", p.dx());
			o.addProperty("y", p.dy());
			o.addProperty("z", p.dz());
			o.addProperty("block", p.block());
			if (p.state() != null) {
				o.addProperty("state", p.state());
			}
			if (p.free()) {
				o.addProperty("free", true);
			}
			parts.add(o);
		}
		root.add("parts", parts);
		write(dir.resolve(BLUEPRINT_DIR).resolve(fileName(bp.id())), root);
	}

	public void deleteBlueprint(String name) {
		try {
			Files.deleteIfExists(dir.resolve(BLUEPRINT_DIR).resolve(fileName(name)));
		} catch (IOException e) {
			AiNakama.LOGGER.warn("設計図のファイルを消せませんでした: {}", e.getMessage());
		}
	}

	private static java.util.Optional<Blueprint> readBlueprint(JsonObject root) {
		String name = root.get("name").getAsString();
		List<Blueprint.Part> parts = new ArrayList<>();
		for (JsonElement el : root.getAsJsonArray("parts")) {
			JsonObject o = el.getAsJsonObject();
			parts.add(new Blueprint.Part(
				o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
				o.get("block").getAsString(),
				o.has("state") ? o.get("state").getAsString() : null,
				o.has("free") && o.get("free").getAsBoolean()));
		}
		return parts.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(new Blueprint(name, name, parts, true));
	}

	/** ファイル名に使えない文字を避けるため、名前を16進数にする（日本語の名前でも安全）。 */
	private static String fileName(String name) {
		StringBuilder sb = new StringBuilder();
		for (byte b : Blueprints.normalize(name).getBytes(StandardCharsets.UTF_8)) {
			sb.append(String.format("%02x", b));
		}
		return sb + ".json";
	}

	private static void write(Path file, JsonObject json) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AiNakama.LOGGER.warn("{} に保存できませんでした: {}", file.getFileName(), e.getMessage());
		}
	}
}
