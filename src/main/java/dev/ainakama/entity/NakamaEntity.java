package dev.ainakama.entity;

import com.mojang.serialization.Codec;
import dev.ainakama.AiNakama;
import dev.ainakama.ModServices;
import dev.ainakama.ai.AiService;
import dev.ainakama.core.CallBudget;
import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.core.Memory;
import dev.ainakama.core.Plan;
import dev.ainakama.core.PromptBuilder;
import dev.ainakama.core.QuickCommands;
import dev.ainakama.core.Status;
import dev.ainakama.task.TaskRunner;
import dev.ainakama.world.Inv;
import dev.ainakama.world.StatusCollector;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * AIの仲間（村人の見た目）。
 * プレイヤーの言葉を受け取り、決まった言い方ならそのまま、それ以外はAIに計画を聞いて、TaskRunner で実行する。
 * AIを呼ぶのは「話しかけられたとき」と「プログラムで直せない失敗をしたとき」だけ。
 */
public class NakamaEntity extends PathfinderMob implements InventoryCarrier, TaskRunner.Listener {
	public static final String DEFAULT_NAME = "ミナ";
	public static final double WALK_SPEED = 0.6;
	private static final int INVENTORY_SIZE = 27;
	private static final int REGEN_INTERVAL = 80;
	/** 1回の話しかけのあと、失敗してAIに立て直しを頼める回数。これを超えたらプレイヤーに任せる。 */
	private static final int MAX_AUTO_REPLANS = 2;
	private static final String THINKING_SUFFIX = "（考え中…）";

	private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
	private final TaskRunner runner = new TaskRunner();
	private Memory memory = Memory.empty();
	private @Nullable BlockPos home;
	private @Nullable UUID lastSpeaker;
	private String baseName = DEFAULT_NAME;
	private String personality = "";
	private boolean thinking;
	/** 新しい指示のたびに増える番号。AIの返事が届いたとき、もっと新しい指示が出ていれば古い返事は捨てる。 */
	private int generation;
	private int autoReplans;

