package dev.ainakama.entity;

import com.mojang.serialization.Codec;
import dev.ainakama.ModServices;
import dev.ainakama.core.Command;
import dev.ainakama.core.Memory;
import dev.ainakama.core.Trigger;
import dev.ainakama.task.TaskRunner;
import java.util.List;
import java.util.UUID;
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
import net.minecraft.world.damagesource.DamageSource;
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
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * AIの仲間（村人の見た目）。体（持ち物・移動・保存）を受け持ち、考える部分は {@link NakamaMind} に任せる。
 * AIを呼ぶのは「決まった言い方でも覚えた技でもない言葉で話しかけられたとき」と「プログラムで直せない失敗をしたとき」だけ。
 */
public class NakamaEntity extends PathfinderMob implements InventoryCarrier, TaskRunner.Listener {
	public static final String DEFAULT_NAME = "ミナ";
	public static final double WALK_SPEED = 0.6;
	private static final int INVENTORY_SIZE = 27;
	private static final int REGEN_INTERVAL = 80;
	private static final String THINKING_SUFFIX = "（考え中…）";

	private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
	private final TaskRunner runner = new TaskRunner();
	private final NakamaMind mind = new NakamaMind(this);
	private final TriggerWatch triggers = new TriggerWatch();
	private Memory memory = Memory.empty();
	private @Nullable BlockPos home;
	private @Nullable UUID lastSpeaker;
	private String baseName = DEFAULT_NAME;
	private String personality = "";
	private boolean thinking;

	public NakamaEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
		this.setPermanentlyInvulnerable(true);
		this.setCanPickUpLoot(false);
		// 手に持たせているのは持ち物の見た目用の写しなので、落とさない
		this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
		// 水を避けずに泳いで渡る
		this.setPathfindingMalus(PathType.WATER, 0.0F);
		this.getNavigation().setCanFloat(true);
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
		if (this.tickCount % TriggerWatch.POLL_INTERVAL == 0) {
			List<Trigger> fired = triggers.poll(this, level, runner.isIdle());
			if (!fired.isEmpty()) {
				mind.onTriggers(fired, level);
			}
		}
	}

	@Override
	public boolean removeWhenFarAway(final double distSqr) {
		return false;
	}

	// ---- 話しかけられたとき・実行側からの知らせ ----

	public void onPlayerMessage(ServerPlayer player, String text) {
		if (this.level() instanceof ServerLevel level) {
			this.lastSpeaker = player.getUUID();
			this.getLookControl().setLookAt(player);
			mind.onPlayerMessage(player, text, level);
		}
	}

	@Override
	public void onTaskFailed(Command command, String reason) {
		if (this.level() instanceof ServerLevel level) {
			mind.onTaskFailed(command, reason, level);
		}
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

	void setThinking(boolean value) {
		this.thinking = value;
		if (!this.isRemoved()) {
			refreshName();
		}
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
				say("今の作業: " + runner.describeCurrent() + " / " + mind.inventoryText());
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
	protected SoundEvent getHurtSound(final DamageSource source) {
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
		this.memory = new Memory(input.read("Notes", Codec.STRING.listOf()).orElse(List.of()), List.of());
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

	public Memory memory() {
		return memory;
	}

	void setMemory(Memory memory) {
		this.memory = memory;
	}

	String personality() {
		return personality.isBlank() ? ModServices.config().defaultPersonality : personality;
	}

	TaskRunner runner() {
		return runner;
	}

	public String currentJob() {
		return runner.describeCurrent();
	}

	public boolean isThinking() {
		return thinking;
	}

	public boolean isIdle() {
		return runner.isIdle();
	}

	/** テスト用：決まった計画を直接渡す。 */
	public void runPlan(List<Command> commands) {
		if (this.level() instanceof ServerLevel level) {
			runner.replace(commands, this, level);
		}
	}

	/** 腕を振る（掘る・置く・作るときの動き）。 */
	public void swingArm() {
		this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
	}
}
