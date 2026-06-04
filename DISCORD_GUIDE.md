# Foreman MassExtractor — Discord Guide

A copy-paste user guide formatted for **vanilla Discord** (unicode emoji + Discord markdown).
It's split into four messages, each under Discord's 2000-character limit — paste them in order.
Each block below is the raw text for one Discord message.

---

### Message 1 — Start here

```
# ⛏️ Foreman MassExtractor — Quick Start
An AFK bulk miner for MC 1.21.4 (Meteor addon). Point it at an area, walk away, come back to full shulkers.

## 🎒 What to bring
- A **pickaxe** (mines deepslate by default) + a few **spares**
- One or more **ender chests**
- **Empty shulkers** inside the ender chest
- Optional: **food** for AutoEat, **totems** near caves/lava

## ▶️ Turn it on
1. Stand where you want to start
2. Meteor menu → **Foreman** category → **Mass Extractor** (or bind a key)
3. Click it **ON** — it starts mining right away

🔧 First set **min-y-level** (default `-59`) just above bedrock so it can't dig itself in.
🛑 To stop: click it **OFF** — your Baritone settings get restored.
```

---

### Message 2 — Area + drop collection

```
## 🗺️ Pick a mining area (optional)
By default it spirals outward forever. To box it in, turn on **limit-area** and pick an **area-mode**:

🟩 **ChunksFromStart** (no clicking, fully AFK)
- **area-anchor = Center** → box centered on you
- **area-anchor = Corner** → you stand at a corner, box grows the way you're FACING
- Set **area-width-chunks** = **area-length-chunks** for a square (e.g. 5×5 = an 80×80 block area)

🟦 **CornerSelect** (mark by hand, like Excavator)
1. Turn the module on (it waits for you)
2. Look at one corner → **right-click**
3. Look at the opposite corner → right-click again
It stops once the area is fully cleared. ✅

## 🧹 Collecting drops (so nothing despawns)
- **clear-box-size** (default `8`) → digs each chunk in small boxes so it walks back over its own drops. Lower = picks up more.
- **mining-reach** (default `3.0`) → lower makes it stand closer to each block and grab the drop. Re-toggle the module after changing it.
```

---

### Message 3 — Storage + base chests

```
## 📦 Where the haul goes — set with **deposit-target**

The ender chest is always the **field buffer**: empties come out of it, filled shulkers go back into it — they never clog your mining inventory. The mode just decides what happens when it fills up.

🟢 **EnderChest** (default)
Fill the ender chest with filled shulkers; run ends when it's full. Best for "just fill a few shulkers."

🔵 **DepositChests** — haul to a base, near-unlimited run
When the ender chest runs out of empties, the bot pulls the filled shulkers back out, walks to your base, drops them off, grabs fresh empties, and comes back.

🟣 **EnderChestThenChests**
Same as DepositChests (kept for compatibility).

## 🏷️ Setting base chests (TWO kinds — keep them separate!)
- 📥 **Deposit chest** = where FILLED shulkers go → aim at it, press **K**
- 📤 **Supply chest** = where you keep EMPTY shulkers → aim at it, press **L**

Mark as many as you want — it always uses the **nearest** and skips any that are full/unreachable. Locations save between runs and show as boxes.

⚙️ Tuning: trip size = how many empties you keep in the ender chest, **empties-per-trip** (how many to refill), **refill-empties** (off = drop-off only), **max-deposit-distance** (won't trek farther than this).
ℹ️ You carry an ender chest — it dispenses empties, holds the filled shulkers between trips, and restocks tools.
🪜 Build a **ladder** shaft from the mining depth up to your base — the bot climbs ladders automatically, so it's the cleanest way up to the chests.
```

---

### Message 4 — Tools, caves, safety, stopping

```
## 🧰 Tool restock
- Swaps in a fresh **pickaxe** when yours drops below **min-tool-durability** (default `60`)
- From loose spares in the ender chest, OR turn on **restock-from-shulker** to pull from a whole **shulker of spare pickaxes** kept in the chest
- **reserve-silk-touch** keeps your Silk Touch pick reserved for ender chests

## 🕳️ Cave handling (on by default)
Lets it drop into caves to reach blocks instead of stalling. Never walks/falls into lava.
- **max-fall-height** (default `20`) → raise for more reach if you run armor + totems
- Parkour/diagonal toggles → turn on for more aggressive traversal

## ⚠️ Safety
- Won't mine below your **min-y-level** floor
- Pauses if **lava** is adjacent or a **player** comes within **player-pause-range** (default `48`)
- Spaces out its actions so it won't desync on a laggy / high-ping server

## 🛑 It stops on its own when
- ✅ A set area is fully cleared
- 📦 Storage runs out (no empties left / chests full)
- 🔌 Optionally **auto-disconnect** logs you off when the run ends
```
