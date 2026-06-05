package com.shallowplague.foreman.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import com.shallowplague.foreman.ForemanAddon;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseButtonEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
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

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Foreman MassExtractor — AFK bulk single-block miner for anarchy servers (Minecraft 1.21.4).
 *
 * Design goals:
 *  - Never lock up. A flat finite-state machine drives everything; any unexpected
 *    condition transitions to a safe PAUSED state instead of stalling.
 *  - Mining is STOCK Baritone's BuilderProcess.clearArea quarry, run one small sub-box at a
 *    time (each chunk is split into clear-box-size cells so the bot repositions often and walks
 *    back over its drops to collect them) and walked outward in a spiral. clearArea is box-confined
 *    and breaks the nearest
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
public class MassExtractor extends Module {
    /** SLF4J logger — debug output lands in the game's latest.log under "(Foreman)". */
    private static final Logger LOG = LoggerFactory.getLogger("Foreman");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgArea = settings.createGroup("Area");
    private final SettingGroup sgStorage = settings.createGroup("Storage");
    private final SettingGroup sgDeposit = settings.createGroup("Deposit chests");
    private final SettingGroup sgTools = settings.createGroup("Tools");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgCave = settings.createGroup("Cave handling");
    private final SettingGroup sgRender = settings.createGroup("Rendering");

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

    private final Setting<Integer> clearBoxSize = sgGeneral.add(new IntSetting.Builder()
        .name("clear-box-size")
        .description("Clear each chunk in sub-boxes this many blocks wide (an N×N footprint, full height) instead of the whole 16×16 chunk in one go. After each sub-box the bot repositions to the next, so it walks back over the ground it just dug and PICKS UP the drops — the fix for items despawning when the bot mines a wide area from one spot. Smaller = more thorough collection but slower (more repositioning). 16 = old behaviour (whole chunk at once); 8 ≈ four cells per chunk; 4 ≈ sixteen. Applied per chunk, so a change takes effect on the next chunk.")
        .defaultValue(8)
        .min(2).max(16).sliderRange(2, 16)
        .build()
    );

    private final Setting<Double> miningReach = sgGeneral.add(new DoubleSetting.Builder()
        .name("mining-reach")
        .description("How far (in blocks) Baritone may reach to break a block — its 'blockReachDistance', default 4.5. LOWER makes the bot stand closer to each block before mining it, so when the block drops the bot is already within the ~1-block pickup range and grabs it instead of leaving it to despawn. Trades mining speed (more stepping) for collection, which is the point. Pushed only while the module is active and restored when it's turned off; re-enable the module after changing it. ~3.0 collects well; below ~2.5 the bot may struggle to reach some blocks.")
        .defaultValue(3.0)
        .min(2.0).max(4.5).sliderRange(2.0, 4.5)
        .build()
    );

    // ----- Area -----

    private final Setting<Boolean> limitArea = sgArea.add(new BoolSetting.Builder()
        .name("limit-area")
        .description("Confine mining to a FINITE area instead of the default infinite outward chunk spiral. When the whole area is cleared, the bot stores whatever it's holding and then finishes (stops, or disconnects if 'auto-disconnect' is on). Bounding the dig also removes any chance of wandering past your site. Off = mine outward forever.")
        .defaultValue(false)
        .build()
    );

    private final Setting<AreaMode> areaMode = sgArea.add(new EnumSetting.Builder<AreaMode>()
        .name("area-mode")
        .description("How the finite area is defined. ChunksFromStart: a width x length box of chunks placed by 'area-anchor' relative to the chunk you ACTIVATE in — no clicking, fully AFK. For a simple NxN square growing out from where you stand, set width = length and area-anchor = Corner. CornerSelect: right-click two blocks to mark opposite corners (like Meteor's Excavator), with a live box render — precise to the block.")
        .defaultValue(AreaMode.ChunksFromStart)
        .visible(limitArea::get)
        .build()
    );

    private final Setting<AreaAnchor> areaAnchor = sgArea.add(new EnumSetting.Builder<AreaAnchor>()
        .name("area-anchor")
        .description("ChunksFromStart only: where the box sits relative to the chunk you activate in. Center: you're in the middle of the box (good for 'mine everything around me'). Corner: your chunk is one CORNER and the box extends outward in the direction you're FACING — i.e. stand at a corner, look at the area, and it mines the square/rectangle in front of you. Re-enable the module after changing it.")
        .defaultValue(AreaAnchor.Center)
        .visible(() -> limitArea.get() && areaMode.get() == AreaMode.ChunksFromStart)
        .build()
    );

    private final Setting<Integer> areaWidthChunks = sgArea.add(new IntSetting.Builder()
        .name("area-width-chunks")
        .description("ChunksFromStart only: size of the area along X, in chunks (1 chunk = 16 blocks). Set this equal to area-length-chunks for a square. Placed per 'area-anchor'. Re-enable the module after changing it.")
        .defaultValue(3)
        .min(1).max(32).sliderRange(1, 16)
        .visible(() -> limitArea.get() && areaMode.get() == AreaMode.ChunksFromStart)
        .build()
    );

    private final Setting<Integer> areaLengthChunks = sgArea.add(new IntSetting.Builder()
        .name("area-length-chunks")
        .description("ChunksFromStart only: size of the area along Z, in chunks (1 chunk = 16 blocks). Set this equal to area-width-chunks for a square. Placed per 'area-anchor'. Re-enable the module after changing it.")
        .defaultValue(3)
        .min(1).max(32).sliderRange(1, 16)
        .visible(() -> limitArea.get() && areaMode.get() == AreaMode.ChunksFromStart)
        .build()
    );

    private final Setting<Integer> areaLayerHeight = sgArea.add(new IntSetting.Builder()
        .name("layer-height")
        .description("Bounded areas only. Mine the area in HORIZONTAL layers this many blocks tall, top-down: the bot clears this slice across the WHOLE area (corner to corner, in clear-box-size cells) before dropping to the next slice — so it never tunnels one cell down to minY while the rest of the area stands untouched. 1 = peel one block-level at a time across the whole area (most even surface + best drop pickup, but the most repositioning/travel). Larger = fewer full-area passes (faster), but it digs this many blocks deep at each spot before moving on. Set it to your full Y band height (maxY-minY+1) for the old per-cell full-height behaviour. Unlimited areas can't pre-sweep an infinite top, so they always clear full-height per chunk.")
        .defaultValue(1)
        .min(1).max(64).sliderRange(1, 32)
        .visible(limitArea::get)
        .build()
    );

    private final Setting<Keybind> selectionBind = sgArea.add(new KeybindSetting.Builder()
        .name("selection-bind")
        .description("CornerSelect only: the button used to mark the two corners. After enabling the module, point at a block and press it once for the first corner, again for the second; mining starts once both are set. Defaults to right mouse button (same as Excavator).")
        .defaultValue(Keybind.fromButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT))
        .visible(() -> limitArea.get() && areaMode.get() == AreaMode.CornerSelect)
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
        .description("When the run ends — storage exhausted (no empty shulkers left to fill, or the ender chest itself full) OR a finite 'limit-area' has been fully cleared — also disconnect from the server. The module always stops in these cases; this just additionally logs you out — handy for an AFK run so you leave cleanly once everything's packed. Off = just stop and stay connected.")
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

    // ----- Deposit chests (optional) -----
    // All OFF by default: with deposit-target = EnderChest the whole storage cycle is exactly the
    // original (fill shulkers into the ender chest you carry, stop when it's full). Set a chest mode to
    // also send filled shulkers to fixed double chests at your base and (optionally) restock empties
    // there for near-unlimited runs.

    private final Setting<DepositTarget> depositTarget = sgDeposit.add(new EnumSetting.Builder<DepositTarget>()
        .name("deposit-target")
        .description("Where filled shulkers end up. EnderChest (default): fill shulkers into the ender chest you carry and stop when it's full (good for 'just fill a few shulkers in my echest'). DepositChests: the ender chest is the field buffer — empties are pulled from it and filled shulkers stored back into it, exactly the same, but once it runs out of empties the bot hauls the filled shulkers out to the NEAREST marked deposit chest, dumps them, refills empty shulkers from a separate supply chest, and comes back — a near-unlimited run that piles the haul at your base. Filled shulkers never clog the working inventory while mining. EnderChestThenChests: same as DepositChests (kept for compatibility).")
        .defaultValue(DepositTarget.EnderChest)
        .build()
    );

