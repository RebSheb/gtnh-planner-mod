# Bees

Issue 7: a bee production feature, with a jubilant toggle for special products, the housing and its frames or
upgrades, and the bee's speed. A bee card is a species, pure-bred, in a housing, made from NEI's bee products page
(NEI Addons' "beeproduce"). Its rates are worked from the game's code (Forestry 4.11.39, MagicBees, GT5-Unofficial
5.09.54.205, the GTNH 2.9 RC-2 pack's, read from the jars' bytecode), its settings are the website's own keys.

## Where it lives

- `machines/Bees` (pure): the species, the housings, frames, speed genes, climate and Industrial Apiary upgrades, the
  run (a craft's ticks, each product's expected items, the draw) and the formulas. Tested by `BeesTest` (worked values
  from the game's code) and `BeesGoldensTest` (25,440 cases from the website's own functions, written by
  `tools/dev/bees`; see below).
- `machines/game/BeeCards`: the species' facts on the node, the effect step (ticks and EU/t), the per-product
  multipliers after every refresh (`MachineConfig.outputProductivity`), the housings' items, the gear sheet's settings
  and formulas. `data/provider/BeeProvider` reads a species off the page through Forestry's API (NEI Addons is found by
  name, never compiled against). `Compat.BEES` registers it when NEI Addons and Forestry are in.
- The card: named for its species ("Forest Bee"), its machine the housing (a setting, `beeHousing`, as a crop's
  harvester is), picked from the card's machine menu or the plan button's. The bee is not a port: it is never used up.
  One port per product (Forestry registers every comb under the ore name beeComb, so `BeeCards.afterRefresh` splits the
  ports Node merges by ore name).
- Plans: `FfSettings` maps the website's `machineHandlerId` to the housing, `NeiRecipeIndex` finds the species by its
  Forestry id (`factoryflow:bee_species:<slug>`, `source.rawRecipeId`), `PlanExport` posts the website's bee recipe
  (the species' base chances over one 550-tick cycle, specialties as byproducts, the bee an input not consumed).

## The game's maths

A product with base chance b (a fraction, as the species registers it) comes out with Forestry's
`Bee.getFinalChance`:

    F(b) = 2.8 · (100 b)^0.52 · (p + t)^0.52 · s^0.37 / 100        (0 when p + t <= 0)

p is the housing's production modifier, t is 1 in Forestry's housings and 8 in the Industrial Apiary, s the speed
gene (Slowest 0.3 to Fastest 1.7, MagicBees' Blinding 2.0). A pure-bred bee rolls each product twice, at b for its
first species and at b / 2 for its second, and each specialty once at b, only while jubilant (both species'
`isJubilant`: the climate it prefers, or its own rule; a GregTech species with JubilanceMegaApiary never is outside a
Mega Apiary).

- **Apiary** (Forestry): every 550 ticks (BeekeepingLogic's queen work cycle) each roll succeeds with
  `rand.nextFloat() < F`, so at most once. p = -0.9 plus its three frames, each added against the sum so far
  (BeeHousingModifier): Forestry's frames give 1 while the sum is under 10, MagicBees' their own number (Magic and
  Resilient 2, Gentle 0.4, Metabolic 0.2, Necrotic -0.25, Temporal 0, Oblivion -9001), ExtraBees' theirs (Chocolate
  0.5 no further than a sum of 2; Restraint and Healing -0.25, Soul -0.75).
- **Magic Apiary** (MagicBees): the same, p = -0.1, or 0.8 with a production aura.
- **Industrial Apiary** (GregTech, MTEIndustrialApiary.checkRecipe): one operation is a queen's whole life. Cycles =
  her lifespan gene (Normal 40) times the beekeeping mode's lifespan modifier (GTNH ships HARD, 1.5). Each product
  comes out F(b) x cycles times, uncapped, randomly rounded, t = 8, p = 2, or 4 x 1.2^n with n production upgrades
  (up to 8), or 17.2 with the Upgraded Acceleration x256. The operation takes cycles x 550 ticks divided by its
  acceleration 2^speed; where that would be under 100 ticks it divides by the life's hundreds instead and multiplies
  the outputs by the whole part of what is left (a Shortest-lived bee at x256 reaches x246). Its draw:
  `(int) (37 x energy x 2^speed) + 32 x 4^(speed - 1)`, energy 1.4^n for production upgrades and x14.75 for the
  Upgraded Acceleration.

Worked: a 30% product at Normal speed is 0.084 a cycle in a plain Apiary (F(30%) 0.050 + F(15%) 0.035), 0.502 with
three Proven Frames, 0.923 a cycle in an Industrial Apiary (55.4 per 60-cycle operation of 33,000 ticks, 37 EU/t).
With x16 and four production upgrades: 71.4 an operation of 2,062 ticks at 4,322 EU/t.

## The website

Its bee cards (passive-production.ts, machine-effects.ts, overclock.ts) offer the Magic Apiary (its default: a card
with no machine), Alveary, Industrial Apiary and Mega Apiary, with the speed gene, three frames, the climate (Wrong,
Tolerated, Preferred, Controlled), the Magic Apiary's aura, the Alveary's frame housings, stimulators and utility
blocks, the Industrial Apiary's acceleration and production upgrades and the Mega Apiary's royal jelly. Its recipes
come from the dataset's Forestry species: each output at its raw base chance, once a 550-tick cycle, scaled by
`((p + t) / 0.1)^0.52 x s^0.37`. The goldens check that the mod's p + t, speed gene, frames, aura, upgrades, climate
and acceleration give the website's numbers for all of that (the website adds a Chocolate Frame past its ceiling; the
game stops it).

Where the game and the website part, the mod follows the game (as with the TGS and the vat), and the website should
follow:

1. A chance becomes items through F(b), not b: b^0.52 times a constant.
2. Every product is rolled a second time at half its chance (the bee's second species). With 1, the website's rates
   come out 1.5 to 6 times the game's, the likelier the product the more: a plain Apiary's 30% product is 0.084 a
   cycle in game and 0.30 on the website, an Industrial Apiary's 0.92 in game and 3.29 on the website.
3. Forestry's housings roll at most once a cycle (F above 1 is still one item).
4. The Industrial Apiary's draw is 37 x energy x 2^speed + 32 x 4^(speed - 1); the website bills
   (37 + 32 x 4^(speed - 1)) x energy, so 8,010 EU/t where the game draws 4,322 (x16, four production upgrades). With
   the Upgraded Acceleration the production upgrades still cost their energy in game.
5. The Industrial Apiary's acceleration is cut short for short-lived bees (above).
6. The plain Apiary is not one of the website's bee machines (the mod posts its Apiary cards with an "apiary" handler
   first in the recipe, which the website merges ahead of its own and computes as the plain Apiary).
7. The website gates specialties on a "Needs preferred climate" tooltip its dataset does not carry (it marks them
   `byproduct`), so on the live site Tolerated keeps them; the mod drops them, as the website means to.
8. A Magic Apiary card with no machine at a tier above NONE overclocks its 0 EU recipe (550 ticks at NONE, 275 at
   MV).

## The card's settings

All under the website's keys, so plans carry them both ways: `beeSpeedGene` (unset: the species' own; on the website
Normal, so an import without one says Normal and a post always says it), `beeEnvironment` (shown as Jubilant: Yes is
preferred, No is tolerated, Wrong climate stops the bee; controlled reads as Yes), `beeFrameSlot1..3`, `beeMagicAura`,
`beeIndustrialSpeed` (none, speed-1..8, speed-8-upgraded), `beeIndustrialProduction` (0..8). The housing is
`beeHousing` here and `machineHandlerId` there (`apiary`, `magic-apiary`, `industrial-apiary`; a website card with no
machine is the Magic Apiary). The upgrades, frames and climate carry to the next bee card; the housing comes from
the plan button's machine menu, remembered per tab.

