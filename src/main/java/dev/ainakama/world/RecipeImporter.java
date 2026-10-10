package dev.ainakama.world;

import dev.ainakama.AiNakama;
import dev.ainakama.ModServices;
import dev.ainakama.core.Items;
import dev.ainakama.core.Recipes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * マイクラ本体のクラフトのレシピを読み、仲間が使える形（{@link Recipes}）にする。
 * これで、手書きの表にない物（はしご・ベッド・ボートなど）も作れるようになる。かまどで焼くレシピは対象外。
 */
public final class RecipeImporter {
	/** 2×2に収まらないレシピは作業台が要る。 */
	private static final int HAND_GRID = 2;
	private static final int HAND_SLOTS = HAND_GRID * HAND_GRID;

	private RecipeImporter() {
	}

	public static void importFrom(MinecraftServer server) {
		List<Recipes.Recipe> recipes = new ArrayList<>();
		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			try {
				convert(holder.value(), recipes);
			} catch (RuntimeException e) {
				AiNakama.LOGGER.debug("レシピを読めませんでした: {}", holder.id(), e);
			}
		}
		Recipes.install(recipes);
		AiNakama.LOGGER.info("クラフトのレシピを {} 種類読み込みました", Recipes.count());
		ModServices.reloadSkills();
	}

	private static void convert(Recipe<?> recipe, List<Recipes.Recipe> out) {
		boolean needsTable;
		if (recipe instanceof ShapedRecipe shaped) {
			needsTable = shaped.getWidth() > HAND_GRID || shaped.getHeight() > HAND_GRID;
		} else if (recipe instanceof ShapelessRecipe) {
			needsTable = recipe.placementInfo().ingredients().size() > HAND_SLOTS;
		} else {
			return;
		}
		ItemStack result = recipe instanceof ShapedRecipe s ? s.assemble(CraftingInput.EMPTY) : ((ShapelessRecipe) recipe).assemble(CraftingInput.EMPTY);
		if (result.isEmpty()) {
			return;
		}
		// placementInfo の ingredients は種類ごとの一覧なので、マス目ごとの数は slotsToIngredientIndex で数える
		List<Ingredient> kinds = recipe.placementInfo().ingredients();
		int[] counts = new int[kinds.size()];
		for (int index : recipe.placementInfo().slotsToIngredientIndex()) {
			if (index >= 0 && index < counts.length) {
				counts[index]++;
			}
		}
		Map<String, Integer> ingredients = new LinkedHashMap<>();
		for (int i = 0; i < kinds.size(); i++) {
			List<String> ids = kinds.get(i).items().map(h -> Inv.idOf(h.value())).sorted().toList();
			if (ids.isEmpty() || counts[i] == 0) {
				return;
			}
			ingredients.merge(Items.keyFor(ids), counts[i], Integer::sum);
		}
		if (!ingredients.isEmpty()) {
			out.add(new Recipes.Recipe(Inv.idOf(result.getItem()), result.getCount(), ingredients, needsTable));
		}
	}
}
