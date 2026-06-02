package com.autominer.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import com.autominer.AutoMinerAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * AutoMiner — AFK bulk single-block miner for anarchy servers (Minecraft 1.21.4).
 *
 * Design goals:
 *  - Never lock up. A flat finite-state machine drives everything; any unexpected
 *    condition transitions to a safe PAUSED state instead of stalling.
 *  - Mining is STOCK Baritone's BuilderProcess.clearArea quarry, run one chunk box at a
 *    time and walked outward in a spiral. clearArea is box-confined and breaks the nearest
 *    block first, so it can't wander to a distant target and skips the per-tick visibility
 *    raytrace that overwhelms MineProcess on abundant blocks (deepslate/stone). No patched
 *    Baritone fork is required — any Baritone (incl. Meteor's bundled one) works.
 *  - When the inventory fills, run a storage cycle: place ender chest, pull an
 *    empty shulker, fill it, recover it, store it back, restock the pickaxe, then
 *    recover the ender chest and resume.
 *
 * DESYNC SAFETY: every packet-generating action (drop, hotbar swap, block place,
 * container open/close, slot shift-move, block break) happens on its OWN tick and is
 * followed by an {@code action-delay} wait. Nothing fires multiple packets in a single
 * tick. This is what keeps the bot from outrunning the server on a high-ping anarchy
 * connection (2b2t etc.), where a burst of same-tick packets desyncs client and server.
 */
public class AutoMiner extends Module {
    /** SLF4J logger — debug output lands in the game's latest.log under "(AutoMiner)". */
    private static final Logger LOG = LoggerFactory.getLogger("AutoMiner");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStorage = settings.createGroup("Storage");
    private final SettingGroup sgTools = settings.createGroup("Tools");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // ---------------- Settings ----------------

    private final Setting<List<Block>> mineBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("target-blocks")
        .description("The block you're quarrying for — used to pick the restock TOOL (deepslate/stone → pickaxe, sand/gravel → shovel) and for the 'targets left in box' debug count. NOTE: the ClearArea quarry breaks EVERY block in the chunk box regardless; what you actually KEEP is set in 'keep-items', and everything else is dropped as junk. So this is just the tool hint, not a mining filter.")
        .defaultValue(List.of(Blocks.DEEPSLATE))
        .build()
    );

    private final Setting<List<Item>> keepItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("keep-items")
        .description("The dropped items to actually KEEP, store in shulkers, and count toward 'inventory full' — i.e. what mining yields. Mining deepslate without Silk Touch drops COBBLED deepslate, so that's the default; deepslate is also kept in case you mine with Silk Touch. Anything not in this list (dirt/stone tunnelled through) is treated as junk.")
        .defaultValue(List.of(Items.COBBLED_DEEPSLATE, Items.DEEPSLATE))
        .build()
    );

    private final Setting<Integer> minYLevel = sgGeneral.add(new IntSetting.Builder()
        .name("min-y-level")
        .description("Absolute floor. Baritone won't mine below this Y, and ender chests / shulkers are never placed below it — so the bot can't tunnel into the bedrock layer and wedge. Keep it above bedrock (≈ -59 in 1.21.4). Re-enable the module after changing this so it's re-pushed to Baritone.")
        .defaultValue(-59)
        .min(-64).max(320)
        .sliderRange(-64, 120)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks to wait after EVERY individual action (drop, hotbar swap, block place, open/close a container, one slot shift-move, block break). Each is on its own tick — nothing bursts. Higher = slower but far safer against client-server desync on high-ping/laggy servers (2b2t). 20 ticks = 1s.")
        .defaultValue(15)
        .min(1).sliderRange(1, 60)
        .build()
    );

    private final Setting<Integer> delayJitter = sgGeneral.add(new IntSetting.Builder()
        .name("delay-jitter")
        .description("Randomly ADD up to this percent of action-delay to each wait, so actions aren't perfectly metronomic. Only ever adds time (never faster than action-delay), so it can't cause desync — purely to avoid timing-regularity flags. 0 = off.")
        .defaultValue(40)
        .min(0).sliderRange(0, 150)
        .build()
    );

    private final Setting<Boolean> requireFullStacks = sgStorage.add(new BoolSetting.Builder()
        .name("require-full-stacks")
        .description("Only begin a storage cycle once the main inventory is COMPLETELY packed — every target stack at 64 and no empty slots left to start a new one — so shulkers are always filled with whole stacks. Reserved items (ender chest, spare shulkers, tools, food) occupying a slot don't block it: the trigger is simply 'no room for one more target block'. Turn OFF to fall back to the looser 'free-slots-before-store' (which can fire with partial stacks).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> freeSlotsBeforeStore = sgStorage.add(new IntSetting.Builder()
        .name("free-slots-before-store")
        .description("Used only when 'require-full-stacks' is OFF. Start a storage cycle when at most this many EMPTY main-inventory slots remain (the 27 slots above the hotbar). Counts free space, NOT occupancy — so spare ender chests, tools, and food no longer trigger an early cycle. The hotbar is excluded entirely (it's kept clear of target and reserved for staging). 1 = leave a one-slot margin; 0 = fill the main inventory completely before storing.")
        .defaultValue(1)
        .min(0).max(26).sliderRange(0, 10)
        .visible(() -> !requireFullStacks.get())
        .build()
    );

    private final Setting<Boolean> dropJunk = sgStorage.add(new BoolSetting.Builder()
        .name("drop-junk")
        .description("Throw out items that are not the target, a tool, a (good) food, an ender chest, or a shulker. One item per action-delay so it can't burst.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> dropBadFood = sgStorage.add(new BoolSetting.Builder()
        .name("drop-bad-food")
        .description("Also treat 'risky' foods as junk: rotten flesh, spider eye, poisonous/raw potato & chicken, pufferfish, chorus fruit (harmful-effect or teleport foods) and all stews (mushroom/rabbit/beetroot/suspicious — any non-stackable food). Normal foods (bread, cooked meat, carrots, golden apples…) are still kept for AutoEat. Only applies when 'drop-junk' is on.")
        .defaultValue(true)
        .visible(dropJunk::get)
        .build()
    );

    private final Setting<Boolean> autoDisconnect = sgStorage.add(new BoolSetting.Builder()
        .name("auto-disconnect")
        .description("When storage is exhausted (no empty shulkers left in the ender chest to fill, or the ender chest is itself full), also disconnect from the server. The module always stops in this case; this just additionally logs you out — handy for an AFK run so you leave cleanly once everything's packed. Off = just stop and stay connected.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fillDelay = sgStorage.add(new IntSetting.Builder()
        .name("fill-delay")
        .description("Ticks between each stack moved into a shulker, and each filled shulker stored. Container slot transfers are transactional and far lower-risk than world block/movement actions, so this runs faster than action-delay to speed up the dump. Still one move per tick — never bursts. Jitter is applied on top.")
        .defaultValue(4)
        .min(1).sliderRange(1, 20)
        .build()
    );

    private final Setting<ToolType> toolType = sgTools.add(new EnumSetting.Builder<ToolType>()
        .name("restock-tool")
        .description("Which tool to pull from the ender chest when yours runs low. Auto derives it from your target blocks (sand/gravel → shovel, deepslate/stone → pickaxe, logs → axe).")
        .defaultValue(ToolType.Auto)
        .build()
    );

    private final Setting<Integer> restockDurability = sgTools.add(new IntSetting.Builder()
        .name("min-tool-durability")
        .description("Swap to a fresh tool from the ender chest below this remaining durability.")
        .defaultValue(60)
        .min(1).sliderRange(1, 200)
        .build()
    );

    private final Setting<Boolean> reserveSilk = sgTools.add(new BoolSetting.Builder()
        .name("reserve-silk-touch")
        .description("Never restock a Silk Touch tool, so your Silk Touch pickaxe stays reserved for ender chests (let Meteor AutoTool's 'silk-touch-for-ender-chest' use it there). Keep ON if you mine with Fortune/normal; turn OFF if you actually mine WITH Silk Touch.")
        .defaultValue(true)
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

    private final Setting<Integer> pauseRetryTicks = sgSafety.add(new IntSetting.Builder()
        .name("pause-retry-ticks")
        .description("How long to wait before re-checking after a hazard or a missing resource pause.")
        .defaultValue(40)
        .min(5).sliderRange(5, 200)
        .build()
    );

    private final Setting<Boolean> debugLog = sgSafety.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Write a timestamped trace of what the miner is doing (state changes, chunk advances with remaining-target counts, storage steps, pauses, pushed Baritone settings) to the game's latest.log under '(AutoMiner)'. Off for normal use; flip on before a test run to capture a shareable log, then off. Interleaves with Baritone's own pathing lines in the same file.")
        .defaultValue(false)
        .build()
    );

    // ---------------- State ----------------

    private enum State {
        MINING,            // Baritone is mining target blocks
        CLEAR_AREA,        // mine a small pocket so the echest + shulker both have room
        PLACE_ECHEST,      // place the ender chest (multi-step)
        ECHEST_TAKE,       // open echest, restock pickaxe, pull an empty shulker (multi-step)
        PLACE_SHULKER,     // place the empty shulker (multi-step)
        SHULKER_FILL,      // open shulker, dump target stacks one per tick (multi-step)
        BREAK_SHULKER,     // break the filled shulker to pick it up
        ECHEST_STORE,      // reopen echest, store the filled shulker one per tick (multi-step)
        BREAK_ECHEST,      // break the echest to pick it back up
        PAUSED,            // hazard / waiting
        DONE               // ender chest full of filled shulkers
    }

    /** Result of one sub-step of a placement sequence. */
    private enum Place { BUSY, DONE, FAILED }

    /** Which tool the restock pulls. Auto = derive from the target blocks' mineable tag. */
    public enum ToolType { Auto, Pickaxe, Shovel, Axe, Hoe }

    private State state = State.MINING;
    private int step = 0;                   // sub-step within the current state
    private int attempts = 0;               // retry/timeout counter within a sub-step
    private int timer = 0;                  // counts down between actions
    private BlockPos placedEchest = null;   // where we placed the ender chest this cycle
    private BlockPos placedShulker = null;  // where we placed the shulker this cycle
    private BlockPos pendingPlace = null;   // chosen spot mid-placement (between sub-steps)
    private String doneReason = "storage full"; // why the DONE state was entered (for the message/action)
    private IBaritone baritone;
    private final java.util.Random random = new java.util.Random();
    private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

    // Outward chunk spiral (chunk coords). startC* is the spiral centre captured at activation;
    // the spiral stepper walks outward from it, and curC* is the chunk currently being cleared.
    private int startCX, startCZ, curCX, curCZ;
    private int spX, spZ, spDir, spSegLen, spSegLeft, spSegDone; // outward-square spiral stepper
    private int quarryTopY = 0; // quarry ceiling (player's start Y + 1); box top for clearArea

    // ClearArea engine state (stock BuilderProcess quarry).
    private boolean clearAreaStarted = false; // have we issued clearArea for the current chunk yet
    private int clearAreaStallTicks = 0;      // ticks the bot hasn't moved while the builder is active
    private BlockPos lastClearPos = null;     // last player pos, for stall detection
    private static final int CLEARAREA_STALL_TICKS = 20 * 30; // 30s without moving = stuck -> skip box

    public AutoMiner() {
        super(AutoMinerAddon.CATEGORY, "auto-miner", "AFK bulk single-block miner with shulker/echest storage and tool restock.");
    }

    // ---------------- Lifecycle ----------------

    @Override
    public void onActivate() {
        dbg("=== activate === mineBlocks=%d keepItems=%d minY=%d actionDelay=%d fillDelay=%d",
            mineBlocks.get().size(), keepItems.get().size(), minYLevel.get(), actionDelay.get(), fillDelay.get());
        baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        applyBaritoneSettings();
        go(State.MINING);
        timer = 0;
        placedEchest = null;
        placedShulker = null;
        pendingPlace = null;
        resetArea();   // capture the start chunk + seed the outward spiral
        startMining();
    }

    @Override
    public void onDeactivate() {
        dbg("=== deactivate ===");
        stopMining();
    }

    /** Move to a new state, resetting the per-state sub-step + retry counters. */
    private void go(State s) {
        if (s != state) dbg("state %s -> %s", state, s);
        state = s;
        step = 0;
        attempts = 0;
    }

    /**
     * The wait after one action: {@code action-delay} plus a random 0..jitter% extra.
     * Always &ge; action-delay, so jitter can never make the bot faster (no desync risk);
     * it only breaks up perfect timing regularity.
     */
    private int nextDelay() { return withJitter(actionDelay.get()); }

    /** Faster cadence for in-container slot transfers (shulker fill / echest store). */
    private int fillMoveDelay() { return withJitter(fillDelay.get()); }

    private int withJitter(int base) {
        int pct = delayJitter.get();
        if (pct <= 0) return base;
        int maxExtra = Math.round(base * (pct / 100f));
        return maxExtra > 0 ? base + random.nextInt(maxExtra + 1) : base;
    }

    /** Debug trace to latest.log, only when 'debug-logging' is on. */
    private void dbg(String fmt, Object... args) {
        if (debugLog.get()) LOG.info(args.length == 0 ? fmt : String.format(fmt, args));
    }

    /**
     * Count the mine-target blocks still in a chunk box over the quarry's Y band, and how many are
     * exposed (touching air) — for diagnostics. Lets the log show whether a chunk was abandoned
     * with reachable targets still in it (premature advance) vs. genuinely cleared. Capped for cost.
     */
    private int[] countMineBlocksInChunk(int cx, int cz) {
        int bx = cx << 4, bz = cz << 4;
        int floor = minYLevel.get();
        int ceil = quarryTopY;
        int total = 0, exposed = 0;
        List<Block> blocks = mineBlocks.get();
        for (int y = ceil; y >= floor && total < 4096; y--) {
            for (int x = bx; x <= bx + 15; x++) {
                for (int z = bz; z <= bz + 15; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (blocks.contains(mc.world.getBlockState(p).getBlock())) {
                        total++;
                        if (isExposed(p)) exposed++;
                    }
                }
            }
        }
        return new int[] { total, exposed };
    }

    private boolean isExposed(BlockPos p) {
        for (Direction d : Direction.values()) {
            var st = mc.world.getBlockState(p.offset(d));
            if (st.isAir() || st.isReplaceable()) return true;
        }
        return false;
    }

    /** Safe pause: stop, warn, and re-check after a delay (the anti-stall guarantee). */
    private void pause(String msg) {
        dbg("PAUSE (from %s): %s", state, msg);
        warning(msg);
        stopMining();
        state = State.PAUSED;
        step = 0;
        attempts = 0;
        timer = pauseRetryTicks.get();
    }

    /**
     * Push the handful of STOCK Baritone settings the ClearArea quarry benefits from (all by name, so
     * the addon stays decoupled from the Baritone jar's mappings). The quarry itself is driven by the
     * box passed to {@code clearArea}, so there are no area/scan settings to configure.
     */
    private void applyBaritoneSettings() {
        Settings s = BaritoneAPI.getSettings();
        setBaritone(s, "allowbreak", true);
        // Cap how long Baritone searches for a path it can't find, so a bedrock-encased / lava-fronted
        // block the builder can't reach fails fast instead of freezing for the default 2s/5s. Quarry
        // targets are always near, so a reachable path is found well under 1s.
        setBaritone(s, "failuretimeoutms", 1000L);
        setBaritone(s, "planaheadfailuretimeoutms", 1000L);
        dbg("pushed baritone (stock): allowbreak=true failTO=1000ms floorY=%d", minYLevel.get());
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

        // Defer to item use (eating/drinking): hold ALL AutoMiner actions while a bite is
        // in progress, so we never restart mining mid-bite or interrupt AutoEat. (AutoEat's
        // own "pause-baritone" setting stops Baritone; this keeps the addon out of the way
        // too.) The timer is left frozen and resumes once the use finishes.
        if (mc.player.isUsingItem()) return;

        if (timer > 0) { timer--; return; }

        if (pauseOnHazard.get() && isHazard()) {
            if (state != State.PAUSED) {
                warning("Hazard detected — pausing.");
                stopMining();
                state = State.PAUSED;
                step = 0;
            }
            timer = pauseRetryTicks.get();
            return;
        }

        switch (state) {
            case MINING       -> tickMining();
            case CLEAR_AREA   -> tickClearArea();
            case PLACE_ECHEST -> tickPlaceEchest();
            case ECHEST_TAKE  -> tickEchestTake();
            case PLACE_SHULKER-> tickPlaceShulker();
            case SHULKER_FILL -> tickShulkerFill();
            case BREAK_SHULKER-> tickBreakShulker();
            case ECHEST_STORE -> tickEchestStore();
            case BREAK_ECHEST -> tickBreakEchest();
            case PAUSED       -> tickPaused();
            case DONE         -> finishStorage();
        }
    }

    // ----- MINING -----

    private void tickMining() {
        // One junk item per action-delay so dropping can never burst a stack of packets.
        if (dropJunk.get() && dropOneJunk()) { timer = nextDelay(); return; }

        // Keep target blocks OUT of the hotbar (BepHax-style hotbar cleaning). Vanilla pickup
        // fills empty hotbar slots before main-inventory slots, so mined blocks pile into the
        // hotbar and leave no slot to stage the shulker/echest — the root cause of "a shulker
        // gets pulled but never placed". Shift one target stack from the hotbar back into the
        // main inventory per action-delay (one packet, never bursts).
        if (sweepHotbarTargets()) { timer = nextDelay(); return; }

        // Genuinely-full trigger: fire only when empty MAIN-inventory slots run out (hotbar
        // excluded — it's kept clear of target and reserved for staging). Counting empties, not
        // occupancy, means reserved items (echest stack, spare tools, food) never trigger early,
        // and partial target stacks keep filling (vanilla merges into them before consuming an
        // empty) so we don't store until the inventory is actually full.
        boolean invFull = requireFullStacks.get()
            ? !canHoldMoreTarget()                              // every target stack at 64, no empty slot
            : emptyMainSlots() <= freeSlotsBeforeStore.get();   // legacy: empty-slot margin
        if (invFull && hasTargetStacks()) {                      // never start a cycle with nothing to store
            stopMining();
            dbg("inventory full (%s) — starting storage cycle",
                requireFullStacks.get() ? "all stacks at 64" : "emptyMain<=" + freeSlotsBeforeStore.get());
            if (countItem(Items.ENDER_CHEST) == 0) { pause("No ender chest in inventory."); return; }
            go(State.CLEAR_AREA);
            return;
        }

        // Mining is the stock-Baritone ClearArea quarry: clear the current chunk box, then spiral.
        tickClearAreaMining();
    }

    // ----- CLEAR_AREA: open a pocket so the echest + shulker both have somewhere to go -----

    private void tickClearArea() {
        // Before placing anything, make sure the hotbar has a free slot to stage the echest and
        // shulker — evict any target blocks the hotbar picked up during mining into main inv.
        if (sweepHotbarTargets()) { timer = nextDelay(); return; }

        BlockPos feet = mc.player.getBlockPos();
        // Break the four horizontal neighbours at feet + head height, leaving the floor
        // (feet.down()) intact. Breaks continuously (every tick) like normal mining, and
        // only touches solid non-bedrock blocks — an already-open area is a no-op.
        for (Direction dir : HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos p = feet.offset(dir).up(dy);
                if (p.getY() < minYLevel.get()) continue;
                var st = mc.world.getBlockState(p);
                if (!st.isReplaceable() && st.getBlock() != Blocks.BEDROCK) {
                    BlockUtils.breakBlock(p, true);
                    return; // continuous; resume next tick (no action-delay during breaking)
                }
            }
        }
        go(State.PLACE_ECHEST); // pocket cleared
        timer = nextDelay();
    }

    // ----- PLACE_ECHEST (select -> place -> swapBack, one packet per tick) -----

    private void tickPlaceEchest() {
        switch (tickPlace(s -> s.getItem() == Items.ENDER_CHEST)) {
            case DONE -> { placedEchest = pendingPlace; dbg("placed ender chest at %s", placedEchest.toShortString()); go(State.ECHEST_TAKE); timer = nextDelay(); }
            case FAILED -> pause("Couldn't place ender chest (no spot or no free hotbar slot).");
            case BUSY -> {}
        }
    }

    // ----- ECHEST_TAKE: open, restock pickaxe, pull one empty shulker -----

    private void tickEchestTake() {
        switch (step) {
            case 0 -> { openBlock(placedEchest); step = 1; timer = nextDelay(); }
            case 1 -> {
                if (isContainerOpen()) { step = 2; attempts = 0; timer = nextDelay(); }
                else if (++attempts >= 20) pause("Ender chest didn't open.");
                else timer = nextDelay();
            }
            case 2 -> { // restock pickaxe if low (at most one shift-move)
                if (!isContainerOpen()) { pause("Ender chest closed unexpectedly."); return; }
                restockTool(mc.player.currentScreenHandler);
                step = 3; timer = nextDelay();
            }
            case 3 -> { // pull one empty shulker straight into the HOTBAR (so it can be placed), or finish
                if (!isContainerOpen()) { pause("Ender chest closed unexpectedly."); return; }
                ScreenHandler h = mc.player.currentScreenHandler;
                int shulkerSlot = findContainerSlot(h, this::isEmptyShulker);
                if (shulkerSlot >= 0) {
                    int free = freeHotbarSlot();
                    if (free != -1) swapToHotbar(h, shulkerSlot, free); // one packet, lands in hotbar
                    else quickMove(h, shulkerSlot);                     // fallback: at least pull it out
                    dbg("pulled empty shulker to hotbar slot %d", free);
                    step = 4;
                } else { dbg("no empty shulkers left in ender chest"); step = 5; }
                timer = nextDelay();
            }
            case 4 -> { closeScreen(); go(State.PLACE_SHULKER); timer = nextDelay(); }
            default -> { // 5: no empty shulkers left in the ender chest
                closeScreen();
                if (countShulkers(this::isFilledShulkerInInv) > 0) go(State.BREAK_SHULKER); // store what we have first
                else if (countShulkers(this::isEmptyShulkerInInv) > 0) go(State.PLACE_SHULKER); // use an inventory spare
                else { doneReason = "no empty shulkers left in the ender chest"; go(State.DONE); }
                timer = nextDelay();
            }
        }
    }

    // ----- PLACE_SHULKER -----

    private void tickPlaceShulker() {
        switch (tickPlace(this::isEmptyShulkerStack)) {
            case DONE -> { placedShulker = pendingPlace; dbg("placed shulker at %s", placedShulker.toShortString()); go(State.SHULKER_FILL); timer = nextDelay(); }
            case FAILED -> pause("Couldn't place shulker (no spot or no free hotbar slot).");
            case BUSY -> {}
        }
    }

    // ----- SHULKER_FILL: open, then shift one target stack per tick -----

    private void tickShulkerFill() {
        switch (step) {
            case 0 -> { openBlock(placedShulker); step = 1; timer = nextDelay(); }
            case 1 -> {
                if (isContainerOpen()) { step = 2; attempts = 0; timer = nextDelay(); }
                else if (++attempts >= 20) pause("Shulker didn't open.");
                else timer = nextDelay();
            }
            case 2 -> { // move ONE target stack into the shulker per tick (only target ever moves)
                if (!isContainerOpen()) { pause("Shulker closed unexpectedly."); return; }
                ScreenHandler h = mc.player.currentScreenHandler;
                int playerSlot = findPlayerSlotInContainer(h, this::isTargetStack);
                if (playerSlot < 0) { step = 3; timer = nextDelay(); return; } // no target left → sealed
                // Destination-aware: only shift if the shulker can actually take this stack. If it's
                // full, stop here — the post-store loop will place a fresh shulker and keep going.
                if (!containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) { step = 3; timer = nextDelay(); return; }
                quickMove(h, playerSlot);     // QUICK_MOVE moves only the clicked (target) stack
                timer = fillMoveDelay();       // faster cadence for in-container transfers
            }
            default -> { closeScreen(); go(State.BREAK_SHULKER); timer = nextDelay(); } // 3
        }
    }

    // ----- BREAK_SHULKER -----

    private void tickBreakShulker() {
        if (placedShulker != null
                && mc.world.getBlockState(placedShulker).getBlock() instanceof ShulkerBoxBlock) {
            BlockUtils.breakBlock(placedShulker, true); // continuous until it pops (drops filled)
            return; // breaking must run every tick to make progress — no action-delay here
        }
        placedShulker = null;
        go(State.ECHEST_STORE);
        timer = nextDelay();
    }

    // ----- ECHEST_STORE: reopen echest, push filled shulker(s) in one per tick -----

    private void tickEchestStore() {
        switch (step) {
            case 0 -> { openBlock(placedEchest); step = 1; timer = nextDelay(); }
            case 1 -> {
                if (isContainerOpen()) { step = 2; attempts = 0; timer = nextDelay(); }
                else if (++attempts >= 20) pause("Ender chest didn't reopen.");
                else timer = nextDelay();
            }
            case 2 -> { // one filled shulker into the echest per tick
                if (!isContainerOpen()) { pause("Ender chest closed unexpectedly."); return; }
                ScreenHandler h = mc.player.currentScreenHandler;
                int playerSlot = findPlayerSlotInContainer(h, this::isFilledShulkerStack);
                if (playerSlot < 0) { step = 3; timer = nextDelay(); return; } // nothing left to store
                if (!containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) { // echest full of shulkers
                    closeScreen(); doneReason = "ender chest full of filled shulkers"; go(State.DONE); timer = nextDelay(); return;
                }
                quickMove(h, playerSlot);
                timer = fillMoveDelay();
            }
            default -> { // 3: done storing — loop another shulker or recover the echest
                closeScreen();
                if (countShulkers(this::isEmptyShulkerInInv) > 0 && hasTargetStacks()) go(State.PLACE_SHULKER);
                else go(State.BREAK_ECHEST);
                timer = nextDelay();
            }
        }
    }

    // ----- BREAK_ECHEST -----

    private void tickBreakEchest() {
        if (placedEchest != null
                && mc.world.getBlockState(placedEchest).getBlock() == Blocks.ENDER_CHEST) {
            BlockUtils.breakBlock(placedEchest, true); // continuous until it pops
            return; // breaking must run every tick to make progress — no action-delay here
        }
        placedEchest = null;
        resumeMining();
        timer = nextDelay();
    }

    private void resumeMining() {
        dbg("resume mining at chunk [%d,%d]", curCX, curCZ);
        go(State.MINING);
        startMining();
    }

    /** Storage is exhausted: stop the module, and (if 'auto-disconnect' is on) leave the server too. */
    private void finishStorage() {
        boolean disconnect = autoDisconnect.get();
        dbg("DONE (%s) -> %s", doneReason, disconnect ? "disconnect + toggle off" : "toggle off");
        info("Storage done: %s.%s", doneReason, disconnect ? " Disconnecting." : "");
        if (disconnect) disconnectFromServer("AutoMiner: " + doneReason);
        toggle();
    }

    /** Cleanly leave the current server with a reason shown on the disconnect screen. */
    private void disconnectFromServer(String reason) {
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal(reason));
        }
    }

    // ----- PAUSED -----

    private void tickPaused() {
        if (!isHazard()) {
            info("Clear — resuming mining.");
            resumeMining();
        } else {
            timer = pauseRetryTicks.get();
        }
    }

    // ---------------- Placement sub-routine (3 ticks: select, place, swapBack) ----------------

    /**
     * Desync-safe placement of the item matching {@code pred}. Picks a spot, makes sure the
     * item is in the HOTBAR (shift-clicked pulls land in main inventory, which InvUtils.swap
     * can't select — this was the "shulker stuck in main inventory" bug), then places it.
     * Uses {@link #step}, so the caller must be a dedicated state; {@link #pendingPlace} holds
     * the spot on DONE. Every dead end returns FAILED so the caller can pause + recover.
     */
    private Place tickPlace(java.util.function.Predicate<ItemStack> pred) {
        switch (step) {
            case 0 -> { // choose a spot
                pendingPlace = findPlacementSpot();
                if (pendingPlace == null) return Place.FAILED;
                step = 1; attempts = 0; timer = nextDelay();
                return Place.BUSY;
            }
            case 1 -> { // ensure the item is in the hotbar
                if (InvUtils.findInHotbar(pred).found()) { step = 3; attempts = 0; timer = nextDelay(); return Place.BUSY; }
                FindItemResult any = InvUtils.find(pred);
                if (!any.found()) return Place.FAILED;            // lost the item
                int free = freeHotbarSlot();
                if (free == -1) {                                 // hotbar full — try to free a slot
                    if (sweepHotbarTargets()) { timer = nextDelay(); return Place.BUSY; } // evicted a target, retry
                    return Place.FAILED;                          // genuinely no room (all tools/echest/food)
                }
                InvUtils.move().from(any.slot()).toHotbar(free);  // main -> hotbar
                attempts = 0; step = 2; timer = nextDelay();
                return Place.BUSY;
            }
            case 2 -> { // wait for the move to land
                if (InvUtils.findInHotbar(pred).found()) { step = 3; attempts = 0; }
                else if (++attempts >= 5) return Place.FAILED;
                timer = nextDelay();
                return Place.BUSY;
            }
            default -> { // 3: place from the hotbar (BlockUtils.place selects + places + restores)
                FindItemResult hb = InvUtils.findInHotbar(pred);
                if (hb.found() && BlockUtils.place(pendingPlace, hb, true, 0, true)) {
                    timer = nextDelay();
                    return Place.DONE;
                }
                if (++attempts >= 6) return Place.FAILED;
                timer = nextDelay();
                return Place.BUSY;
            }
        }
    }

    // ---------------- Baritone control ----------------

    private void startMining() {
        startClearArea(); // stock Baritone BuilderProcess quarry of the current chunk box
    }

    private void stopMining() {
        if (baritone != null) {
            baritone.getMineProcess().cancel();        // legacy safety (no-op if unused)
            baritone.getBuilderProcess().onLostControl(); // cancel any active clearArea quarry
            baritone.getPathingBehavior().cancelEverything();
        }
    }

    // ----- Outward chunk spiral -----

    /** Capture the start chunk + quarry ceiling and seed the outward spiral at the centre. */
    private void resetArea() {
        BlockPos fp = mc.player.getBlockPos();
        startCX = fp.getX() >> 4;
        startCZ = fp.getZ() >> 4;
        spX = 0; spZ = 0; spDir = 0; spSegLen = 1; spSegLeft = 1; spSegDone = 0;
        curCX = startCX; curCZ = startCZ;
        // Quarry ceiling = where we started (+1 for head level); the box never chases the player.
        quarryTopY = fp.getY() + 1;
        dbg("area reset: start chunk [%d,%d] at player %s, quarry box Y[%d..%d]",
            startCX, startCZ, fp.toShortString(), minYLevel.get(), quarryTopY);
        clearAreaStarted = false;
        clearAreaStallTicks = 0;
        lastClearPos = null;
    }

    // ----- ClearArea engine: stock BuilderProcess quarry of one chunk box at a time -----

    /** Issue a clearArea (full chunk box, Y floor..quarry ceiling) for the current spiral chunk. */
    private void startClearArea() {
        int x1 = curCX << 4, z1 = curCZ << 4;
        BlockPos c1 = new BlockPos(x1, minYLevel.get(), z1);
        BlockPos c2 = new BlockPos(x1 + 15, quarryTopY, z1 + 15);
        callClearArea(c1, c2);
        clearAreaStarted = true;
        clearAreaStallTicks = 0;
        lastClearPos = mc.player.getBlockPos();
        dbg("clearArea chunk [%d,%d] box x[%d..%d] y[%d..%d] z[%d..%d]",
            curCX, curCZ, x1, x1 + 15, minYLevel.get(), quarryTopY, z1, z1 + 15);
    }

    /**
     * Drive the ClearArea engine: when the builder finishes a chunk box it goes inactive — advance
     * the spiral and start the next. While it's working, watch for a genuine stall (bot hasn't moved
     * for a while = an unreachable pocket) and skip the chunk so it can never hang.
     */
    private void tickClearAreaMining() {
        if (!baritone.getBuilderProcess().isActive()) {
            if (clearAreaStarted) {
                if (debugLog.get()) {
                    int[] c = countMineBlocksInChunk(curCX, curCZ);
                    dbg("chunk [%d,%d] clearArea done: %d target block(s) left in box, %d exposed — advancing", curCX, curCZ, c[0], c[1]);
                }
                advanceSpiral();
                info("Chunk cleared — moving to chunk [%d, %d].", curCX, curCZ);
            }
            startClearArea();
            timer = nextDelay();
            return;
        }
        // builder is working — detect a true stall via lack of movement (not just slow progress)
        BlockPos pos = mc.player.getBlockPos();
        if (lastClearPos == null || !pos.equals(lastClearPos)) {
            lastClearPos = pos;
            clearAreaStallTicks = 0;
        } else if (++clearAreaStallTicks > CLEARAREA_STALL_TICKS) {
            warning("clearArea stalled on chunk [%d, %d] (no movement %ds) — skipping.", curCX, curCZ, CLEARAREA_STALL_TICKS / 20);
            dbg("clearArea stall: skipping chunk [%d,%d]", curCX, curCZ);
            baritone.getBuilderProcess().onLostControl();
            advanceSpiral();
            startClearArea();
            timer = nextDelay();
        }
    }

    /**
     * Call IBuilderProcess.clearArea(BlockPos, BlockPos) reflectively. The addon is Yarn-mapped and
     * the Baritone API jar isn't, so we can't name its BlockPos parameter type at compile time — but
     * both BlockPos classes are identical at runtime after Fabric remapping, so reflection resolves
     * and invokes cleanly. (Same reason the addon drives MineProcess by name and pushes settings by
     * name.)
     */
    private void callClearArea(BlockPos c1, BlockPos c2) {
        try {
            Object builder = baritone.getBuilderProcess();
            builder.getClass().getMethod("clearArea", BlockPos.class, BlockPos.class).invoke(builder, c1, c2);
        } catch (Exception e) {
            warning("Couldn't start clearArea — is this Baritone build's BuilderProcess available?");
            dbg("clearArea reflection failed: %s", e.toString());
        }
    }

    /** Step the spiral one chunk outward; the next clearArea() box is built from curCX/curCZ. */
    private void advanceSpiral() {
        stepSpiral();
        curCX = startCX + spX;
        curCZ = startCZ + spZ;
    }

    /**
     * Outward square spiral over chunk offsets: (0,0),(1,0),(1,1),(0,1),(-1,1),(-1,0),(-1,-1)…
     * Directions cycle +x,+z,-x,-z; the segment length grows by one every two direction changes.
     */
    private void stepSpiral() {
        switch (spDir) {
            case 0 -> spX++;
            case 1 -> spZ++;
            case 2 -> spX--;
            default -> spZ--;
        }
        if (--spSegLeft == 0) {
            spDir = (spDir + 1) & 3;
            if (++spSegDone == 2) { spSegDone = 0; spSegLen++; }
            spSegLeft = spSegLen;
        }
    }

    // ---------------- Inventory helpers ----------------

    /** Empty slots in the MAIN inventory only (indices 9-35); the hotbar is reserved for staging. */
    private int emptyMainSlots() {
        int n = 0;
        for (int i = 9; i <= 35; i++) if (mc.player.getInventory().getStack(i).isEmpty()) n++;
        return n;
    }

    /**
     * Can the MAIN inventory (9-35) accept even one more target block? True if there's an empty slot
     * (room for a new stack) or a target stack below 64 (room to top it off). When this is false the
     * inventory is completely packed — every target stack is a full 64 and no empty slots remain — so
     * a storage cycle will fill shulkers with whole stacks (the 'require-full-stacks' trigger).
     * Reserved non-target items (echest/shulker/tool/food) occupying a slot simply don't add capacity.
     */
    private boolean canHoldMoreTarget() {
        var inv = mc.player.getInventory();
        for (int i = 9; i <= 35; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) return true;
            if (isTargetStack(s) && s.getCount() < s.getMaxCount()) return true;
        }
        return false;
    }

    private boolean hasTargetStacks() {
        for (int i = 0; i < 36; i++) if (isTargetStack(mc.player.getInventory().getStack(i))) return true;
        return false;
    }

    /** A "target" for inventory purposes = a kept item (the mined DROP), not the world block mined. */
    private boolean isTargetStack(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return keepItems.get().contains(stack.getItem());
    }

    private int countItem(Item item) {
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

    /** Container-contents detection for a shulker item in 1.21.4 uses the CONTAINER component. */
    private boolean hasContents(ItemStack stack) {
        var c = stack.get(DataComponentTypes.CONTAINER);
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

    /**
     * "Risky" foods we throw out rather than keep for AutoEat: ones that apply a harmful effect or
     * teleport the bot out of position. Stews are caught separately by the non-stackable check in
     * {@link #isRiskyFood} (and listed here too for clarity).
     */
    private static final java.util.Set<Item> RISKY_FOODS = java.util.Set.of(
        Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
        Items.CHICKEN, Items.CHORUS_FRUIT,
        Items.SUSPICIOUS_STEW, Items.MUSHROOM_STEW, Items.RABBIT_STEW, Items.BEETROOT_SOUP
    );

    /**
     * A food we'd rather drop than keep: an explicitly-listed harmful/teleport food (rotten flesh,
     * spider eye, poisonous/raw foods, pufferfish, chorus fruit), or ANY non-stackable food — which
     * in vanilla is exactly the stews (mushroom/rabbit/beetroot/suspicious), so modded or future
     * specialty foods are covered too. Normal stackable foods (bread, cooked meat, carrots, golden
     * apples, …) are not risky and are kept for AutoEat.
     */
    private boolean isRiskyFood(ItemStack s) {
        if (RISKY_FOODS.contains(s.getItem())) return true;
        return s.contains(DataComponentTypes.FOOD) && s.getMaxCount() == 1; // stews / specialty foods
    }

    /** Drop the first junk slot found (one per call). Returns true if something was dropped. */
    private boolean dropOneJunk() {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            if (isTargetStack(s)) continue;
            if (isShulker(s)) continue;
            if (s.getItem() == Items.ENDER_CHEST) continue;
            if (s.contains(DataComponentTypes.TOOL)) continue;
            // Keep good food for AutoEat, but let risky foods (rotten flesh, stews, poison/teleport
            // foods) fall through to be dropped when 'drop-bad-food' is on.
            if (s.contains(DataComponentTypes.FOOD) && !(dropBadFood.get() && isRiskyFood(s))) continue;
            InvUtils.drop().slot(i);
            return true;
        }
        return false;
    }

    // ---------------- Tool restock ----------------

    private void restockTool(ScreenHandler h) {
        // Resolve Auto to a concrete tool from the target blocks.
        final ToolType t = toolType.get() == ToolType.Auto ? autoToolType() : toolType.get();

        ItemStack held = mc.player.getMainHandStack();
        boolean lowOrMissing = !isToolOfType(held, t)
            || (held.getMaxDamage() - held.getDamage()) < restockDurability.get();
        if (!lowOrMissing) return;
        dbg("restock check: held=%s low/missing -> looking for fresh %s", held.getItem(), t);

        // Match the chosen tool type specifically (by item type, so custom names/enchants
        // don't matter) — a named axe/sword in the chest can't be grabbed by mistake.
        int slot = findContainerSlot(h, s ->
            isToolOfType(s, t)
                && (s.getMaxDamage() - s.getDamage()) >= restockDurability.get()
                && (!reserveSilk.get() || !Utils.hasEnchantments(s, Enchantments.SILK_TOUCH)));
        if (slot >= 0) {
            int free = freeHotbarSlot();
            if (free != -1) swapToHotbar(h, slot, free); // into the hotbar so AutoTool/mining can use it
            else quickMove(h, slot);                     // fallback
            dbg("restocked %s into hotbar slot %d", t, free);
            info("Restocked %s.", t.name().toLowerCase());
        } else {
            dbg("no fresh %s found in ender chest", t);
            warning("No fresh %s in ender chest.", t.name().toLowerCase());
        }
    }

    /** True if the stack is a tool of the given type, regardless of custom name or enchants. */
    private boolean isToolOfType(ItemStack s, ToolType t) {
        Item i = s.getItem();
        return switch (t) {
            case Pickaxe -> i == Items.WOODEN_PICKAXE || i == Items.STONE_PICKAXE || i == Items.IRON_PICKAXE
                || i == Items.GOLDEN_PICKAXE || i == Items.DIAMOND_PICKAXE || i == Items.NETHERITE_PICKAXE;
            case Shovel -> i == Items.WOODEN_SHOVEL || i == Items.STONE_SHOVEL || i == Items.IRON_SHOVEL
                || i == Items.GOLDEN_SHOVEL || i == Items.DIAMOND_SHOVEL || i == Items.NETHERITE_SHOVEL;
            case Axe -> i == Items.WOODEN_AXE || i == Items.STONE_AXE || i == Items.IRON_AXE
                || i == Items.GOLDEN_AXE || i == Items.DIAMOND_AXE || i == Items.NETHERITE_AXE;
            case Hoe -> i == Items.WOODEN_HOE || i == Items.STONE_HOE || i == Items.IRON_HOE
                || i == Items.GOLDEN_HOE || i == Items.DIAMOND_HOE || i == Items.NETHERITE_HOE;
            case Auto -> isToolOfType(s, autoToolType());
        };
    }

    /** Pick the tool type matching the mined blocks' mineable tag (sand→shovel, deepslate→pickaxe …). */
    private ToolType autoToolType() {
        for (Block b : mineBlocks.get()) {
            var st = b.getDefaultState();
            if (st.isIn(BlockTags.SHOVEL_MINEABLE)) return ToolType.Shovel;
            if (st.isIn(BlockTags.AXE_MINEABLE))    return ToolType.Axe;
            if (st.isIn(BlockTags.HOE_MINEABLE))     return ToolType.Hoe;
            if (st.isIn(BlockTags.PICKAXE_MINEABLE)) return ToolType.Pickaxe;
        }
        return ToolType.Pickaxe;
    }

    // ---------------- Container interaction ----------------
    // Vanilla ScreenHandler + interactionManager APIs. Slot indexing: the player
    // inventory is always the trailing 36 slots of the open handler.

    private boolean isContainerOpen() {
        return mc.player != null
            && mc.player.currentScreenHandler != mc.player.playerScreenHandler;
    }

    private void openBlock(BlockPos pos) {
        if (pos == null) return;
        Vec3d hit = Vec3d.ofCenter(pos);
        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
            BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, pos, false);
            BlockUtils.interact(bhr, Hand.MAIN_HAND, true);
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

    /**
     * True if the container half (the chest/shulker, not the player rows) can accept {@code s}:
     * either an empty slot, or — for stackable items — a non-full stack of the same item. Used to
     * make fills/stores destination-aware so a blind shift-click can't spin against a full
     * container, and so a filled shulker (non-stackable) only counts an empty slot as room.
     */
    private boolean containerHasRoomFor(ScreenHandler h, ItemStack s) {
        int containerSlots = h.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack c = h.slots.get(i).getStack();
            if (c.isEmpty()) return true;
            if (s.getMaxCount() > 1 && ItemStack.areItemsAndComponentsEqual(c, s) && c.getCount() < c.getMaxCount()) return true;
        }
        return false;
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
        for (Direction dir : HORIZONTAL) {
            BlockPos target = feet.offset(dir);
            if (target.getY() < minYLevel.get()) continue; // never place storage below the floor
            BlockPos below = target.down();
            if (mc.world.getBlockState(target).isReplaceable()
                && !mc.world.getBlockState(below).isReplaceable()) {
                return target;
            }
        }
        return null;
    }

    /** First empty hotbar slot (0-8), or -1 if the hotbar is full. */
    private int freeHotbarSlot() {
        for (int i = 0; i <= 8; i++) if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        return -1;
    }

    /** Move the item at a (currently-open) container slot into a hotbar slot — single SWAP packet. */
    private void swapToHotbar(ScreenHandler h, int fromSlot, int hotbarSlot) {
        mc.interactionManager.clickSlot(h.syncId, fromSlot, hotbarSlot, SlotActionType.SWAP, mc.player);
    }

    /**
     * Keep the hotbar free of target blocks (BepHax-style hotbar cleaning). Vanilla item pickup
     * fills empty hotbar slots before main-inventory slots, so mined blocks pile into the hotbar
     * and leave no room to stage the shulker/ender chest — the root cause of "a shulker gets
     * pulled but never placed". Shift ONE target stack from the hotbar into the main inventory
     * (one QUICK_MOVE packet) per call. MUST only be called when no container is open (it acts on
     * the player's own screen handler), and only when the main inventory can actually receive the
     * stack, so it never spins with no progress. Returns true if it moved one.
     */
    private boolean sweepHotbarTargets() {
        var inv = mc.player.getInventory();
        for (int i = 0; i <= 8; i++) {
            ItemStack s = inv.getStack(i);
            if (!isTargetStack(s)) continue;
            if (!mainHasRoomFor(s)) continue; // can't relocate now; it'll go into the shulker during fill
            // Hotbar inventory index i is screen slot 36+i in the player's own screen handler;
            // shift-clicking it there moves the stack into the main inventory (slots 9-35).
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 36 + i, 0, SlotActionType.QUICK_MOVE, mc.player);
            return true;
        }
        return false;
    }

    /** True if the main inventory (slots 9-35) has an empty slot or a non-full stack matching {@code s}. */
    private boolean mainHasRoomFor(ItemStack s) {
        var inv = mc.player.getInventory();
        for (int i = 9; i <= 35; i++) {
            ItemStack m = inv.getStack(i);
            if (m.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(m, s) && m.getCount() < m.getMaxCount()) return true;
        }
        return false;
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
