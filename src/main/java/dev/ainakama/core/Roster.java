package dev.ainakama.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 仲間が最後にいた場所の名簿。
 * 遠くにいる仲間は、まわりの土地ごとゲームから読み込まれなくなり、探しても見つからない。
 * 「遠くにいて聞こえない」と知らせたり、呼び寄せたりできるよう、最後にいた場所を覚えておく。
 */
public final class Roster {
	/** dimension は "minecraft:overworld" のような、世界の名前。 */
	public record Entry(String name, String dimension, int x, int y, int z) {
		/** 水平の距離（マス）。 */
		public int blocksFrom(double fromX, double fromZ) {
			return (int) Math.round(Math.hypot(x - fromX, z - fromZ));
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Map<UUID, Entry> entries = new LinkedHashMap<>();
	private boolean dirty;

	public void put(UUID id, Entry entry) {
		if (!entry.equals(entries.put(id, entry))) {
			dirty = true;
		}
	}

	public void remove(UUID id) {
		if (entries.remove(id) != null) {
			dirty = true;
		}
	}

	public Map<UUID, Entry> all() {
		return Collections.unmodifiableMap(entries);
	}

	/** 前に聞かれたあとで中身が変わったか（保存が要るか）。聞くと「変わっていない」に戻る。 */
	public boolean takeDirty() {
		boolean was = dirty;
		dirty = false;
		return was;
	}

	public String toJson() {
		JsonObject root = new JsonObject();
		entries.forEach((id, entry) -> root.add(id.toString(), GSON.toJsonTree(entry)));
		return GSON.toJson(root);
	}

	/** 読めないところは飛ばす（名簿は、仲間が読み込まれれば作り直される）。 */
	public static Roster fromJson(String json) {
		Roster roster = new Roster();
		try {
			JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject()) {
				return roster;
			}
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
				Entry entry = GSON.fromJson(e.getValue(), Entry.class);
				if (entry != null && entry.name() != null && entry.dimension() != null) {
					roster.entries.put(UUID.fromString(e.getKey()), entry);
				}
			}
		} catch (JsonParseException | IllegalArgumentException e) {
			return new Roster();
		}
		return roster;
	}
}
