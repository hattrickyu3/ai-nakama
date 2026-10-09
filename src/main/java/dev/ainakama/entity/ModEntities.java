package dev.ainakama.entity;

import dev.ainakama.AiNakama;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	private static final ResourceKey<EntityType<?>> NAKAMA_KEY = ResourceKey.create(Registries.ENTITY_TYPE, AiNakama.id("nakama"));

	/** 大きさは村人と同じ。 */
	public static final EntityType<NakamaEntity> NAKAMA = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		NAKAMA_KEY,
		EntityType.Builder.of(NakamaEntity::new, MobCategory.MISC)
			.sized(0.6F, 1.95F)
			.eyeHeight(1.62F)
			.clientTrackingRange(10)
			.build(NAKAMA_KEY));

	private ModEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(NAKAMA, NakamaEntity.createAttributes());
	}
}
