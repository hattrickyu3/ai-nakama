package dev.ainakama;

import dev.ainakama.command.ChatRouter;
import dev.ainakama.command.NakamaCommands;
import dev.ainakama.entity.ModEntities;
import net.fabricmc.api.ModInitializer;
import dev.ainakama.world.RecipeImporter;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiNakama implements ModInitializer {
	public static final String MOD_ID = "ainakama";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModEntities.register();
		ModServices.reload();
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> NakamaCommands.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(RecipeImporter::importFrom);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> RecipeImporter.importFrom(server));
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> ChatRouter.onChat(sender, message.signedContent()));
		LOGGER.info("AI Nakama loaded ({})", ModServices.ai().describeClient());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
