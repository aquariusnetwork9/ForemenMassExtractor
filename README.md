# Foreman MassExtractor

An AFK bulk miner for Minecraft 1.21.4, built as a [Meteor Client](https://meteorclient.com/) addon and aimed at anarchy servers (2b2t, 6b6t, and the like). By Shallowplague.

You point it at an area, walk away, and come back to a stack of full shulkers. It digs out the blocks, packs the haul into shulkers inside an ender chest, swaps in a fresh pickaxe when the old one wears down, and keeps going until the area is done or your storage is full.

## What it does

- Mines a whole area, not one block at a time. It clears the ground in chunk-sized boxes and works outward, so it won't run off chasing a block on the far side of the map.
- Mines **top-down**, always. For a set area it clears the **top layer across the whole area first**, then drops a layer and sweeps again, down to your floor — so it never tunnels one spot to bedrock while the rest sits untouched.
- Actually collects the drops. It digs in small boxes and walks back over the ground it just cleared, and then does a **vacuum pass** — once a box is clear it walks over every block still lying on the floor and picks it up before moving on, so nothing is left to despawn.
- Mines gravel and sand without getting stuck on them, and (optionally) keeps a **shovel** on hand so they go fast.
- Stores everything for you. Fills shulkers inside an ender chest, then gets back to mining.
- Or hauls to a base (optional). Once the ender chest is full of filled shulkers, it walks to the nearest base chest (haul stays safe in the ender chest until it arrives), unloads the filled shulkers there, restocks empty shulkers from a separate supply chest, and heads back — a near-unlimited run.
- Refuels its own tools. Grabs a fresh pickaxe when the old one wears down — either loose ones in the ender chest, or, if you turn it on, from a whole shulker of spare pickaxes kept in the chest (it places that shulker, takes a tool, and puts it back).
- Throws out the trash. Junk blocks and risky foods (rotten flesh, stews, poison/teleport foods) get dropped; normal food is kept so AutoEat can use it.
- Handles caves. It will drop down into caverns to reach the blocks instead of getting stuck on the edge.
- Knows when to quit. When there are no empty shulkers left, or a set area is fully cleared, it stops — and can log you off the server if you want.

It runs on any Baritone, including the one bundled with Meteor. No special build required.

## What to bring

Before you turn it on, have these on you:

- A pickaxe (it mines deepslate by default), plus a few spares in the ender chest — either loose, or in a shulker if you turn on **restock-from-shulker** (lets one chest slot hold a whole shulker of pickaxes).
- A few spare **shovels** loose in the ender chest if you're clearing gravel/sand (with **also-restock-shovel** on by default, it keeps one on hand so falling blocks go fast).
- One or more ender chests in your inventory.
- Empty shulker boxes inside the ender chest.
- Optional: food for AutoEat, and totems if you're running near caves or lava.

## How to turn it on

1. Stand where you want to start mining.
2. Open the Meteor menu and find **Mass Extractor** under the **Foreman** category (or bind it to a key).
3. Click it on. That's it — it starts mining straight away.

To stop, click it off. Anything it changed in Baritone gets put back the way you had it.

## Setting a mining area

By default it mines outward forever from where you started. If you'd rather it stay in one spot, turn on **limit-area** and pick how you want to mark the box:

**ChunksFromStart** — set the size in chunks and where you stand decides the rest. No clicking.
- `area-anchor = Center`: the box is centered on you. Good for "clear everything around me."
- `area-anchor = Corner`: you're standing at a corner and the box grows out in the direction you're facing. Set the width and length the same for a square — for example 5×5 chunks mines an 80×80 block square in front of you.

**CornerSelect** — mark the two corners by hand, like Meteor's Excavator.
1. Turn the module on (it waits for you to mark the area).
2. Look at one corner block and press the selection key (right mouse by default).
3. Look at the opposite corner and press it again.
The box shows up as you aim, and mining starts as soon as both corners are set.

A set area is mined in **horizontal layers, top-down**: the bot clears the top slice corner-to-corner across the whole area, then drops to the next slice, repeating to your floor. Set how thick each slice is with **layer-height** (1 = peel one block-level at a time — most even, most walking; larger = fewer full-area passes but it digs that many blocks deep at each spot first). The infinite outward spiral still clears full-height per chunk (there's no "top of an infinite area" to sweep first).

When a set area is fully cleared, it packs up whatever it's still holding and stops.

## Cave handling

Deepslate depth is full of caves. Normally Baritone refuses to drop down to reach blocks across a cavern and either takes the long way around or gives up. With **cave-handling** on (the default), it's allowed to fall into caves to mine them out. It will never walk or fall into lava, and it puts your Baritone movement settings back to normal when you switch the module off.

If you've got good armor and totems you can push the fall height higher and turn on parkour for even more reach. If you'd rather play it safe, lower the fall height or turn cave handling off entirely.

## Hauling to base chests

In every mode the **ender chest is the field buffer**: empty shulkers are pulled out of it, filled, and the filled shulkers stored straight back into it — they never pile up in the working inventory while mining. By default the run just ends when the ender chest is full. If you'd rather pile the haul up at a base for an unlimited run, set **deposit-target** (in the **Deposit chests** group):

- **EnderChest** — the default. Filled shulkers stay in your ender chest; the run ends when it's full. Best for "just fill a few shulkers."
- **DepositChests** — same field cycle, but once the ender chest runs out of empties (it's full of filled shulkers) the bot makes a base trip. **EnderChestThenChests** — same as DepositChests (kept for compatibility).

The trip is ordered so the haul is never at risk on an anarchy server:

1. The instant the bot uses its **last empty shulker** (and packs it), the trip starts — it never keeps mining and fills up with blocks first.
2. It walks to the nearest **deposit chest** with an **empty inventory** — the filled shulkers stay in the ender chest (global) the whole way, so dying en route can't strand them.
3. **At** the deposit chest it pulls the filled shulkers out of the ender chest and drops them in.
4. It walks to a separate **supply chest** and takes **only as many empty shulkers as will fit** back in the ender chest.
5. It puts those empties straight **into the ender chest** and heads back to mining.

Set the locations in-game: aim at a chest and press **mark-deposit** (default K) for a drop-off chest, or **mark-supply** (default L) for an empty-shulker chest. Mark as many as you want — the bot uses the nearest and skips any that are full or unreachable. Locations are saved between runs.

Good to know:
- Keep your **deposit** chests (filled shulkers) and **supply** chests (spare empty shulkers) as separate chests.
- The **batch size per trip** is just how many empty shulkers you keep stocked in the ender chest — load it with the number you want filled before each trip (and **empties-per-trip** caps how many it refills, never more than fit in the echest).
- You still carry an ender chest item — it dispenses the empties, holds the filled shulkers between trips, and restocks tools.
- **max-deposit-distance** caps how far it will travel for a chest; past that it pauses instead of crossing the map.
- Turn off **refill-empties** if you only want drop-offs and no supply runs (the run then ends when your empties run out).
- Build a **ladder** (or vine) shaft from the mining depth up to your base — Baritone climbs ladders automatically, so it's the cleanest way up to the chests. Nothing to enable.

## Safety

- It won't mine below a floor you set, so it can't dig itself into bedrock.
- It pauses if lava ends up next to it or another player comes close.
- It pauses to **eat**: when hunger gets low it lets go of the quarry so AutoEat can finish a bite (otherwise the mining keeps cancelling the bite and the bot loops between eating and breaking). Tune with **pause-to-eat** / **eat-below-hunger** in Safety.
- It spaces out its actions on purpose, so it doesn't get ahead of a laggy or high-ping server and desync.

## Settings at a glance

- **General** — what to mine, what to keep, the floor height, action timing, and how thoroughly it sweeps for drops (`clear-box-size` — smaller boxes pick up more; `mining-reach` — lower makes it stand closer and collect more; `collect-drops` — the vacuum pass after each box, with `collect-max-seconds` capping how long it lingers).
- **Area** — the area limit, the two area modes, sizes, the top-down `layer-height`, and the corner-select key.
- **Storage** — when to start packing, dropping junk and bad food, and auto-disconnect.
- **Deposit chests** — haul filled shulkers out of the ender chest to base chests once it's full: the mode, the deposit/supply chest lists and their mark keys, empty refills (`empties-per-trip`), and travel distance cap.
- **Tools** — which tool to restock, at what durability, whether to also keep a shovel on hand (`also-restock-shovel`), and whether to pull spares from a tool-shulker in the ender chest.
- **Safety** — lava/player pause, and the eat pause (`pause-to-eat` / `eat-below-hunger`).
- **Cave handling** — fall height and parkour/diagonal movement.
- **Rendering** — the area box colors and style.