	public NakamaEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
		this.setPermanentlyInvulnerable(true);
		this.setCanPickUpLoot(false);
		// 手に持たせているのは持ち物の見た目用の写しなので、落とさない
		this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 40.0)
			.add(Attributes.MOVEMENT_SPEED, 0.5)
			.add(Attributes.FOLLOW_RANGE, 64.0);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
	}

	/** 出したときの初期設定。 */
	public void setup(String name, @Nullable BlockPos home) {
		this.baseName = name;
		this.home = home;
		this.personality = ModServices.config().defaultPersonality;
		refreshName();
	}

	// ---- 毎tickの処理 ----

	@Override
	protected void customServerAiStep(final ServerLevel level) {
		super.customServerAiStep(level);
		runner.tick(this, level, this);
		if (this.tickCount % REGEN_INTERVAL == 0 && this.getHealth() < this.getMaxHealth()) {
			this.heal(1.0F);
		}
	}

	@Override
	public boolean removeWhenFarAway(final double distSqr) {
		return false;
	}

	// ---- 話しかけられたとき ----

	public void onPlayerMessage(ServerPlayer player, String text) {
		if (!(this.level() instanceof ServerLevel level)) {
			return;
		}
		this.lastSpeaker = player.getUUID();
		this.autoReplans = 0;
		this.getLookControl().setLookAt(player);

		var quick = QuickCommands.match(text, Inv::itemExists);
		if (quick.isPresent()) {
			generation++;
			quick.get().special().ifPresent(s -> handleSpecial(s, player));
			quick.get().plan().ifPresent(p -> applyPlan(p, text, level));
			return;
		}
		if (thinking) {
			say("ちょっと待ってね、いま考え中…");
			return;
		}
		Status status = StatusCollector.collect(this, level, runner.describeCurrent());
		String user = PromptBuilder.userForMessage(baseName, status, memory, text);
		generation++;
		askAi("message", user, text, level);
	}

	private void handleSpecial(QuickCommands.Special special, ServerPlayer player) {
		switch (special) {
			case SET_HOME -> {
				this.home = player.blockPosition();
				say("ここを拠点にするね！");
				remember("拠点は " + home.getX() + " " + home.getY() + " " + home.getZ());
			}
			case SHOW_INVENTORY -> say(inventoryText());
		}
	}

	private String inventoryText() {
		var counts = Inv.counts(inventory);
		if (counts.isEmpty()) {
			return "何も持ってないよ。";
		}
		return "持ち物: " + counts.entrySet().stream()
			.map(e -> Items.label(e.getKey()) + "×" + e.getValue())
			.collect(Collectors.joining(", "));
	}

	// ---- AIに聞く ----

	private void askAi(String purpose, String user, @Nullable String playerText, ServerLevel level) {
		AiService ai = ModServices.ai();
		thinking = true;
		refreshName();
		String system = PromptBuilder.system(baseName, personality.isBlank() ? ModServices.config().defaultPersonality : personality);
		int askedAt = generation;
		ai.ask(this.getUUID(), purpose, system, user).whenComplete((outcome, err) ->
			level.getServer().execute(() -> {
				thinking = false;
				if (this.isRemoved()) {
					return;
				}
				refreshName();
				if (askedAt != generation) {
					// 考えている間に「止まって」など新しい指示が出たので、古い返事は使わない
					return;
				}
				if (err != null) {
					AiNakama.LOGGER.warn("AIの返事を受け取れませんでした", err);
					say("うまく考えられなかった…。");
					return;
				}
				handleOutcome(outcome, playerText, level);
			}));
	}

	private void handleOutcome(AiService.Outcome outcome, @Nullable String playerText, ServerLevel level) {
		switch (outcome) {
			case AiService.Outcome.Success s -> {
				if (s.plan().commands().isEmpty() && s.plan().say().isBlank()) {
					say("ごめん、よくわからなかった…。言い方を変えてみて？");
					return;
				}
				applyPlan(s.plan(), playerText, level);
			}
			case AiService.Outcome.Refused r -> say(r.decision() == CallBudget.Decision.DAILY_LIMIT
				? "今日はもう考え疲れた…。「ついてきて」「木を10本集めて」みたいな決まった言い方なら動けるよ。"
				: "ちょっとだけ待ってね。");
			case AiService.Outcome.Failed f -> say(f.reason());
		}
	}

	private void applyPlan(Plan plan, @Nullable String playerText, ServerLevel level) {
		if (!plan.say().isBlank()) {
			say(plan.say());
		}
		if (plan.memo() != null) {
			remember(plan.memo());
		}
		if (playerText != null) {
			memory = memory.withExchange(playerText, plan.say());
		}
		if (!plan.commands().isEmpty()) {
			runner.replace(plan.commands(), this, level);
		}
	}

	// ---- 実行側からの知らせ ----

	@Override
	public void onTaskFailed(Command command, String reason) {
		if (!(this.level() instanceof ServerLevel level)) {
			return;
		}
		if (autoReplans >= MAX_AUTO_REPLANS || thinking) {
			say("うまくいかない…。" + reason + "。どうしたらいいか教えて？");
			return;
		}
		autoReplans++;
		say(reason + "…。どうするか考えるね。");
		Status status = StatusCollector.collect(this, level, "なし");
		askAi("failure", PromptBuilder.userForFailure(baseName, status, memory, command.describe(), reason), null, level);
	}

	@Override
	public void onAllDone(boolean didWork) {
		if (didWork) {
			say("終わったよ！");
		}
	}

	// ---- 話す・覚える ----

	public void say(String text) {
		if (this.level() instanceof ServerLevel level && !text.isBlank()) {
			Component msg = Component.literal("<" + baseName + "> ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE));
			level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
		}
	}

	public void remember(String note) {
		memory = memory.withNote(note);
	}

	private void refreshName() {
		this.setCustomName(Component.literal(thinking ? baseName + THINKING_SUFFIX : baseName));
		this.setCustomNameVisible(true);
	}

	/** 名札で名前を変えられたときにも呼びかけに反応できるよう、表示名から名前を読み直す。 */
	public String baseName() {
		Component custom = this.getCustomName();
		if (custom != null && !thinking) {
			String shown = custom.getString();
			if (!shown.isBlank() && !shown.equals(baseName)) {
				baseName = shown;
			}
		}
		return baseName;
	}

	// ---- 右クリック ----

	@Override
	protected InteractionResult mobInteract(final Player player, final InteractionHand hand) {
		if (hand == InteractionHand.MAIN_HAND && player.getItemInHand(hand).isEmpty()) {
			if (!this.level().isClientSide()) {
				say("今の作業: " + runner.describeCurrent() + " / " + inventoryText());
			}
			return InteractionResult.SUCCESS;
		}
		return super.mobInteract(player, hand);
	}

	// ---- 音 ----

	@Override
	protected SoundEvent getAmbientSound() {
		return thinking ? SoundEvents.VILLAGER_AMBIENT : null;
	}

	@Override
	protected SoundEvent getHurtSound(final net.minecraft.world.damagesource.DamageSource source) {
		return SoundEvents.VILLAGER_HURT;
	}

	// ---- 保存 ----

	@Override
	protected void addAdditionalSaveData(final ValueOutput output) {
		super.addAdditionalSaveData(output);
		this.writeInventoryToTag(output);
		output.storeNullable("Home", BlockPos.CODEC, home);
		output.putString("BaseName", baseName);
		output.putString("Personality", personality);
		output.store("Notes", Codec.STRING.listOf(), memory.notes());
	}

	@Override
	protected void readAdditionalSaveData(final ValueInput input) {
		super.readAdditionalSaveData(input);
		this.readInventoryFromTag(input);
		this.home = input.read("Home", BlockPos.CODEC).orElse(null);
		this.baseName = input.getStringOr("BaseName", DEFAULT_NAME);
		this.personality = input.getStringOr("Personality", "");
		this.memory = new Memory(input.read("Notes", Codec.STRING.listOf()).orElse(java.util.List.of()), java.util.List.of());
		refreshName();
	}

	// ---- ほかから使うもの ----

	@Override
	public SimpleContainer getInventory() {
		return inventory;
	}

	public @Nullable BlockPos home() {
		return home;
	}

	public void setHome(BlockPos pos) {
		this.home = pos;
	}

	public @Nullable Player lastSpeaker(ServerLevel level) {
		return lastSpeaker == null ? null : level.getPlayerByUUID(lastSpeaker);
	}

	public String currentJob() {
		return runner.describeCurrent();
	}

	public boolean isThinking() {
		return thinking;
	}

	/** テスト用：決まった計画を直接渡す。 */
	public void runPlan(java.util.List<Command> commands) {
		if (this.level() instanceof ServerLevel level) {
			runner.replace(commands, this, level);
		}
	}

	/** 腕を振る（掘る・置く・作るときの動き）。 */
	public void swingArm() {
		this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
	}

	public boolean isIdle() {
		return runner.isIdle();
	}
}