    private final Setting<List<String>> depositChests = sgDeposit.add(new StringListSetting.Builder()
        .name("deposit-chests")
        .description("Where FILLED shulkers are dropped off — locations as \"x y z\" (one per entry). Add them in-game by aiming at a chest and pressing the mark-deposit bind, or edit by hand. The bot deposits in the nearest one and moves on to the next if a chest is full or unreachable. Persists across runs — set your base up once.")
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    private final Setting<Keybind> markDepositBind = sgDeposit.add(new KeybindSetting.Builder()
        .name("mark-deposit-bind")
        .description("Aim at a chest and press this to add its location to 'deposit-chests' (where filled shulkers go). Works any time the module is on; ignores duplicates. Default: K.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_K))
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    // (No "deposit after N shulkers" setting: the ender chest is the field buffer, so a trip happens once
    // it runs out of empty shulkers — i.e. the batch size is however many empty shulkers you keep stocked
    // in it / refill per trip. See 'empties-per-trip'.)

    private final Setting<Boolean> refillEmpties = sgDeposit.add(new BoolSetting.Builder()
        .name("refill-empties")
        .description("On a deposit trip, after dropping off filled shulkers, also visit a SUPPLY chest and pull a fresh batch of EMPTY shulkers so the bot can keep filling. This is what makes a run effectively unlimited. Off = trips only drop off filled shulkers, and the run ends when the empties you started with run out.")
        .defaultValue(true)
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    private final Setting<List<String>> supplyChests = sgDeposit.add(new StringListSetting.Builder()
        .name("supply-chests")
        .description("Where EMPTY shulkers are taken from — a SEPARATE chest (or chests) from your deposit chests, locations as \"x y z\". Stock these with spare empty shulkers. On a trip the bot drops filled shulkers at the nearest deposit chest, then walks to the nearest supply chest and refills empties. Add them with the mark-supply bind. Only used when 'refill-empties' is on.")
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest && refillEmpties.get())
        .build()
    );

    private final Setting<Keybind> markSupplyBind = sgDeposit.add(new KeybindSetting.Builder()
        .name("mark-supply-bind")
        .description("Aim at a chest and press this to add its location to 'supply-chests' (where empty shulkers are taken from). Works any time the module is on; ignores duplicates. Default: L.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_L))
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest && refillEmpties.get())
        .build()
    );

    private final Setting<Integer> emptiesPerTrip = sgDeposit.add(new IntSetting.Builder()
        .name("empties-per-trip")
        .description("How many empty shulkers to grab from the chest on each deposit trip (when 'refill-empties' is on). Empty shulkers don't stack, so each takes one slot in both the chest and the bot's inventory.")
        .defaultValue(6)
        .min(1).max(27).sliderRange(1, 18)
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest && refillEmpties.get())
        .build()
    );

    private final Setting<Integer> maxDepositDistance = sgDeposit.add(new IntSetting.Builder()
        .name("max-deposit-distance")
        .description("Don't walk to a deposit chest farther than this many blocks (straight-line). If every marked chest is beyond it, the bot pauses instead of trekking across the map — the spiral keeps pushing the mining face away from your base, so this caps the round trip. 0 = no limit.")
        .defaultValue(1024)
        .min(0).max(10000).sliderRange(0, 4000)
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    private final Setting<Boolean> renderChests = sgDeposit.add(new BoolSetting.Builder()
        .name("render-chests")
        .description("Draw a box at each marked deposit-chest location.")
        .defaultValue(true)
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    private final Setting<ToolType> toolType = sgTools.add(new EnumSetting.Builder<ToolType>()
        .name("restock-tool")
        .description("Which tool to pull from the ender chest when yours runs low. Auto derives it from your target blocks (sand/gravel → shovel, deepslate/stone → pickaxe, logs → axe).")
        .defaultValue(ToolType.Auto)
        .build()
    );

    private final Setting<Boolean> alsoRestockShovel = sgTools.add(new BoolSetting.Builder()
        .name("also-restock-shovel")
        .description("Also keep a fresh SHOVEL on hand (on top of the main restock-tool) so gravel/sand in the quarry is mined FAST with the right tool instead of slogged through with a pickaxe. Topped up opportunistically whenever the ender chest is open during a storage cycle — keep a few spare shovels loose in the echest. Best-effort: a missing shovel never pauses the run (unlike the main tool). Off, or when the main tool is already a shovel, this does nothing.")
        .defaultValue(true)
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

    private final Setting<Boolean> restockFromShulker = sgTools.add(new BoolSetting.Builder()
        .name("restock-from-shulker")
        .description("When NO fresh tool is left in the inventory, pull one from a TOOL-SHULKER kept in your ender chest: place the echest, take the tool-shulker out, place it, open it, grab a fresh tool, then break the shulker and put it back (with its remaining tools) in the chest, and recover the echest. Lets one ender-chest slot hold a whole shulker of spare tools for very long AFK runs. The tool-shulker is identified by its CONTENTS (a shulker holding a fresh 'restock-tool'), so it needs no special name or colour and your loot shulkers are never touched. Off = only the loose-tool grab during a storage cycle restocks (which needs spare tools loose in the ender chest).")
        .defaultValue(false)
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

    private final Setting<Boolean> pauseToEat = sgSafety.add(new BoolSetting.Builder()
        .name("pause-to-eat")
        .description("Pause mining while hunger is low so AutoEat (or you) can finish a bite. The Baritone quarry forces the pickaxe + an attack click every tick, which CANCELS any bite mid-chew — so without this the bot loops between eating and breaking and never actually refills. While paused the quarry is released; mining resumes once you're back to full. No-op when you have no edible food (pausing couldn't help).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> eatBelowHunger = sgSafety.add(new IntSetting.Builder()
        .name("eat-below-hunger")
        .description("Pause to eat when food drops to this many points or below (20 = full). Set this AT OR ABOVE your AutoEat threshold so the quarry yields the moment AutoEat wants to eat (if ours is lower, the loop still happens in the gap). Mining resumes once you're full again (20).")
        .defaultValue(18)
        .min(1).max(19).sliderRange(1, 19)
        .visible(pauseToEat::get)
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
        .description("Write a timestamped trace of what the miner is doing (state changes, chunk advances with remaining-target counts, storage steps, pauses, pushed Baritone settings) to the game's latest.log under '(Foreman)'. Off for normal use; flip on before a test run to capture a shareable log, then off. Interleaves with Baritone's own pathing lines in the same file.")
        .defaultValue(false)
        .build()
    );

    // ----- Cave handling -----
    // Caves at deepslate depth leave the box full of air pockets/caverns. clearArea skips the air
    // (nothing to break) but Baritone's conservative fall defaults make it REFUSE to drop down to the
    // solid blocks around a cavern, so it detours forever or gives up (and our 30s stall-skip then
    // abandons the chunk with blocks left). With good armour + totems you can safely relax those
    // limits. These are pushed to STOCK Baritone while the module is active and RESTORED on deactivate,
    // so they never leak into your manual Baritone use. Lava is NEVER walked/fallen into (lava landings
    // stay cost-infinity and 'assume-walk-on-lava' is forced off), and falling-block (sand/gravel)
    // cascade avoidance is left at Baritone's safe default.

    private final Setting<Boolean> caveHandling = sgCave.add(new BoolSetting.Builder()
        .name("cave-handling")
        .description("Relax Baritone's movement limits so it can drop into caves to reach the surrounding blocks instead of detouring or abandoning the chunk. Pushed only while this module is active and restored when it's turned off. Leave on for cave-riddled deepslate; turn off to use Baritone's stock-safe movement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxFallHeight = sgCave.add(new IntSetting.Builder()
        .name("max-fall-height")
        .description("The tallest drop (in blocks) Baritone will take onto solid ground without a water bucket (Baritone's 'maxFallHeightNoWater', stock default 3 = the no-damage limit). Higher lets the bot drop into caves/shafts to mine. It still never lands in lava (those spots are cost-infinity). 20 ≈ a survivable fall in good armour; 255 = drop any distance (lean on totems).")
        .defaultValue(20)
        .min(3).max(255).sliderRange(3, 64)
        .visible(caveHandling::get)
        .build()
    );

    private final Setting<Boolean> allowParkourPlace = sgCave.add(new BoolSetting.Builder()
        .name("allow-parkour-place")
        .description("Let Baritone place a block mid-jump to make a parkour gap (Baritone's 'allowParkourPlace'). Fairly reliable; helps cross small voids between cave ledges. On for Balanced.")
        .defaultValue(true)
        .visible(caveHandling::get)
        .build()
    );

    private final Setting<Boolean> allowDiagonalDescend = sgCave.add(new BoolSetting.Builder()
        .name("allow-diagonal-descend")
        .description("Let Baritone step diagonally downward (Baritone's 'allowDiagonalDescend'). Slightly risky (can brush unchecked adjacent blocks) but speeds up getting down into caves. On for Balanced.")
        .defaultValue(true)
        .visible(caveHandling::get)
        .build()
    );

    private final Setting<Boolean> allowParkour = sgCave.add(new BoolSetting.Builder()
        .name("allow-parkour")
        .description("Let Baritone make running parkour jumps across gaps (Baritone's 'allowParkour'). Less reliable than parkour-place and can overshoot, so it's OFF for Balanced — turn on for Aggressive cave traversal.")
        .defaultValue(false)
        .visible(caveHandling::get)
        .build()
    );

    private final Setting<Boolean> allowDiagonalAscend = sgCave.add(new BoolSetting.Builder()
        .name("allow-diagonal-ascend")
        .description("Let Baritone step diagonally upward (Baritone's 'allowDiagonalAscend'). OFF for Balanced; turn on for Aggressive.")
        .defaultValue(false)
        .visible(caveHandling::get)
        .build()
    );

    // ----- Rendering -----

    private final Setting<Boolean> renderArea = sgRender.add(new BoolSetting.Builder()
        .name("render-area")
        .description("Draw the bounding box of the finite mining area (and, while picking corners in CornerSelect mode, the block you're aimed at and the box-in-progress). Only relevant when 'limit-area' is on.")
        .defaultValue(true)
        .visible(limitArea::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the area box is rendered: filled sides, outline lines, or both.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> limitArea.get() && renderArea.get())
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour of the area box sides.")
        .defaultValue(new SettingColor(0, 200, 255, 30))
        .visible(() -> limitArea.get() && renderArea.get())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour of the area box.")
        .defaultValue(new SettingColor(0, 200, 255, 255))
        .visible(() -> limitArea.get() && renderArea.get())
        .build()
    );

    // ---------------- State ----------------

    private enum State {
        SELECT,            // CornerSelect mode: waiting for the player to mark two corners
        MINING,            // Baritone is mining target blocks
        CLEAR_AREA,        // mine a small pocket so the echest + shulker both have room
        PLACE_ECHEST,      // place the ender chest (multi-step)
        ECHEST_TAKE,       // open echest, restock pickaxe, pull an empty shulker (multi-step)
        PLACE_SHULKER,     // place the empty shulker (multi-step)
        SHULKER_FILL,      // open shulker, dump target stacks one per tick (multi-step)
        BREAK_SHULKER,     // break the filled shulker to pick it up
        ECHEST_STORE,      // reopen echest, store the filled shulker one per tick (multi-step)
        BREAK_ECHEST,      // break the echest to pick it back up
        RESTOCK,           // tool-shulker restock cycle (driven by an inner RestockPhase FSM)
        DEPOSIT,           // travel to a marked chest, deposit filled shulkers, refill empties (DepositPhase FSM)
        PAUSED,            // hazard / waiting
        DONE               // ender chest full of filled shulkers
    }

    /** Result of one sub-step of a placement sequence. */
    private enum Place { BUSY, DONE, FAILED }

    /**
     * Sub-phases of the tool-shulker restock cycle (the {@link State#RESTOCK} state). Pull a fresh tool
     * from a tool-shulker stored in the ender chest, then put the shulker back: place echest → open →
     * take tool-shulker → close → place shulker → open → take tool → close → break shulker → reopen
     * echest → return shulker → close → break echest → done.
     */
    private enum RestockPhase {
        PLACE_ECHEST,   // place the ender chest
        OPEN_ECHEST,    // open it
        TAKE_SHULKER,   // pull the tool-shulker into the hotbar
        CLOSE_ECHEST,   // close the chest
        PLACE_SHULKER,  // place the tool-shulker
        OPEN_SHULKER,   // open it
        TAKE_TOOL,      // grab a fresh tool into the hotbar
        CLOSE_SHULKER,  // close it
        BREAK_SHULKER,  // break + collect the tool-shulker (keeps its remaining tools)
        REOPEN_ECHEST,  // reopen the ender chest
        RETURN_SHULKER, // put the tool-shulker back in the chest
        CLOSE_ECHEST2,  // close the chest
        BREAK_ECHEST,   // break + collect the ender chest
        DONE            // resume mining (or pause, if the cycle aborted)
    }

    /** Which tool the restock pulls. Auto = derive from the target blocks' mineable tag. */
    public enum ToolType { Auto, Pickaxe, Shovel, Axe, Hoe }

    /** How a finite mining area is defined when {@code limit-area} is on. */
    public enum AreaMode { ChunksFromStart, CornerSelect }

    /** Where the ChunksFromStart box sits relative to the activation chunk. */
    public enum AreaAnchor { Center, Corner }

    /** Where filled shulkers are sent. See {@code deposit-target}. */
    public enum DepositTarget { EnderChest, DepositChests, EnderChestThenChests }

    /**
     * Sub-phases of a deposit trip (the {@link State#DEPOSIT} state). The filled shulkers live in the
     * ender chest (the field buffer) and STAY there until the bot is standing at the deposit chest — so a
     * death on the way to base never strands the haul (it's in the global ender chest, not the bot's
     * inventory). Order: walk to the nearest deposit chest with a clean inventory → at the chest, EXTRACT
     * the filled loot shulkers out of the echest (place echest → open → pull → break) → dump them into the
     * deposit chest → (if refilling) walk to the nearest, separate, supply chest and take ONLY as many
     * empties as will fit in the echest → STOCK those empties straight into the echest → back to mining.
     */
    private enum DepositPhase {
        PATH_TO_DEPOSIT,                                             // walk to the deposit chest (inv clean; filled stay safe in the echest)
        PULL_PLACE, PULL_OPEN, PULL_FILLED, PULL_CLOSE, PULL_BREAK,  // at the chest: extract the filled loot shulkers out of the echest
        OPEN_DEPOSIT, DEPOSIT_FILLED, CLOSE_DEPOSIT,                 // dump them into the deposit chest
        PATH_TO_SUPPLY, OPEN_SUPPLY, TAKE_EMPTIES, CLOSE_SUPPLY,     // refill empties (only as many as fit in the echest)
        STOCK_PLACE, STOCK_OPEN, STOCK_EMPTIES, STOCK_CLOSE, STOCK_BREAK, // put those empties into the ender chest
        DONE
    }

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
    // Baritone settings we changed this run -> their original values, restored on deactivate.
    private final java.util.Map<String, Object> savedBaritone = new java.util.HashMap<>();
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
    private boolean wantToEat = false;        // hunger-pause hysteresis: latched once food dips to the eat threshold, cleared when full

    // Sub-box subdivision (Option B): clear each chunk in clear-box-size cells so the bot repositions
    // between them and walks back over (and picks up) the drops. subBoxes holds the {x1,z1,x2,z2}
    // cells of the chunk currently being mined; subBoxIdx is the one being cleared right now.
    private final java.util.List<int[]> subBoxes = new java.util.ArrayList<>();
    private int subBoxIdx = 0;

    // Bounded area (resolved at activation, or after corner selection). All block coords INCLUSIVE.
    // When areaLimited is false the spiral is infinite and these are unused (legacy behaviour).
    private boolean areaLimited = false;
    private int areaMinX, areaMaxX, areaMinZ, areaMaxZ, areaMinY, areaMaxY;
    private int gridCxMin, gridCxMax, gridCzMin, gridCzMax; // chunk grid covering the box
    private int areaChunksTotal, areaChunksDone;            // progress (per horizontal layer) + layer-complete detection
    private int curLayerTopY = 0;                           // bounded area: top Y of the layer currently being swept (descends top-down)
    private boolean areaComplete = false;                   // every layer down to areaMinY cleared/skipped
    // CornerSelect: the two corners marked via the selection bind (null until set this run).
    private BlockPos corner1 = null, corner2 = null;

    // Tool-shulker restock cycle (State.RESTOCK). 'restocking' routes the shared CLEAR_AREA pocket-prep
    // into the restock FSM instead of a storage cycle; 'restockPhase' is the current sub-phase; and
    // 'restockAbort', if set, makes the cycle pause with that message (after recovering the echest)
    // instead of resuming mining.
    private boolean restocking = false;
    private RestockPhase restockPhase = RestockPhase.PLACE_ECHEST;
    private String restockAbort = null;

    // Deposit-chest trip (State.DEPOSIT). 'depositActive' = the bot uses fixed deposit/supply chests at a
    // base (any deposit-target other than EnderChest). The ender chest stays the FIELD buffer (filled
    // shulkers are stored into it and empties taken from it, exactly like the EnderChest cycle); a trip
    // only happens once the echest runs out of empties, and the trip extracts the filled shulkers back
    // out of the echest to haul them. 'depositPhase' is the trip sub-phase; 'depositChest' the chest
    // currently in use; 'triedChests' the ones skipped this trip (full/unreachable); the travel fields
    // detect a stuck walk-out. 'echestExhausted' marks that the echest has no empty shulkers left.
    private boolean depositActive = false;
    private boolean echestExhausted = false;
    private DepositPhase depositPhase = DepositPhase.PATH_TO_DEPOSIT;
    private BlockPos depositChest = null;
    private final java.util.List<BlockPos> triedChests = new java.util.ArrayList<>();
    private int depositTravelTicks = 0;
    private BlockPos lastTravelPos = null;
    private boolean finishAfterDeposit = false; // bounded-area final drop-off: stop once this trip ends
    private boolean tripExtracted = false;      // the filled shulkers have been pulled out of the echest this trip
    private boolean tripRefilled = false;       // this trip stocked at least one empty shulker back into the echest
    private int echestFreeAfterExtract = 0;     // echest free slots after the extract = how many empties may refill

    public MassExtractor() {
        super(ForemanAddon.CATEGORY, "mass-extractor", "AFK bulk single-block miner with shulker/echest storage and tool restock.");
    }

    // ---------------- Lifecycle ----------------

    @Override
    public void onActivate() {
        dbg("=== activate === mineBlocks=%d keepItems=%d minY=%d actionDelay=%d fillDelay=%d limitArea=%b",
            mineBlocks.get().size(), keepItems.get().size(), minYLevel.get(), actionDelay.get(), fillDelay.get(), limitArea.get());
        baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        applyBaritoneSettings();
        timer = 0;
        placedEchest = null;
        placedShulker = null;
        pendingPlace = null;
        corner1 = null;
        corner2 = null;
        areaComplete = false;
        restocking = false;
        restockPhase = RestockPhase.PLACE_ECHEST;
        restockAbort = null;
        depositActive = (depositTarget.get() != DepositTarget.EnderChest);
        echestExhausted = false;
        depositPhase = DepositPhase.PATH_TO_DEPOSIT;
        depositChest = null;
        triedChests.clear();
        lastTravelPos = null;
        depositTravelTicks = 0;
        finishAfterDeposit = false;
        tripExtracted = false;
        echestFreeAfterExtract = 0;

        // CornerSelect: don't mine yet — sit in SELECT until the player marks both corners
        // (handled in selectCorners(), which resolves the box and starts mining).
        if (limitArea.get() && areaMode.get() == AreaMode.CornerSelect) {
            areaLimited = true;
            go(State.SELECT);
            info("Mark the mining area: aim at a block and press the selection bind for corner 1, then again for corner 2.");
            return;
        }

        resolveArea();   // unbounded spiral, or a ChunksFromStart box centred on the activation chunk
        seedSpiral();
        go(State.MINING);
        startMining();
    }

    @Override
    public void onDeactivate() {
        dbg("=== deactivate ===");
        stopMining();
        restoreBaritone();   // put any Baritone settings we pushed back to the user's originals
        corner1 = null;
        corner2 = null;
    }

    // ---------------- CornerSelect: Excavator-style two-corner marking ----------------

    @EventHandler
    private void onMouseButton(MouseButtonEvent event) {
        if (event.action == KeyAction.Press) { tryMarkCorner(); tryMarkChests(); }
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Press) { tryMarkCorner(); tryMarkChests(); }
    }

    /** Handle both mark binds: add the aimed-at chest to the deposit or the supply list. */
    private void tryMarkChests() {
        if (mc.currentScreen != null || depositTarget.get() == DepositTarget.EnderChest) return;
        if (markDepositBind.get().isPressed()) markChest(depositChests, "deposit");
        if (refillEmpties.get() && markSupplyBind.get().isPressed()) markChest(supplyChests, "supply");
    }

    /** Add the block under the crosshair to the given chest-location list (ignoring duplicates). */
    private void markChest(Setting<List<String>> list, String kind) {
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;
        BlockPos pos = bhr.getBlockPos();
        String key = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        var updated = new java.util.ArrayList<>(list.get());
        if (updated.contains(key)) { info("That %s chest (%d, %d, %d) is already marked.", kind, pos.getX(), pos.getY(), pos.getZ()); return; }
        updated.add(key);
        list.set(updated);
        info("Marked %s chest %d, %d, %d (%d total).", kind, pos.getX(), pos.getY(), pos.getZ(), updated.size());
    }

    /** Parse a chest-location list ("x y z" strings) into BlockPos, skipping malformed entries. */
    private java.util.List<BlockPos> parseChests(java.util.List<String> raw) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (String s : raw) {
            String[] p = s.trim().split("\\s+");
            if (p.length != 3) continue;
            try {
                out.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private java.util.List<BlockPos> depositChestList() { return parseChests(depositChests.get()); }
    private java.util.List<BlockPos> supplyChestList()  { return parseChests(supplyChests.get()); }

    /** Nearest chest in {@code chests} not already tried this trip, within max-deposit-distance (or null). */
    private BlockPos nearestChest(java.util.List<BlockPos> chests) {
        BlockPos feet = mc.player.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos c : chests) {
            if (triedChests.contains(c)) continue;
            double d = feet.getSquaredDistance(c);
            if (d < bestD) { bestD = d; best = c; }
        }
        if (best != null && maxDepositDistance.get() > 0) {
            double max = maxDepositDistance.get();
            if (bestD > max * max) return null; // every untried chest is out of range
        }
        return best;
    }

    /**
     * While in SELECT, a press of the selection bind marks the block under the crosshair as the next
     * corner. First press sets corner 1, second sets corner 2 and kicks off mining of the resolved box.
     */
    private void tryMarkCorner() {
        if (state != State.SELECT || mc.currentScreen != null) return;
        if (!selectionBind.get().isPressed()) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;
        BlockPos pos = bhr.getBlockPos();
        if (corner1 == null) {
            corner1 = pos;
            info("Corner 1 set: %d, %d, %d. Aim at the opposite corner and press again.", pos.getX(), pos.getY(), pos.getZ());
        } else {
            corner2 = pos;
            info("Corner 2 set: %d, %d, %d — mining the area now.", pos.getX(), pos.getY(), pos.getZ());
            resolveAreaFromCorners();
            seedSpiral();
            go(State.MINING);
            startMining();
        }
    }

    // ---------------- Area box rendering ----------------

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        // Deposit + supply chests render independently of the mining-area box.
        if (renderChests.get() && depositTarget.get() != DepositTarget.EnderChest) {
            for (BlockPos c : depositChestList()) event.renderer.box(c, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            if (refillEmpties.get())
                for (BlockPos c : supplyChestList()) event.renderer.box(c, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
        if (!limitArea.get() || !renderArea.get()) return;
        if (state == State.SELECT) {
            // outline the block under the crosshair, plus corner 1 and the box-in-progress once set
            if (mc.crosshairTarget instanceof BlockHitResult bhr) {
                BlockPos aim = bhr.getBlockPos();
                event.renderer.box(aim, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                if (corner1 != null) renderBounds(event, corner1, aim);
            }
            if (corner1 != null) event.renderer.box(corner1, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        } else if (areaLimited) {
            // the resolved mining box (block coords are inclusive -> +1 on the max side to cover the blocks)
            event.renderer.box(areaMinX, areaMinY, areaMinZ, areaMaxX + 1, areaMaxY + 1, areaMaxZ + 1,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    /** Render a box spanning two inclusive block corners (covers the full blocks). */
    private void renderBounds(Render3DEvent event, BlockPos a, BlockPos b) {
        event.renderer.box(
            Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1,
            sideColor.get(), lineColor.get(), shapeMode.get(), 0);
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
        int x1 = bx, x2 = bx + 15, z1 = bz, z2 = bz + 15;
        int floor = areaMinY, ceil = areaMaxY;
        if (areaLimited) {
            x1 = Math.max(x1, areaMinX); x2 = Math.min(x2, areaMaxX);
            z1 = Math.max(z1, areaMinZ); z2 = Math.min(z2, areaMaxZ);
        }
        int total = 0, exposed = 0;
        List<Block> blocks = mineBlocks.get();
        for (int y = ceil; y >= floor && total < 4096; y--) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
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
        savedBaritone.clear();                       // fresh snapshot for this run
        pushBaritone(s, "allowbreak", true);
        // Stand closer to each block (lower reach) so the bot is within the ~1-block pickup range when
        // the block drops -> it collects the haul instead of leaving it to despawn. Baritone stock 4.5.
        pushBaritone(s, "blockreachdistance", miningReach.get().floatValue());
        // Cap how long Baritone searches for a path it can't find, so a bedrock-encased / lava-fronted
        // block the builder can't reach fails fast instead of freezing for the default 2s/5s. Quarry
        // targets are always near, so a reachable path is found well under 1s.
        pushBaritone(s, "failuretimeoutms", 1000L);
        pushBaritone(s, "planaheadfailuretimeoutms", 1000L);

        // Mine TOP-DOWN, always. Stock clearArea is nearest-first, which digs to the bottom of the box and
        // works upward. buildInLayers clears one Y-layer at a time; layerOrder=true makes that order top to
        // bottom (BuilderProcess: "if (layerOrder.value) { // top to bottom"). breakFromAbove lets the bot
        // stand on the layer and break the block directly below it (the reach loop starts at dy=-1), which
        // is what makes descending layer-by-layer actually work. All restored on deactivate.
        pushBaritone(s, "buildinlayers", true);
        pushBaritone(s, "layerorder", true);   // true = top to bottom
        pushBaritone(s, "layerheight", 1);     // one block per layer (full top-down sweep)
        pushBaritone(s, "breakfromabove", true);

        // Break gravel/sand instead of thrashing on it. Stock Baritone defaults avoidUpdatingFallingBlocks
        // = TRUE: it "will never break a block adjacent to an unsupported falling block" (to avoid cascading
        // sand/gravel falls). In a quarry every block in/around a gravel patch is adjacent to unsupported
        // gravel, so the builder refuses to commit and oscillates between candidate blocks forever, never
        // completing a break (the reported "stuck switching back and forth" on gravel). We WANT to clear it,
        // so turn that guard off. pauseMiningForFallingBlocks (held TRUE) keeps the bot patient: it breaks the
        // gravel, waits for the cascade to settle, then continues — no thrash. Both restored on deactivate.
        pushBaritone(s, "avoidupdatingfallingblocks", false);
        pushBaritone(s, "pauseminingforfallingblocks", true);

        // Cave handling: relax fall/jump limits so the bot drops into caverns to reach blocks. Lava is
        // never walked/fallen into regardless (those moves stay cost-infinity), and we force
        // assume-walk-on-lava off so a stray config can't make it lava-walk.
        if (caveHandling.get()) {
            pushBaritone(s, "maxfallheightnowater", maxFallHeight.get());
            pushBaritone(s, "allowparkour", allowParkour.get());
            pushBaritone(s, "allowparkourplace", allowParkourPlace.get());
            pushBaritone(s, "allowdiagonaldescend", allowDiagonalDescend.get());
            pushBaritone(s, "allowdiagonalascend", allowDiagonalAscend.get());
            pushBaritone(s, "assumewalkonlava", false);
            dbg("pushed cave settings: maxFall=%d parkour=%b parkourPlace=%b diagDesc=%b diagAsc=%b",
                maxFallHeight.get(), allowParkour.get(), allowParkourPlace.get(), allowDiagonalDescend.get(), allowDiagonalAscend.get());
        }
        dbg("pushed baritone (stock): allowbreak=true topDown=true(buildInLayers+layerOrder+breakFromAbove) breakGravel=true(avoidUpdatingFallingBlocks=false,pauseForSettle=true) failTO=1000ms caveHandling=%b floorY=%d", caveHandling.get(), minYLevel.get());
    }

    /**
     * Set a Baritone setting by lowercase name, snapshotting its current value the FIRST time we touch
     * it this run so {@link #restoreBaritone()} can put it back on deactivate (no leaking of our pushes
     * into the user's manual Baritone use). Driven by name so the addon stays decoupled from Baritone's
     * mappings.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void pushBaritone(Settings s, String lowerName, Object value) {
        Settings.Setting setting = s.byLowerName.get(lowerName);
        if (setting == null) {
            warning("Baritone setting '%s' not found.", lowerName);
            return;
        }
        if (!savedBaritone.containsKey(lowerName)) savedBaritone.put(lowerName, setting.value);
        setting.value = value;
    }

    /** Restore every Baritone setting we changed this run to its snapshotted original value. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void restoreBaritone() {
        if (savedBaritone.isEmpty()) return;
        Settings s = BaritoneAPI.getSettings();
        for (var e : savedBaritone.entrySet()) {
            Settings.Setting setting = s.byLowerName.get(e.getKey());
            if (setting != null) setting.value = e.getValue();
        }
        dbg("restored %d baritone setting(s) to their originals", savedBaritone.size());
        savedBaritone.clear();
    }

    // ---------------- Main loop ----------------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Defer to item use (eating/drinking): hold the addon's OWN actions while a bite is in
        // progress, so we never restart mining mid-bite. This covers the addon's storage/deposit
        // breaks — but NOT the Baritone quarry, which runs on its own handler and keeps forcing the
        // attack click; the hunger pause below releases the quarry so the bite can finish.
        if (mc.player.isUsingItem()) return;

        // CornerSelect: idle until both corners are marked (handled by the input events + render),
        // and don't let the hazard check pause us mid-selection.
        if (state == State.SELECT) return;

        if (timer > 0) { timer--; return; }

        // Pause for a hazard (any state) OR for low hunger (only while MINING — the quarry is the
        // one that fights AutoEat; pausing mid storage/deposit cycle would strand a placed chest).
        boolean hazard = pauseOnHazard.get() && isHazard();
        boolean hungry = hungerPauseActive() && (state == State.MINING || state == State.PAUSED);
        if (hazard || hungry) {
            if (state != State.PAUSED) {
                warning(hazard ? "Hazard detected — pausing." : "Hunger low — pausing so AutoEat can eat.");
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
            case RESTOCK      -> tickRestock();
            case DEPOSIT      -> tickDeposit();
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
            restocking = false;                                  // CLEAR_AREA routes to the storage cycle
            go(State.CLEAR_AREA);
            return;
        }

        // Tool-shulker restock: no fresh tool of the chosen type left anywhere, and an ender chest on
        // hand to crack open the tool-shulker. Reuses CLEAR_AREA for pocket prep, then runs the RESTOCK
        // FSM (which leaves the haul untouched — it only swaps a fresh tool in).
        if (restockFromShulker.get() && toolNeedsRestock() && countItem(Items.ENDER_CHEST) > 0) {
            stopMining();
            dbg("no fresh tool left — starting tool-shulker restock cycle");
            restocking = true;                                   // CLEAR_AREA routes to the restock FSM
            restockAbort = null;
            restockPhase = RestockPhase.PLACE_ECHEST;
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
        go(restocking ? State.RESTOCK : State.PLACE_ECHEST); // pocket cleared
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

    // ----- ECHEST_TAKE: open, restock pickaxe, stock carried empties, pull one empty shulker -----

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
                step = depositActive ? 3 : 4; // deposit mode: stock empties from a refill back into the echest first
                timer = nextDelay();
            }
            case 3 -> { // STOCK: push any carried empty shulkers INTO the echest (echest = the field supply)
                if (!isContainerOpen()) { pause("Ender chest closed unexpectedly."); return; }
                ScreenHandler h = mc.player.currentScreenHandler;
                int playerSlot = findPlayerSlotInContainer(h, this::isEmptyShulkerStack);
                if (playerSlot >= 0 && containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) {
                    quickMove(h, playerSlot);       // one carried empty into the echest, then loop
                    timer = fillMoveDelay();
                    return;
                }
                step = 4; timer = nextDelay();      // nothing more to stock (or echest full)
            }
            case 4 -> { // pull one empty shulker straight into the HOTBAR (so it can be placed), or finish
                if (!isContainerOpen()) { pause("Ender chest closed unexpectedly."); return; }
                ScreenHandler h = mc.player.currentScreenHandler;
                int shulkerSlot = findContainerSlot(h, this::isEmptyShulker);
                if (shulkerSlot >= 0) {
                    int free = freeHotbarSlot();
                    if (free != -1) swapToHotbar(h, shulkerSlot, free); // one packet, lands in hotbar
                    else quickMove(h, shulkerSlot);                     // fallback: at least pull it out
                    dbg("pulled empty shulker to hotbar slot %d", free);
                    step = 5;
                } else if (countShulkers(this::isEmptyShulkerInInv) > 0) {
                    dbg("echest empty but a carried empty remains — using it"); // echest was full, couldn't stock it
                    step = 5; // skip the pull; PLACE_SHULKER finds the carried empty
                } else { dbg("no empty shulkers left in ender chest"); step = 6; }
                timer = nextDelay();
            }
            case 5 -> { closeScreen(); go(State.PLACE_SHULKER); timer = nextDelay(); }
            default -> { // 6: no empty shulkers anywhere
                closeScreen();
                if (depositActive) {
                    // Out of empties with a full load of haul in hand: the proactive trip (detected in
                    // tickEchestStore, when the inventory was clear) should normally fire first, so this
                    // is the cold-start fallback (no empties were ever loaded). Can't pack the haul.
                    pause("Out of empty shulkers — none in the ender chest or inventory. "
                        + "Load empty shulkers (or stock a supply chest), then toggle off/on.");
                }
                else if (countShulkers(this::isFilledShulkerInInv) > 0) go(State.BREAK_SHULKER); // store what we have first
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
        // The filled shulker always goes back into the ender chest — the field buffer — whether or not
        // deposit chests are in use. In deposit mode it's later EXTRACTed from the echest for the haul;
        // it never sits in the working inventory during mining (that was the bug: a few cycles in, the
        // inventory was full of filled shulkers and couldn't collect any more drops).
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
                    if (depositActive) {
                        // Full of filled shulkers IS the trip trigger. Recover the echest with a clean
                        // inventory, then resumeMining() starts the deposit trip (so we never keep mining
                        // and pack the inventory with blocks before there's room to pull the shulkers out).
                        echestExhausted = true;
                        info("Ender chest is full of filled shulkers — heading out on a deposit trip.");
                        closeScreen();
                        go(State.BREAK_ECHEST); timer = nextDelay(); return;
                    }
                    closeScreen();
                    doneReason = "ender chest full of filled shulkers"; go(State.DONE); timer = nextDelay(); return;
                }
                quickMove(h, playerSlot);
                timer = fillMoveDelay();
            }
            default -> { // 3: done storing — detect echest exhaustion, then loop another shulker or recover
                ScreenHandler h = mc.player.currentScreenHandler;
                // Deposit mode: with the inventory now clear, if the echest holds no empty shulker (and we
                // aren't carrying one), it's exhausted — resumeMining() will start a deposit trip from this
                // clean state (so the EXTRACT leg has room to pull the filled shulkers back out).
                if (depositActive && isContainerOpen()
                        && findContainerSlot(h, this::isEmptyShulker) == -1
                        && countShulkers(this::isEmptyShulkerInInv) == 0) {
                    echestExhausted = true;
                    info("Used the last empty shulker — ender chest is full; making a deposit trip.");
                }
                closeScreen();
                if (!echestExhausted && countShulkers(this::isEmptyShulkerInInv) > 0 && hasTargetStacks()) go(State.PLACE_SHULKER);
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
        if (areaComplete) {                 // bounded area finished (this was the final storage cycle)
            // Deposit mode: deliver the last shulkers (stored in the echest) before stopping — the trip's
            // EXTRACT leg pulls them out; if the echest holds none it falls straight through to DONE.
            if (depositActive && !depositChestList().isEmpty()) {
                dbg("area complete -> final deposit trip");
                finishAfterDeposit = true;
                beginDepositTrip();
                return;
            }
            dbg("area complete after final storage -> DONE");
            doneReason = "custom area fully cleared";
            go(State.DONE);
            return;
        }
        // Deposit-chest mode: the ender chest has run out of empty shulkers (it's full of filled ones) ->
        // make a trip to haul the filled shulkers out to a deposit chest and refill empties.
        if (depositActive && echestExhausted) {
            echestExhausted = false;
            beginDepositTrip();
            return;
        }
        dbg("resume mining at chunk [%d,%d]", curCX, curCZ);
        go(State.MINING);
        startMining();
    }

    /** Storage is exhausted: stop the module, and (if 'auto-disconnect' is on) leave the server too. */
    private void finishStorage() {
        boolean disconnect = autoDisconnect.get();
        dbg("DONE (%s) -> %s", doneReason, disconnect ? "disconnect + toggle off" : "toggle off");
        info("Storage done: %s.%s", doneReason, disconnect ? " Disconnecting." : "");
        if (disconnect) disconnectFromServer("Foreman MassExtractor: " + doneReason);
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
        boolean hazard = pauseOnHazard.get() && isHazard();
        boolean hungry = hungerPauseActive();
        if (!hazard && !hungry) {
            info("Clear — resuming mining.");
            resumeMining();
        } else {
            timer = pauseRetryTicks.get();
        }
    }

    // ---------------- Deposit-chest trips (optional) ----------------
    // The ender chest is the FIELD buffer: during mining, filled shulkers are stored into it and empties
    // taken from it (exactly like the EnderChest cycle). A trip only happens once the echest runs out of
    // empties (it's full of filled shulkers) — detected the moment the last empty is used, with a clean
    // inventory, so the bot never keeps mining and packs blocks before there's room to extract. The trip
    // walks to the nearest DEPOSIT chest FIRST with an empty inventory (the filled shulkers stay safe in
    // the global echest until the bot is there — a death en route can't strand them), then at the chest
    // EXTRACTs the filled shulkers out of the echest (place echest -> open -> pull -> break) and dumps
    // them in. If refill-empties is on it then walks to the nearest SUPPLY chest — a separate chest — and
    // takes ONLY as many empties as will fit in the echest after the deposit, STOCKs those straight into
    // the echest, and heads back to mining. Every leg has a stuck-timeout that moves on to the next chest
    // or pauses, so a wrong/blocked/out-of-range chest can't hang the run.

    private boolean hasAnyEmptyShulker() { return countShulkers(this::isEmptyShulkerInInv) > 0; }

    /** Begin a deposit trip: walk to the nearest deposit chest FIRST — the filled shulkers stay in the
     *  ender chest (global) until the bot is standing at the chest, so a death en route can't strand them. */
    private void beginDepositTrip() {
        stopMining();
        triedChests.clear();
        depositTravelTicks = 0;
        lastTravelPos = null;
        tripExtracted = false;
        tripRefilled = false;
        echestFreeAfterExtract = 0;
        go(State.DEPOSIT);
        if (countItem(Items.ENDER_CHEST) == 0) { pause("No ender chest in inventory to open the shulker buffer."); return; }
        depositChest = nearestChest(depositChestList());
        if (depositChest == null) { pause(noChestMsg(depositChestList(), "deposit")); return; }
        setDepositPhase(DepositPhase.PATH_TO_DEPOSIT);
        info("Deposit trip: walking to the deposit chest at %d, %d, %d (filled shulkers stay in the ender chest until I'm there).",
            depositChest.getX(), depositChest.getY(), depositChest.getZ());
    }

    /** Empties to take this trip: capped to what will fit in the echest after the deposit (and empties-per-trip). */
    private int tripEmptiesTarget() {
        return Math.min(emptiesPerTrip.get(), echestFreeAfterExtract);
    }

    /** Go stock carried empties into the echest, or finish the trip if there are none. */
    private void gotoStockOrDone() {
        if (countShulkers(this::isEmptyShulkerInInv) > 0 && countItem(Items.ENDER_CHEST) > 0) {
            tripRefilled = true;                 // we obtained empties; they'll be stocked into the echest
            setDepositPhase(DepositPhase.STOCK_PLACE);
        } else setDepositPhase(DepositPhase.DONE);
    }

    /** After the filled shulkers are dumped: refill empties (if wanted and the echest has room), else stock/finish. */
    private void afterDeposit() {
        if (!finishAfterDeposit && refillEmpties.get()
                && tripEmptiesTarget() > 0
                && countShulkers(this::isEmptyShulkerInInv) < tripEmptiesTarget()) {
            startSupplyLeg();
        } else {
            gotoStockOrDone();
        }
        timer = nextDelay();
    }

    // ----- EXTRACT leg (standing at the deposit chest): place the echest, pull the filled loot shulkers out, break it -----

    private void tickPullPlace() {
        switch (tickPlace(s -> s.getItem() == Items.ENDER_CHEST)) {
            case DONE -> { placedEchest = pendingPlace; dbg("placed ender chest at %s (extract)", placedEchest.toShortString()); setDepositPhase(DepositPhase.PULL_OPEN); timer = nextDelay(); }
            case FAILED -> pause("Couldn't place the ender chest to collect filled shulkers.");
            case BUSY -> {}
        }
    }

    private void tickPullOpen() {
        if (step == 0) { openBlock(placedEchest); step = 1; timer = nextDelay(); }
        else if (isContainerOpen()) { setDepositPhase(DepositPhase.PULL_FILLED); timer = nextDelay(); }
        else if (++attempts >= 20) pause("Ender chest didn't open (collecting filled shulkers).");
        else timer = nextDelay();
    }

    /** Pull filled loot shulkers (NOT the tool-shulker) out of the open echest, one per tick. */
    private void tickPullFilled() {
        if (!isContainerOpen()) { pause("Ender chest closed unexpectedly (collecting filled shulkers)."); return; }
        ScreenHandler h = mc.player.currentScreenHandler;
        int slot = findContainerSlot(h, this::isLootFilledShulker);
        if (slot < 0 || (emptyMainSlots() == 0 && freeHotbarSlot() == -1)) {   // pulled them all (or inventory full, rare)
            echestFreeAfterExtract = echestFreeSlots(h);                        // record how many empties may be refilled
            setDepositPhase(DepositPhase.PULL_CLOSE); timer = nextDelay(); return;
        }
        quickMove(h, slot);                   // echest -> player inventory
        timer = fillMoveDelay();
    }

    private void tickPullClose() { closeScreen(); setDepositPhase(DepositPhase.PULL_BREAK); timer = nextDelay(); }

    private void tickPullBreak() {
        if (placedEchest != null && mc.world.getBlockState(placedEchest).getBlock() == Blocks.ENDER_CHEST) {
            BlockUtils.breakBlock(placedEchest, true); // continuous until it pops
            return;
        }
        placedEchest = null;
        tripExtracted = true;
        if (countShulkers(this::isLootFilledShulker) > 0) {
            setDepositPhase(DepositPhase.OPEN_DEPOSIT);  // open the deposit chest and dump them
            timer = nextDelay();
        } else {
            afterDeposit();                              // echest held no filled shulkers (edge) — skip the dump
        }
    }

    // ----- STOCK leg: put the refilled empty shulkers into the ender chest (so the echest stays the field supply) -----

    private void tickStockPlace() {
        switch (tickPlace(s -> s.getItem() == Items.ENDER_CHEST)) {
            case DONE -> { placedEchest = pendingPlace; dbg("placed ender chest at %s (stock)", placedEchest.toShortString()); setDepositPhase(DepositPhase.STOCK_OPEN); timer = nextDelay(); }
            case FAILED -> pause("Couldn't place the ender chest to stock empty shulkers.");
            case BUSY -> {}
        }
    }

    private void tickStockOpen() {
        if (step == 0) { openBlock(placedEchest); step = 1; timer = nextDelay(); }
        else if (isContainerOpen()) { setDepositPhase(DepositPhase.STOCK_EMPTIES); timer = nextDelay(); }
        else if (++attempts >= 20) pause("Ender chest didn't open (stocking empty shulkers).");
        else timer = nextDelay();
    }

    /** Push carried empty shulkers into the open echest, one per tick (they fit — the take was capped to the free space). */
    private void tickStockEmpties() {
        if (!isContainerOpen()) { pause("Ender chest closed unexpectedly (stocking empty shulkers)."); return; }
        ScreenHandler h = mc.player.currentScreenHandler;
        int playerSlot = findPlayerSlotInContainer(h, this::isEmptyShulkerStack);
        if (playerSlot < 0 || !containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) { // all stocked (or echest full)
            setDepositPhase(DepositPhase.STOCK_CLOSE); timer = nextDelay(); return;
        }
        quickMove(h, playerSlot);
        timer = fillMoveDelay();
    }

    private void tickStockClose() { closeScreen(); setDepositPhase(DepositPhase.STOCK_BREAK); timer = nextDelay(); }

    private void tickStockBreak() {
        if (placedEchest != null && mc.world.getBlockState(placedEchest).getBlock() == Blocks.ENDER_CHEST) {
            BlockUtils.breakBlock(placedEchest, true); // continuous until it pops
            return;
        }
        placedEchest = null;
        setDepositPhase(DepositPhase.DONE);
        timer = nextDelay();
    }

    private String noChestMsg(java.util.List<BlockPos> list, String kind) {
        return list.isEmpty()
            ? "No " + kind + " chests marked — aim at one and press the mark-" + kind + " bind."
            : "No " + kind + " chest within range.";
    }

    /** Advance to a deposit sub-phase, resetting the shared step/attempts counters. */
    private void setDepositPhase(DepositPhase p) {
        if (p != depositPhase) dbg("deposit %s -> %s", depositPhase, p);
        depositPhase = p;
        step = 0;
        attempts = 0;
    }

    private void tickDeposit() {
        switch (depositPhase) {
            case PATH_TO_DEPOSIT, PATH_TO_SUPPLY -> tickDepositTravel();
            case PULL_PLACE -> tickPullPlace();
            case PULL_OPEN -> tickPullOpen();
            case PULL_FILLED -> tickPullFilled();
            case PULL_CLOSE -> tickPullClose();
            case PULL_BREAK -> tickPullBreak();
            case OPEN_DEPOSIT, OPEN_SUPPLY -> tickDepositOpen();
            case DEPOSIT_FILLED -> tickDepositFilled();
            case TAKE_EMPTIES -> tickTakeEmpties();
            case STOCK_PLACE -> tickStockPlace();
            case STOCK_OPEN -> tickStockOpen();
            case STOCK_EMPTIES -> tickStockEmpties();
            case STOCK_CLOSE -> tickStockClose();
            case STOCK_BREAK -> tickStockBreak();
            case CLOSE_DEPOSIT -> { closeScreen(); afterDeposit(); }
            case CLOSE_SUPPLY -> { closeScreen(); gotoStockOrDone(); timer = nextDelay(); }
            default -> { // DONE: head back to mining (clearArea re-paths to the box). Guard against spinning.
                if (finishAfterDeposit) {           // bounded-area final trip: last haul delivered, stop now
                    finishAfterDeposit = false;
                    doneReason = "custom area fully cleared";
                    go(State.DONE);
                    return;
                }
                if (depositActive && !tripRefilled) {
                    // trip stocked no empties into the echest (supply empty / refill off) -> can't keep mining.
                    // Pause now rather than mine a full load of blocks first and get stuck with no empty to pack.
                    pause(refillEmpties.get()
                        ? "Out of empty shulkers — no supply chest had any."
                        : "Out of empty shulkers, and refill-from-chests is off.");
                    return;
                }
                dbg("deposit trip done — resuming mining");
                go(State.MINING);
                startMining();
            }
        }
    }

    /** Walk to depositChest (both legs); on arrival open it, on a stuck walk try the next chest. */
    private void tickDepositTravel() {
        boolean supplyLeg = depositPhase == DepositPhase.PATH_TO_SUPPLY;
        if (step == 0) {
            if (!pathToNear(depositChest, 2)) { pause("Couldn't start pathing to the chest."); return; }
            step = 1; lastTravelPos = null; depositTravelTicks = 0;
            return; // poll arrival each tick (no packets)
        }
        if (Math.sqrt(mc.player.getBlockPos().getSquaredDistance(depositChest)) <= 4.0) {
            baritone.getPathingBehavior().cancelEverything();
            if (supplyLeg) setDepositPhase(DepositPhase.OPEN_SUPPLY);
            // first time at a deposit chest: pull the filled shulkers out of the echest here; if we've
            // already extracted (moved on to another deposit chest because the first filled up), just open it.
            else setDepositPhase(tripExtracted ? DepositPhase.OPEN_DEPOSIT : DepositPhase.PULL_PLACE);
            timer = nextDelay();
            return;
        }
        BlockPos p = mc.player.getBlockPos();
        if (lastTravelPos == null || !p.equals(lastTravelPos)) { lastTravelPos = p; depositTravelTicks = 0; }
        else if (++depositTravelTicks > CLEARAREA_STALL_TICKS) {
            warning("Couldn't reach the %s chest (no movement %ds) — trying another.", supplyLeg ? "supply" : "deposit", CLEARAREA_STALL_TICKS / 20);
            baritone.getPathingBehavior().cancelEverything();
            tryNextChest(supplyLeg);
        }
    }

    /** Open depositChest; on failure try the next chest of this leg. */
    private void tickDepositOpen() {
        boolean supplyLeg = depositPhase == DepositPhase.OPEN_SUPPLY;
        if (step == 0) { openBlock(depositChest); step = 1; timer = nextDelay(); }
        else if (isContainerOpen()) { setDepositPhase(supplyLeg ? DepositPhase.TAKE_EMPTIES : DepositPhase.DEPOSIT_FILLED); timer = nextDelay(); }
        else if (++attempts >= 20) {
            warning("Couldn't open the %s chest — trying another.", supplyLeg ? "supply" : "deposit");
            tryNextChest(supplyLeg);
        } else timer = nextDelay();
    }

    /** Shift filled loot shulkers into the open deposit chest, one per tick; full -> next chest. */
    private void tickDepositFilled() {
        if (!isContainerOpen()) { pause("Deposit chest closed unexpectedly."); return; }
        ScreenHandler h = mc.player.currentScreenHandler;
        int playerSlot = findPlayerSlotInContainer(h, this::isLootFilledShulker);
        if (playerSlot < 0) { setDepositPhase(DepositPhase.CLOSE_DEPOSIT); timer = nextDelay(); return; } // all dropped off
        if (!containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) {                                 // this chest is full
            closeScreen();
            tryNextChest(false);
            return;
        }
        quickMove(h, playerSlot);
        timer = fillMoveDelay();
    }

    /** Pull empty shulkers out of the open supply chest, one per tick, up to what will fit in the echest. */
    private void tickTakeEmpties() {
        if (!isContainerOpen()) { pause("Supply chest closed unexpectedly."); return; }
        // only take as many empties as will fit in the (now mostly empty) ender chest after the deposit
        if (countShulkers(this::isEmptyShulkerInInv) >= tripEmptiesTarget()) { setDepositPhase(DepositPhase.CLOSE_SUPPLY); timer = nextDelay(); return; }
        if (emptyMainSlots() == 0 && freeHotbarSlot() == -1) { setDepositPhase(DepositPhase.CLOSE_SUPPLY); timer = nextDelay(); return; } // inventory full
        ScreenHandler h = mc.player.currentScreenHandler;
        int slot = findContainerSlot(h, this::isEmptyShulker);
        if (slot < 0) {                                                  // this supply chest is out of empties
            closeScreen();
            triedChests.add(depositChest);
            BlockPos next = nearestChest(supplyChestList());
            if (next == null) { gotoStockOrDone(); timer = nextDelay(); return; } // stock what we already grabbed
            depositChest = next; depositTravelTicks = 0; lastTravelPos = null;
            setDepositPhase(DepositPhase.PATH_TO_SUPPLY); timer = nextDelay();
            return;
        }
        quickMove(h, slot);                                              // container -> player inventory
        timer = fillMoveDelay();
    }

    /** Start the supply leg (refill empties) by routing to the nearest supply chest. */
    private void startSupplyLeg() {
        triedChests.clear();
        BlockPos s = nearestChest(supplyChestList());
        if (s == null) {
            // No reachable supply chest: stock any empties we already carry, else pause.
            if (hasAnyEmptyShulker()) gotoStockOrDone();
            else pause(noChestMsg(supplyChestList(), "supply"));
            return;
        }
        depositChest = s; depositTravelTicks = 0; lastTravelPos = null;
        setDepositPhase(DepositPhase.PATH_TO_SUPPLY);
    }

    /** A chest of the current leg was full/unreachable: try the next nearest, else finish or pause. */
    private void tryNextChest(boolean supplyLeg) {
        triedChests.add(depositChest);
        BlockPos next = nearestChest(supplyLeg ? supplyChestList() : depositChestList());
        if (next == null) {
            if (supplyLeg) {
                if (hasAnyEmptyShulker()) gotoStockOrDone();
                else pause("No reachable supply chest with empty shulkers.");
            } else {
                if (countShulkers(this::isLootFilledShulker) == 0) gotoStockOrDone();
                else pause("No reachable deposit chest with room.");
            }
            return;
        }
        depositChest = next; depositTravelTicks = 0; lastTravelPos = null;
        setDepositPhase(supplyLeg ? DepositPhase.PATH_TO_SUPPLY : DepositPhase.PATH_TO_DEPOSIT);
        timer = nextDelay();
    }

    /**
     * Path the bot to within {@code range} blocks of {@code pos} using Baritone's GoalNear. GoalNear's
     * constructor takes a BlockPos (an MC type the unmapped runtime Baritone names differently), so the
     * goal is built reflectively — the same reason {@link #callClearArea} is. The process call itself
     * uses Baritone API types directly (those resolve fine at runtime, like getBuilderProcess()).
     */
    private boolean pathToNear(BlockPos pos, int range) {
        try {
            Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
            Object goal = goalNear.getConstructor(BlockPos.class, int.class).newInstance(pos, range);
            baritone.getCustomGoalProcess().setGoalAndPath((baritone.api.pathing.goals.Goal) goal);
            return true;
        } catch (Exception e) {
            dbg("pathToNear reflection failed: %s", e.toString());
            return false;
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

    /**
     * Work out the mining bounds for this run, for the non-corner cases. Unbounded =>
     * areaLimited=false (infinite spiral from the activation chunk). ChunksFromStart => a
     * width x length chunk box centred on the activation chunk, Y from min-y-level up to the
     * activation level. CornerSelect resolves its own bounds in {@link #resolveAreaFromCorners()}.
     * Either way the quarry ceiling is captured from the player's current Y so the box never
     * chases the player downward.
     */
    private void resolveArea() {
        BlockPos fp = mc.player.getBlockPos();
        int startChunkCX = fp.getX() >> 4;
        int startChunkCZ = fp.getZ() >> 4;
        quarryTopY = fp.getY() + 1;

        if (limitArea.get() && areaMode.get() == AreaMode.ChunksFromStart) {
            areaLimited = true;
            int w = areaWidthChunks.get(), l = areaLengthChunks.get();
            if (areaAnchor.get() == AreaAnchor.Corner) {
                // Activation chunk is a corner; extend the box in the player's horizontal facing.
                int[] dir = facingExtend();
                if (dir[0] >= 0) { gridCxMin = startChunkCX; gridCxMax = startChunkCX + w - 1; }
                else             { gridCxMax = startChunkCX; gridCxMin = startChunkCX - (w - 1); }
                if (dir[1] >= 0) { gridCzMin = startChunkCZ; gridCzMax = startChunkCZ + l - 1; }
                else             { gridCzMax = startChunkCZ; gridCzMin = startChunkCZ - (l - 1); }
            } else { // Center: activation chunk in the middle of the box
                gridCxMin = startChunkCX - (w - 1) / 2; gridCxMax = gridCxMin + w - 1;
                gridCzMin = startChunkCZ - (l - 1) / 2; gridCzMax = gridCzMin + l - 1;
            }
            areaMinX = gridCxMin << 4; areaMaxX = (gridCxMax << 4) + 15;
            areaMinZ = gridCzMin << 4; areaMaxZ = (gridCzMax << 4) + 15;
            areaMinY = minYLevel.get(); areaMaxY = quarryTopY;
            startCX = (gridCxMin + gridCxMax) >> 1; // spiral centre = grid centre (Baritone paths there)
            startCZ = (gridCzMin + gridCzMax) >> 1;
        } else {
            areaLimited = false;
            areaMinY = minYLevel.get(); areaMaxY = quarryTopY; // for the debug block counter only
            startCX = startChunkCX; startCZ = startChunkCZ;
        }
        dbg("resolveArea: limited=%b mode=%s Y[%d..%d] spiralCenter[%d,%d]%s",
            areaLimited, areaMode.get(), areaMinY, areaMaxY, startCX, startCZ,
            areaLimited ? String.format(" grid x[%d..%d] z[%d..%d]", gridCxMin, gridCxMax, gridCzMin, gridCzMax) : "");
    }

    /** CornerSelect: turn the two marked corners into the bounded box + spiral centre. */
    private void resolveAreaFromCorners() {
        areaLimited = true;
        areaMinX = Math.min(corner1.getX(), corner2.getX());
        areaMaxX = Math.max(corner1.getX(), corner2.getX());
        areaMinZ = Math.min(corner1.getZ(), corner2.getZ());
        areaMaxZ = Math.max(corner1.getZ(), corner2.getZ());
        int yLo = Math.min(corner1.getY(), corner2.getY());
        int yHi = Math.max(corner1.getY(), corner2.getY());
        areaMinY = Math.max(yLo, minYLevel.get()); // never below the bedrock-floor guard
        areaMaxY = yHi;
        gridCxMin = areaMinX >> 4; gridCxMax = areaMaxX >> 4;
        gridCzMin = areaMinZ >> 4; gridCzMax = areaMaxZ >> 4;
        startCX = (gridCxMin + gridCxMax) >> 1; // spiral centre = middle chunk of the grid
        startCZ = (gridCzMin + gridCzMax) >> 1;
        dbg("resolveAreaFromCorners: box x[%d..%d] y[%d..%d] z[%d..%d] grid x[%d..%d] z[%d..%d] center[%d,%d]",
            areaMinX, areaMaxX, areaMinY, areaMaxY, areaMinZ, areaMaxZ, gridCxMin, gridCxMax, gridCzMin, gridCzMax, startCX, startCZ);
    }

    /**
     * Reset the outward-square spiral stepper to the centre (startCX/startCZ) and clear the per-pass
     * clear-area cursor. Called at the start of the run AND at the start of each new horizontal layer,
     * so every layer re-sweeps the whole area's chunks from the centre out.
     */
    private void resetSpiralStepper() {
        spX = 0; spZ = 0; spDir = 0; spSegLen = 1; spSegLeft = 1; spSegDone = 0;
        curCX = startCX; curCZ = startCZ;
        subBoxes.clear();
        subBoxIdx = 0;
        areaChunksDone = 0;
    }

    /** Seed the whole run: reset the spiral, the stall watch, and start at the TOP layer (descends down). */
    private void seedSpiral() {
        resetSpiralStepper();
        clearAreaStarted = false;
        clearAreaStallTicks = 0;
        lastClearPos = null;
        areaComplete = false;
        curLayerTopY = areaMaxY;               // bounded: sweep from the top layer downward
        areaChunksTotal = areaLimited
            ? (gridCxMax - gridCxMin + 1) * (gridCzMax - gridCzMin + 1)
            : 0;
        dbg("seedSpiral: center[%d,%d] areaChunksTotal=%d topLayerY=%d layerH=%d", startCX, startCZ, areaChunksTotal, curLayerTopY, layerThickness());
    }

    /** Vertical thickness of each top-down horizontal layer (bounded areas), clamped to >= 1. */
    private int layerThickness() { return Math.max(1, areaLayerHeight.get()); }

    /** Y floor of the layer currently being swept (bounded), clamped to the area floor. */
    private int curLayerBottomY() { return Math.max(areaMinY, curLayerTopY - layerThickness() + 1); }

    /**
     * Sign (+1/-1) of the player's horizontal facing on each axis {x, z}, used by the Corner anchor to
     * extend the box in the direction the player is looking. Derived from yaw only (ignores pitch, so
     * looking up/down doesn't collapse the direction). East = +X, South = +Z.
     */
    private int[] facingExtend() {
        double yaw = Math.toRadians(mc.player.getYaw());
        double lx = -Math.sin(yaw);
        double lz = Math.cos(yaw);
        return new int[] { lx >= 0 ? 1 : -1, lz >= 0 ? 1 : -1 };
    }

    // ----- ClearArea engine: stock BuilderProcess quarry of one chunk box at a time -----

    /**
     * Issue a clearArea for the current spiral chunk. Unbounded: the whole chunk box, Y floor..quarry
     * ceiling. Bounded: the chunk box CLAMPED to the area bounds, so an edge chunk never breaks a block
     * outside your box (and Y spans the area's own floor..ceiling).
     */
    private void startClearArea() {
        int bx = curCX << 4, bz = curCZ << 4;
        int x1 = bx, x2 = bx + 15, z1 = bz, z2 = bz + 15;
        if (areaLimited) {
            x1 = Math.max(x1, areaMinX); x2 = Math.min(x2, areaMaxX);
            z1 = Math.max(z1, areaMinZ); z2 = Math.min(z2, areaMaxZ);
        }
        buildSubBoxes(x1, z1, x2, z2);
        subBoxIdx = 0;
        issueSubBox();
        clearAreaStarted = true;
    }

    /** Divide a chunk's clamped XZ footprint into clear-box-size × clear-box-size cells (row-major). */
    private void buildSubBoxes(int x1, int z1, int x2, int z2) {
        subBoxes.clear();
        int s = Math.max(1, clearBoxSize.get());
        for (int sx = x1; sx <= x2; sx += s) {
            for (int sz = z1; sz <= z2; sz += s) {
                subBoxes.add(new int[]{ sx, sz, Math.min(sx + s - 1, x2), Math.min(sz + s - 1, z2) });
            }
        }
        if (subBoxes.isEmpty()) subBoxes.add(new int[]{ x1, z1, x2, z2 }); // safety (degenerate bounds)
    }

    /**
     * Issue clearArea for the current sub-box, and reset the stall watch. Bounded areas mine ONE
     * horizontal layer at a time (the [curLayerBottomY..curLayerTopY] slice), so the whole area's top
     * is swept before descending — the unbounded spiral still clears the full Y band per chunk.
     */
    private void issueSubBox() {
        int y1 = areaLimited ? curLayerBottomY() : minYLevel.get();
        int y2 = areaLimited ? curLayerTopY      : quarryTopY;
        int[] b = subBoxes.get(subBoxIdx);
        callClearArea(new BlockPos(b[0], y1, b[1]), new BlockPos(b[2], y2, b[3]));
        clearAreaStallTicks = 0;
        lastClearPos = mc.player.getBlockPos();
        dbg("clearArea chunk [%d,%d] sub-box %d/%d x[%d..%d] z[%d..%d] y[%d..%d]",
            curCX, curCZ, subBoxIdx + 1, subBoxes.size(), b[0], b[2], b[1], b[3], y1, y2);
    }

    /**
     * Drive the ClearArea engine: when the builder finishes a chunk box it goes inactive — advance
     * the spiral and start the next. While it's working, watch for a genuine stall (bot hasn't moved
     * for a while = an unreachable pocket) and skip the chunk so it can never hang.
     */
    private void tickClearAreaMining() {
        if (!baritone.getBuilderProcess().isActive()) {
            if (clearAreaStarted) {
                afterSubBox(false);           // sub-box cleared; next sub-box, or finish the chunk
            } else {
                startClearArea();             // very first chunk of the run
                timer = nextDelay();
            }
            return;
        }
        // builder is working — detect a true stall via lack of movement (not just slow progress)
        BlockPos pos = mc.player.getBlockPos();
        if (lastClearPos == null || !pos.equals(lastClearPos)) {
            lastClearPos = pos;
            clearAreaStallTicks = 0;
        } else if (++clearAreaStallTicks > CLEARAREA_STALL_TICKS) {
            warning("clearArea stalled on chunk [%d, %d] sub-box %d/%d (no movement %ds) — skipping.",
                curCX, curCZ, subBoxIdx + 1, subBoxes.size(), CLEARAREA_STALL_TICKS / 20);
            dbg("clearArea stall: skipping chunk [%d,%d] sub-box %d", curCX, curCZ, subBoxIdx + 1);
            baritone.getBuilderProcess().onLostControl();
            afterSubBox(true);
        }
    }

    /**
     * A sub-box finished (cleared) or was abandoned (stalled). Move to the next sub-box in this chunk,
     * or — once the chunk's last sub-box is done — hand off to {@link #afterChunk(boolean)} to count the
     * chunk and advance the spiral.
     */
    private void afterSubBox(boolean stalled) {
        if (subBoxIdx + 1 < subBoxes.size()) {
            subBoxIdx++;
            issueSubBox();
            timer = nextDelay();
        } else {
            afterChunk(stalled);
        }
    }

    /**
     * The current chunk box is finished (cleared) or abandoned (stalled). For a bounded area, count it
     * toward the CURRENT LAYER; once every chunk in the layer is done, drop one layer and re-sweep the
     * whole area (or finish the run once we've cleared down to areaMinY). For the infinite spiral it just
     * advances to the next chunk (full-height). Returns true if it routed to area-complete.
     */
    private boolean afterChunk(boolean stalled) {
        if (debugLog.get()) {
            int[] c = countMineBlocksInChunk(curCX, curCZ);
            dbg("chunk [%d,%d] done (%s): %d target block(s) left in box, %d exposed",
                curCX, curCZ, stalled ? "stalled" : "cleared", c[0], c[1]);
        }
        if (areaLimited && ++areaChunksDone >= areaChunksTotal) {
            // Whole-area horizontal layer cleared. Drop to the next layer and re-sweep, or — once the
            // next layer would start below the area floor — the area is fully mined.
            if (curLayerBottomY() <= areaMinY) {
                onAreaComplete();
                return true;
            }
            curLayerTopY -= layerThickness();
            resetSpiralStepper();                              // re-sweep every chunk at the new, lower layer
            info("Layer cleared — dropping to y[%d..%d] and sweeping the area.", curLayerBottomY(), curLayerTopY);
            startClearArea();
            timer = nextDelay();
            return false;
        }
        advanceSpiral();
        info(stalled ? "Skipped chunk — moving to chunk [%d, %d]." : "Chunk cleared — moving to chunk [%d, %d].", curCX, curCZ);
        startClearArea();
        timer = nextDelay();
        return false;
    }

    /** Every in-box chunk has been cleared/skipped: store any remaining yield, then finish the run. */
    private void onAreaComplete() {
        areaComplete = true;
        stopMining();
        dbg("custom area fully cleared (%d chunks)", areaChunksTotal);
        if (hasTargetStacks() && countItem(Items.ENDER_CHEST) > 0) {
            info("Area cleared — storing the last of the haul.");
            go(State.CLEAR_AREA);             // final storage cycle; resumeMining() then routes to DONE
        } else {
            doneReason = "custom area fully cleared";
            go(State.DONE);
        }
    }

    private boolean chunkInGrid(int cx, int cz) {
        return cx >= gridCxMin && cx <= gridCxMax && cz >= gridCzMin && cz <= gridCzMax;
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

    /**
     * Step the spiral one chunk outward; the next clearArea() box is built from curCX/curCZ. When the
     * area is bounded, keep stepping past any chunk outside the grid so only in-box chunks are mined
     * (a guard cap stops a runaway if the grid math is ever off). The caller only advances while
     * in-box chunks remain, so an in-grid chunk is always found.
     */
    private void advanceSpiral() {
        if (areaLimited) {
            int guard = 0;
            do { stepSpiral(); } while (!chunkInGrid(startCX + spX, startCZ + spZ) && ++guard < 100000);
        } else {
            stepSpiral();
        }
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

    /** A FILLED loot shulker (has contents) that is NOT the tool-shulker — i.e. mined haul to haul off. */
    private boolean isLootFilledShulker(ItemStack s) { return isFilledShulkerStack(s) && !isToolBearingShulker(s); }

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

    /**
     * Top up every tool type we keep stocked from the OPEN ender chest: the main restock-tool, plus a
     * shovel when 'also-restock-shovel' is on. Best-effort — a missing secondary tool only logs, it never
     * pauses the run (the dedicated tool-shulker cycle still guards the PRIMARY tool).
     */
    private void restockTool(ScreenHandler h) {
        boolean primary = true;
        for (ToolType t : restockToolTypes()) { restockOneTool(h, t, primary); primary = false; }
    }

    /** Pull one fresh tool of type {@code t} from the open chest if we don't already hold a fresh one. */
    private void restockOneTool(ScreenHandler h, ToolType t, boolean primary) {
        if (hasFreshTool(t)) return;             // a fresh spare is already in reach — AutoTool will swap to it
        dbg("restock check: no fresh %s on hand -> looking in ender chest", t);
        // Match the tool type specifically (by item type, so custom names/enchants don't matter) — a
        // named axe/sword in the chest can't be grabbed by mistake.
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
        } else if (primary) {
            dbg("no fresh %s found in ender chest", t);
            warning("No fresh %s in ender chest.", t.name().toLowerCase());
        } else {
            dbg("no fresh %s (secondary) in ender chest — skipping, mining continues", t);
        }
    }

    /** Tool types kept stocked from the ender chest: the main restock-tool, plus a shovel if 'also-restock-shovel' is on (deduped). */
    private java.util.List<ToolType> restockToolTypes() {
        ToolType primary = toolType.get() == ToolType.Auto ? autoToolType() : toolType.get();
        java.util.List<ToolType> out = new java.util.ArrayList<>(2);
        out.add(primary);
        if (alsoRestockShovel.get() && primary != ToolType.Shovel) out.add(ToolType.Shovel);
        return out;
    }

    /** True if the inventory already holds a fresh (durable, type-matched) tool of this type. */
    private boolean hasFreshTool(ToolType t) {
        for (int i = 0; i < 36; i++) if (isFreshTool(mc.player.getInventory().getStack(i), t)) return true;
        return false;
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

    // ---------------- Tool-shulker restock cycle ----------------
    // A self-contained mini state-machine (entered as State.RESTOCK after CLEAR_AREA opens a pocket)
    // that cracks a TOOL-SHULKER stored in the ender chest to refill a fresh tool, then puts the
    // shulker back. Every world/container action is on its own tick + action-delay, exactly like the
    // storage cycle, so it never bursts packets. Because we match the tool-shulker by its CONTENTS
    // (a shulker holding a fresh tool of the chosen type), loot shulkers are never touched and the
    // tool-shulker needs no special colour or name. Aborts (no tool-shulker found, etc.) still recover
    // the placed ender chest before pausing, so nothing is left in the world.

    /** Advance to a restock sub-phase, resetting the shared step/attempts counters. */
    private void setRestockPhase(RestockPhase p) {
        if (p != restockPhase) dbg("restock %s -> %s", restockPhase, p);
        restockPhase = p;
        step = 0;
        attempts = 0;
    }

    private void tickRestock() {
        final ToolType t = toolType.get() == ToolType.Auto ? autoToolType() : toolType.get();
        switch (restockPhase) {
            case PLACE_ECHEST -> {
                switch (tickPlace(s -> s.getItem() == Items.ENDER_CHEST)) {
                    case DONE -> { placedEchest = pendingPlace; dbg("restock: placed echest at %s", placedEchest.toShortString()); setRestockPhase(RestockPhase.OPEN_ECHEST); timer = nextDelay(); }
                    case FAILED -> pause("Restock: couldn't place ender chest.");
                    case BUSY -> {}
                }
            }
            case OPEN_ECHEST -> {
                if (step == 0) { openBlock(placedEchest); step = 1; timer = nextDelay(); }
                else if (isContainerOpen()) { setRestockPhase(RestockPhase.TAKE_SHULKER); timer = nextDelay(); }
                else if (++attempts >= 20) pause("Restock: ender chest didn't open.");
                else timer = nextDelay();
            }
            case TAKE_SHULKER -> {
                if (!isContainerOpen()) { pause("Restock: ender chest closed unexpectedly."); return; }
                ScreenHandler h = mc.player.currentScreenHandler;
                int slot = findContainerSlot(h, this::isToolShulker);
                if (slot < 0) { // no tool-shulker in the chest — recover the chest, then pause
                    closeScreen();
                    restockAbort = "Restock: no tool-shulker (holding a fresh " + t.name().toLowerCase() + ") in the ender chest.";
                    setRestockPhase(RestockPhase.BREAK_ECHEST);
                    timer = nextDelay();
                    return;
                }
                int free = freeHotbarSlot();
                if (free != -1) swapToHotbar(h, slot, free); else quickMove(h, slot);
                dbg("restock: pulled tool-shulker to hotbar slot %d", free);
                setRestockPhase(RestockPhase.CLOSE_ECHEST);
                timer = nextDelay();
            }
            case CLOSE_ECHEST -> { closeScreen(); setRestockPhase(RestockPhase.PLACE_SHULKER); timer = nextDelay(); }
            case PLACE_SHULKER -> {
                switch (tickPlace(this::isToolShulker)) {
                    case DONE -> { placedShulker = pendingPlace; dbg("restock: placed tool-shulker at %s", placedShulker.toShortString()); setRestockPhase(RestockPhase.OPEN_SHULKER); timer = nextDelay(); }
                    case FAILED -> pause("Restock: couldn't place the tool-shulker.");
                    case BUSY -> {}
                }
            }
            case OPEN_SHULKER -> {
                if (step == 0) { openBlock(placedShulker); step = 1; timer = nextDelay(); }
                else if (isContainerOpen()) { setRestockPhase(RestockPhase.TAKE_TOOL); timer = nextDelay(); }
                else if (++attempts >= 20) pause("Restock: tool-shulker didn't open.");
                else timer = nextDelay();
            }
            case TAKE_TOOL -> {
                if (!isContainerOpen()) { pause("Restock: tool-shulker closed unexpectedly."); return; }
                ScreenHandler h = mc.player.currentScreenHandler;
                int slot = findContainerSlot(h, s -> isFreshTool(s, t));
                if (slot >= 0) {
                    int free = freeHotbarSlot();
                    if (free != -1) swapToHotbar(h, slot, free); else quickMove(h, slot);
                    dbg("restock: took fresh %s into hotbar slot %d", t, free);
                    info("Restocked %s from shulker.", t.name().toLowerCase());
                } else {
                    dbg("restock: no fresh %s left inside the tool-shulker", t); // still recover/return it
                }
                setRestockPhase(RestockPhase.CLOSE_SHULKER);
                timer = nextDelay();
            }
            case CLOSE_SHULKER -> { closeScreen(); setRestockPhase(RestockPhase.BREAK_SHULKER); timer = nextDelay(); }
            case BREAK_SHULKER -> {
                if (placedShulker != null && mc.world.getBlockState(placedShulker).getBlock() instanceof ShulkerBoxBlock) {
                    BlockUtils.breakBlock(placedShulker, true); // continuous until it pops (drops with its remaining tools)
                    return; // breaking must run every tick — no action-delay here
                }
                placedShulker = null;
                setRestockPhase(RestockPhase.REOPEN_ECHEST);
                timer = nextDelay();
            }
            case REOPEN_ECHEST -> {
                if (step == 0) { openBlock(placedEchest); step = 1; timer = nextDelay(); }
                else if (isContainerOpen()) { setRestockPhase(RestockPhase.RETURN_SHULKER); timer = nextDelay(); }
                else if (++attempts >= 20) pause("Restock: ender chest didn't reopen.");
                else timer = nextDelay();
            }
            case RETURN_SHULKER -> {
                if (!isContainerOpen()) { pause("Restock: ender chest closed unexpectedly."); return; }
                ScreenHandler h = mc.player.currentScreenHandler;
                int playerSlot = findPlayerSlotInContainer(h, this::isToolBearingShulker);
                if (playerSlot >= 0 && containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) {
                    quickMove(h, playerSlot);
                    timer = fillMoveDelay();
                    return; // loop: store another tool-bearing shulker if we picked up more than one
                }
                setRestockPhase(RestockPhase.CLOSE_ECHEST2);
                timer = nextDelay();
            }
            case CLOSE_ECHEST2 -> { closeScreen(); setRestockPhase(RestockPhase.BREAK_ECHEST); timer = nextDelay(); }
            case BREAK_ECHEST -> {
                if (placedEchest != null && mc.world.getBlockState(placedEchest).getBlock() == Blocks.ENDER_CHEST) {
                    BlockUtils.breakBlock(placedEchest, true); // continuous until it pops
                    return; // breaking must run every tick — no action-delay here
                }
                placedEchest = null;
                setRestockPhase(RestockPhase.DONE);
                timer = nextDelay();
            }
            default -> { // DONE
                restocking = false;
                if (restockAbort != null) { String m = restockAbort; restockAbort = null; pause(m); }
                else { dbg("restock cycle complete — resuming mining"); resumeMining(); }
            }
        }
    }

    /** True when there is NO fresh tool of the restock type anywhere in the inventory — time to restock. */
    private boolean toolNeedsRestock() {
        final ToolType t = toolType.get() == ToolType.Auto ? autoToolType() : toolType.get();
        for (int i = 0; i < 36; i++) {
            if (isFreshTool(mc.player.getInventory().getStack(i), t)) return false;
        }
        return true;
    }

    /** A usable spare: right tool type, durability at/above the threshold, and (optionally) not Silk Touch. */
    private boolean isFreshTool(ItemStack s, ToolType t) {
        if (s.isEmpty() || !isToolOfType(s, t)) return false;
        if ((s.getMaxDamage() - s.getDamage()) < restockDurability.get()) return false;
        return !reserveSilk.get() || !Utils.hasEnchantments(s, Enchantments.SILK_TOUCH);
    }

    /** A shulker that holds at least one FRESH tool of the restock type (the one we pull a tool from). */
    private boolean isToolShulker(ItemStack s) { return shulkerHoldsTool(s, true); }

    /** A shulker that holds any tool of the restock type, fresh or worn (the one we put back). */
    private boolean isToolBearingShulker(ItemStack s) { return shulkerHoldsTool(s, false); }

    private boolean shulkerHoldsTool(ItemStack s, boolean requireFresh) {
        if (!isShulker(s)) return false;
        var c = s.get(DataComponentTypes.CONTAINER);
        if (c == null) return false;
        final ToolType t = toolType.get() == ToolType.Auto ? autoToolType() : toolType.get();
        return c.stream().anyMatch(inner -> requireFresh ? isFreshTool(inner, t) : isToolOfType(inner, t));
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

    /** Count the empty slots in the container half (used to size a refill to the echest's free space). */
    private int echestFreeSlots(ScreenHandler h) {
        int containerSlots = h.slots.size() - 36;
        int free = 0;
        for (int i = 0; i < containerSlots; i++) if (h.slots.get(i).getStack().isEmpty()) free++;
        return free;
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

    /**
     * Hunger pause: while MINING, Baritone's quarry re-selects the pickaxe and forces an attack click
     * every tick, which cancels any bite AutoEat starts — so the bot loops eating/breaking and never
     * refills. When food dips to the threshold we release the quarry (caller pauses) so the eat
     * completes, and hold until we're full again (hysteresis, so we don't flap on every single bite).
     * No-op when there's nothing edible to eat — pausing then would just stall the run.
     */
    private boolean hungerPauseActive() {
        if (!pauseToEat.get()) { wantToEat = false; return false; }
        int food = mc.player.getHungerManager().getFoodLevel();
        if (wantToEat) {
            if (food >= 20) wantToEat = false;                       // fully fed -> resume
        } else if (food <= eatBelowHunger.get() && hasEdibleFood()) {
            wantToEat = true;                                        // dipped to threshold, food on hand -> pause
        }
        return wantToEat;
    }

    /** True if the inventory holds any edible food (so a hunger pause can actually accomplish a refill). */
    private boolean hasEdibleFood() {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.contains(DataComponentTypes.FOOD)) return true;
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
        if (state == State.PAUSED && wantToEat) return "paused (eating)";
        if (state == State.SELECT) return "select " + (corner1 == null ? "corner 1" : "corner 2");
        if (state == State.RESTOCK) return "restock " + restockPhase.name().toLowerCase();
        if (state == State.DEPOSIT) return "deposit " + depositPhase.name().toLowerCase();
        if (areaLimited && state == State.MINING)
            return String.format("mining y%d..%d  chunk %d/%d", curLayerBottomY(), curLayerTopY, areaChunksDone, areaChunksTotal);
        return state.name();
    }
}
