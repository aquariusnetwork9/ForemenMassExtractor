package com.example.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MiningToolItem;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoMiner — AFK bulk single-block miner for anarchy servers (1.21.4).
 *
 * Design goals:
 *  - Never lock up. A flat finite-state machine drives everything; any unexpected
 *    condition transitions to a safe PAUSED state instead of stalling.
 *  - Mining itself is delegated to a PATCHED Baritone (mineLocalChunksOnly +
 *    mineLocalChunksThreshold) so the scanChunkRadius cache flood that overwhelms
 *    stock Baritone on abundant blocks (deepslate/stone) cannot happen.
 *  - When the inventory fills, run a storage cycle: place ender chest, pull an
 *    empty shulker, fill it, recover it, store it back, restock the pickaxe, then
 *    recover the ender chest and resume. Repeat until the ender chest has no empty
 *    shulkers left (DONE).
 *
 * IMPORTANT — this is a v0.1 scaffold. The block-breaking / mining-filter / Baritone
 * calls are the robust part. The container-slot interactions (marked // VERIFY) use
 * the documented vanilla + Meteor APIs but should be confirmed in-IDE against your
 * exact mappings, since slot indexing and screen-handler types are the most
 * version-sensitive surface.
 */
public class AutoMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStorage = settings.createGroup("Storage");
    private final SettingGroup sgTools = settings.createGroup("Tools");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // ---------------- Settings ----------------

    private final Setting<List<Block>> targetBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("target-blocks")
        .description("Blocks to mine. Everything else is left in place.")
        .defaultValue(List.of(Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE))
        .build()
    );

    private final Setting<Integer> mineLocalThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("local-chunks-threshold")
        .description("Pushed to your patched Baritone (mineLocalChunksThreshold). Suppresses far-chunk scanning once this many local targets are known.")
        .defaultValue(8)
        .min(1).sliderRange(1, 64)
        .build()
    );

    private final Setting<Integer> fullSlots = sgStorage.add(new IntSetting.Builder()
        .name("trigger-full-slots")
        .description("Start a storage cycle when this many inventory slots are occupied.")
        .defaultValue(32)
        .min(8).max(36).sliderRange(8, 36)
        .build()
    );

    private final Setting<Boolean> dropJunk = sgStorage.add(new BoolSetting.Builder()
        .name("drop-junk")
        .description("Throw out items that are not the target, a tool, food, an ender chest, or a shulker.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> restockDurability = sgTools.add(new IntSetting.Builder()
        .name("min-pickaxe-durability")
        .description("Swap to a fresh pickaxe from the ender chest below this remaining durability.")
        .defaultValue(60)
        .min(1).sliderRange(1, 200)
        .build()
    );

    private final Setting<Boolean> pauseOnHazard = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-hazard")
        .description("Pause if lava is adjacent or a non-friendly player is nearby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> hazardPlayerRange = sgSafety.add(new DoubleSetting.Builder()
        .name("player-pause-range")
        .description("Pause if another player comes within this many blocks.")
        .defaultValue(48)
        .min(0).sliderRange(0, 128)
        .visible(pauseOnHazard::get)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks between discrete container actions. Lower = faster, higher = safer vs lag/desync.")
        .defaultValue(4)
        .min(1).sliderRange(1, 20)
        .build()
    );

    // ---------------- State ----------------

    private enum State {
        MINING,            // Baritone is mining target blocks
        PLACE_ECHEST,      // place the ender chest
        ECHEST_TAKE,       // open echest, pull an empty shulker (and restock pickaxe)
        PLACE_SHULKER,     // place the empty shulker
        SHULKER_FILL,      // dump target stacks into the shulker
        BREAK_SHULKER,     // break the filled shulker to pick it up
        ECHEST_STORE,      // reopen echest, store the filled shulker
        BREAK_ECHEST,      // break the echest to pick it back up
        PAUSED,            // hazard / waiting
        DONE               // ender chest full of filled shulkers
    }

    private State state = State.MINING;
    private int timer = 0;                 // counts down between actions
    private BlockPos placedEchest = null;  // where we placed the ender chest this cycle
    private BlockPos placedShulker = null; // where we placed the shulker this cycle
    private IBaritone baritone;

    public AutoMiner() {
        super(AddonTemplate.CATEGORY, "auto-miner", "AFK bulk single-block miner with shulker/echest storage and tool restock.");
    }

    // ---------------- Lifecycle ----------------

    @Override
    public void onActivate() {
        baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        applyBaritoneSettings();
        state = State.MINING;
        timer = 0;
        placedEchest = null;
        placedShulker = null;
        startMining();
    }

    @Override
    public void onDeactivate() {
        stopMining();
    }

    /**
     * Push the patched-Baritone settings by name, so the addon compiles against the
     * stock baritone-api jar (which lacks these fields) but still drives the fork.
     */
    @SuppressWarnings("unchecked")
    private void applyBaritoneSettings() {
        Settings s = BaritoneAPI.getSettings();
        setBaritone(s, "minelocalchunksonly", true);
        setBaritone(s, "minelocalchunksthreshold", mineLocalThreshold.get());
        // Helpful stock knobs:
        setBaritone(s, "allowbreak", true);
        setBaritone(s, "legitmine", false); // we already have the target loaded; no need to pretend
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setBaritone(Settings s, String lowerName, Object value) {
        Settings.Setting setting = s.byLowerName.get(lowerName);
        if (setting != null) {
            setting.value = value;
        } else {
            warning("Baritone setting '%s' not found — is the patched Baritone installed?", lowerName);
        }
    }

    // ---------------- Main loop ----------------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (timer > 0) { timer--; return; }

        if (pauseOnHazard.get() && isHazard()) {
            if (state != State.PAUSED) {
                warning("Hazard detected — pausing.");
                stopMining();
                state = State.PAUSED;
            }
            timer = 20;
            return;
        }

        switch (state) {
            case MINING       -> tickMining();
            case PLACE_ECHEST -> tickPlaceEchest();
            case ECHEST_TAKE  -> tickEchestTake();
            case PLACE_SHULKER-> tickPlaceShulker();
            case SHULKER_FILL -> tickShulkerFill();
            case BREAK_SHULKER-> tickBreakShulker();
            case ECHEST_STORE -> tickEchestStore();
            case BREAK_ECHEST -> tickBreakEchest();
            case PAUSED       -> tickPaused();
            case DONE         -> { info("Ender chest full of filled shulkers. Done."); toggle(); }
        }
    }

    // ----- MINING -----

    private void tickMining() {
        if (dropJunk.get()) dropJunkItems();

        if (occupiedSlots() >= fullSlots.get()) {
            stopMining();
            // ensure we actually have something to store + the gear to do it
            if (countItem(Items.ENDER_CHEST) == 0) { warning("No ender chest in inventory."); state = State.PAUSED; timer = 40; return; }
            state = State.PLACE_ECHEST;
            return;
        }

        if (!baritone.getMineProcess().isActive()) startMining(); // restart if it ever idles
    }

    // ----- PLACE_ECHEST -----

    private void tickPlaceEchest() {
        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) { warning("No ender chest."); state = State.PAUSED; timer = 40; return; }

        BlockPos pos = findPlacementSpot();
        if (pos == null) { warning("No spot to place ender chest."); state = State.PAUSED; timer = 40; return; }

        InvUtils.swap(echest.slot(), true);
        if (placeBlock(pos)) {
            placedEchest = pos;
            state = State.ECHEST_TAKE;
            timer = actionDelay.get();
        } else {
            timer = actionDelay.get();
        }
        InvUtils.swapBack();
    }

    // ----- ECHEST_TAKE: open, restock pickaxe, pull one empty shulker -----

    private void tickEchestTake() {
        if (!isContainerOpen()) {
            openBlock(placedEchest);
            timer = actionDelay.get();
            return;
        }

        // VERIFY: ender chest is a GenericContainerScreenHandler with 27 container slots.
        ScreenHandler h = mc.player.currentScreenHandler;

        // 1) Restock pickaxe if the current one is low/missing.
        restockPickaxe(h);

        // 2) Pull an empty shulker out of the echest into our inventory.
        int shulkerSlot = findContainerSlot(h, this::isEmptyShulker);
        if (shulkerSlot >= 0) {
            quickMove(h, shulkerSlot);
            closeScreen();
            state = State.PLACE_SHULKER;
            timer = actionDelay.get();
            return;
        }

        // No empty shulkers left — but if we are still holding filled shulkers to store,
        // store them first; otherwise we are finished.
        if (countShulkers(this::isFilledShulkerInInv) > 0) {
            // unusual ordering safety-net; go store what we have
            closeScreen();
            state = State.BREAK_SHULKER; // nothing to break, will fall through to store
            timer = actionDelay.get();
            return;
        }

        closeScreen();
        state = State.DONE;
    }

    // ----- PLACE_SHULKER -----

    private void tickPlaceShulker() {
        FindItemResult shulker = InvUtils.find(this::isEmptyShulkerStack);
        if (!shulker.found()) { warning("Lost the empty shulker."); state = State.PAUSED; timer = 40; return; }

        BlockPos pos = findPlacementSpot();
        if (pos == null) { state = State.PAUSED; timer = 40; return; }

        InvUtils.swap(shulker.slot(), true);
        if (placeBlock(pos)) {
            placedShulker = pos;
            state = State.SHULKER_FILL;
            timer = actionDelay.get();
        } else {
            timer = actionDelay.get();
        }
        InvUtils.swapBack();
    }

    // ----- SHULKER_FILL: dump every target stack into the shulker -----

    private void tickShulkerFill() {
        if (!isContainerOpen()) {
            openBlock(placedShulker);
            timer = actionDelay.get();
            return;
        }

        ScreenHandler h = mc.player.currentScreenHandler;
        // VERIFY: shulker = ShulkerBoxScreenHandler, 27 container slots (0..26),
        // player slots follow (27..62). We shift-move target stacks from the PLAYER
        // half into the container half.
        int playerSlot = findPlayerSlotInContainer(h, this::isTargetStack);
        if (playerSlot >= 0) {
            quickMove(h, playerSlot);
            timer = actionDelay.get();
            return; // one stack per action tick; come back next tick
        }

        // No more target stacks (or shulker full) — close and recover it.
        closeScreen();
        state = State.BREAK_SHULKER;
        timer = actionDelay.get();
    }

    // ----- BREAK_SHULKER -----

    private void tickBreakShulker() {
        if (placedShulker == null) { state = State.ECHEST_STORE; return; }
        if (mc.world.getBlockState(placedShulker).getBlock() instanceof ShulkerBoxBlock) {
            // break it (silk not required — breaking a shulker always drops it filled)
            BlockUtils.breakBlock(placedShulker, true);
            timer = actionDelay.get();
            return;
        }
        // gone -> picked up
        placedShulker = null;
        state = State.ECHEST_STORE;
        timer = actionDelay.get();
    }

    // ----- ECHEST_STORE: reopen echest, push filled shulker(s) in -----

    private void tickEchestStore() {
        if (!isContainerOpen()) {
            openBlock(placedEchest);
            timer = actionDelay.get();
            return;
        }

        ScreenHandler h = mc.player.currentScreenHandler;
        int playerSlot = findPlayerSlotInContainer(h, this::isFilledShulkerStack);
        if (playerSlot >= 0) {
            quickMove(h, playerSlot);
            timer = actionDelay.get();
            return;
        }

        closeScreen();
        // Decide: more inventory to clear? If we still have target stacks, loop another
        // shulker; else recover the echest and go back to mining.
        if (countShulkers(this::isEmptyShulkerInInv) > 0 && hasTargetStacks()) {
            state = State.PLACE_SHULKER;
            timer = actionDelay.get();
        } else {
            state = State.BREAK_ECHEST;
            timer = actionDelay.get();
        }
    }

    // ----- BREAK_ECHEST -----

    private void tickBreakEchest() {
        if (placedEchest == null) { resumeMining(); return; }
        if (mc.world.getBlockState(placedEchest).getBlock() == Blocks.ENDER_CHEST) {
            BlockUtils.breakBlock(placedEchest, true);
            timer = actionDelay.get();
            return;
        }
        placedEchest = null;
        resumeMining();
    }

    private void resumeMining() {
        state = State.MINING;
        startMining();
    }

    // ----- PAUSED -----

    private void tickPaused() {
        if (!isHazard()) {
            info("Hazard clear — resuming.");
            // resume from a safe point: re-evaluate from mining
            state = State.MINING;
            startMining();
        } else {
            timer = 20;
        }
    }

    // ---------------- Baritone control ----------------

    private void startMining() {
        Block[] blocks = targetBlocks.get().toArray(new Block[0]);
        if (blocks.length == 0) { warning("No target blocks set."); toggle(); return; }
        baritone.getMineProcess().mine(0, blocks); // quantity 0 = infinite
    }

    private void stopMining() {
        if (baritone != null) {
            baritone.getMineProcess().cancel();
            baritone.getPathingBehavior().cancelEverything();
        }
    }

    // ---------------- Inventory helpers ----------------

    private int occupiedSlots() {
        int n = 0;
        for (int i = 0; i < 36; i++) if (!mc.player.getInventory().getStack(i).isEmpty()) n++;
        return n;
    }

    private boolean hasTargetStacks() {
        for (int i = 0; i < 36; i++) if (isTargetStack(mc.player.getInventory().getStack(i))) return true;
        return false;
    }

    private boolean isTargetStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        return targetBlocks.get().contains(bi.getBlock());
    }

    private int countItem(net.minecraft.item.Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).getItem() == item) n++;
        return n;
    }

    private boolean isEmptyShulkerStack(ItemStack stack) { return isShulker(stack) && !hasContents(stack); }
    private boolean isFilledShulkerStack(ItemStack stack) { return isShulker(stack) && hasContents(stack); }

    private boolean isShulker(ItemStack stack) {
        return !stack.isEmpty()
            && stack.getItem() instanceof BlockItem bi
            && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /** VERIFY: container-contents detection for a shulker item in 1.21.4 uses the
     *  CONTAINER component. If your mappings differ, adjust here only. */
    private boolean hasContents(ItemStack stack) {
        var c = stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
        return c != null && c.stream().findAny().isPresent();
    }

    private int countShulkers(java.util.function.Predicate<ItemStack> p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.test(mc.player.getInventory().getStack(i))) n++;
        return n;
    }
    private boolean isEmptyShulkerInInv(ItemStack s)  { return isEmptyShulkerStack(s); }
    private boolean isFilledShulkerInInv(ItemStack s) { return isFilledShulkerStack(s); }
    private boolean isEmptyShulker(ItemStack s)        { return isEmptyShulkerStack(s); }

    private void dropJunkItems() {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            if (isTargetStack(s)) continue;
            if (isShulker(s)) continue;
            if (s.getItem() == Items.ENDER_CHEST) continue;
            if (s.getItem() instanceof MiningToolItem) continue;
            if (s.contains(net.minecraft.component.DataComponentTypes.FOOD)) continue;
            // throw it
            InvUtils.drop().slot(i); // VERIFY: Meteor InvUtils.drop().slot(i) throws the stack
        }
    }

    // ---------------- Tool restock ----------------

    private void restockPickaxe(ScreenHandler h) {
        ItemStack held = mc.player.getMainHandStack();
        boolean lowOrMissing = !(held.getItem() instanceof MiningToolItem)
            || (held.getMaxDamage() - held.getDamage()) < restockDurability.get();
        if (!lowOrMissing) return;

        int slot = findContainerSlot(h, s ->
            s.getItem() instanceof MiningToolItem
                && (s.getMaxDamage() - s.getDamage()) >= restockDurability.get());
        if (slot >= 0) {
            quickMove(h, slot); // pull fresh pickaxe into inventory
            info("Restocked pickaxe.");
        } else {
            warning("No fresh pickaxe in ender chest.");
        }
    }

    // ---------------- Container interaction (VERIFY block) ----------------
    // These are the most version-sensitive parts. They use vanilla ScreenHandler +
    // interactionManager APIs that are stable in shape but worth confirming in-IDE.

    private boolean isContainerOpen() {
        return mc.player != null
            && mc.player.currentScreenHandler != mc.player.playerScreenHandler;
    }

    private void openBlock(BlockPos pos) {
        if (pos == null) return;
        Vec3d hit = Vec3d.ofCenter(pos);
        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
            BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, pos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        });
    }

    private void closeScreen() {
        if (isContainerOpen()) mc.player.closeHandledScreen();
    }

    /** Find a container-half slot (the chest/shulker, not the player rows) matching p. */
    private int findContainerSlot(ScreenHandler h, java.util.function.Predicate<ItemStack> p) {
        int containerSlots = h.slots.size() - 36; // player inv is always the trailing 36 slots
        for (int i = 0; i < containerSlots; i++) {
            Slot slot = h.slots.get(i);
            if (p.test(slot.getStack())) return i;
        }
        return -1;
    }

    /** Find a player-half slot (within an open container) matching p. */
    private int findPlayerSlotInContainer(ScreenHandler h, java.util.function.Predicate<ItemStack> p) {
        int total = h.slots.size();
        for (int i = total - 36; i < total; i++) {
            Slot slot = h.slots.get(i);
            if (p.test(slot.getStack())) return i;
        }
        return -1;
    }

    /** Shift-click a slot (moves the stack to the opposite inventory half). */
    private void quickMove(ScreenHandler h, int slotIndex) {
        mc.interactionManager.clickSlot(h.syncId, slotIndex, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    // ---------------- Placement + safety ----------------

    /** Find a spot adjacent to the player with solid support and air above, to place on. */
    private BlockPos findPlacementSpot() {
        BlockPos feet = mc.player.getBlockPos();
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            BlockPos target = feet.offset(dir);
            BlockPos below = target.down();
            if (mc.world.getBlockState(target).isReplaceable()
                && !mc.world.getBlockState(below).isReplaceable()) {
                return target;
            }
        }
        return null;
    }

    private boolean placeBlock(BlockPos pos) {
        // BlockUtils.place handles rotation + the place packet. Returns success.
        FindItemResult held = InvUtils.find(stack -> stack == mc.player.getMainHandStack() && !stack.isEmpty());
        return BlockUtils.place(pos, InvUtils.findInHotbar(mc.player.getMainHandStack().getItem()), true, 0, true);
    }

    private boolean isHazard() {
        // lava adjacency
        BlockPos p = mc.player.getBlockPos();
        for (Direction d : Direction.values()) {
            if (mc.world.getBlockState(p.offset(d)).getBlock() == Blocks.LAVA) return true;
        }
        // nearby non-self player
        if (hazardPlayerRange.get() > 0) {
            for (var pe : mc.world.getPlayers()) {
                if (pe == mc.player) continue;
                if (pe.distanceTo(mc.player) <= hazardPlayerRange.get()) return true;
            }
        }
        return false;
    }

    @Override
    public String getInfoString() {
        return state.name();
    }
}