## Left to do

- The Alveary (Forestry's multiblock, ExtraBees' frame housings and electrical stimulators) and the Mega Apiary
  (kubatech's MTEMegaIndustrialApiary, its BeeSimulator, royal jelly, voltage slots). Imported, an Alveary comes in as
  an Apiary and a Mega Apiary as an Industrial Apiary, each with a note. The Bee House (p = -0.75) is a one-liner.
- Frames wear out (one use a cycle; Untreated 80, Impregnated 240, Proven 720 uses): an input at that rate, as the
  vat's radio hatch burns.
- The Industrial Apiary's other upgrades: lifespan (shorter lives, so the acceleration cap sooner), climate and biome
  (whether it is jubilant), pollen, and its flower requirement.
- The beekeeping mode is GTNH's HARD, fixed; a pack on another mode changes only the Industrial Apiary's lifespan.
- Hybrid bees (a second species unlike the first) and the princess and drones a queen leaves.
- The website's fixes above, then goldens of whole rates both ways.

## Goldens

`tools/dev/bees/bee-goldens.local.test.ts` runs the website's functions on a bee recipe (one product at 30%, one
specialty at 15% marked as the website's jubilance rule wants) over every speed gene and climate: the Apiary (with
the mod's handler) and the Magic Apiary (with and without the aura) on 235 frame sets, the Industrial Apiary on every
acceleration and production upgrade. Rerun it from the website's checkout with the website's vitest and
`tools/dev/bees/vitest.bees.config.mjs` (its header has the line); it adds nothing to the website's checkout.

## Seeing it in game

The dev game has no Forestry by default: `PLANNH_GTNH=1 PLANNH_BEES=1 tools/dev/mc.sh start` adds Forestry and NEI
Addons (GregTech's bees and the Industrial Apiary come with them), in a world of its own (`plannh-dev-bees`).
