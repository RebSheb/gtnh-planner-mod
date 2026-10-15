# GTNH Planner

GTNH Planner, the in-game side of gtnhplanner.com (mod id `gtnhplanner`, package `com.gtnhplanner`). It started as
a fork of sbancuz/PlanNH and was renamed on 2026-10-07 so the two never collide; saves (`plannh/plannh.dat` in a
world's NEI folder) and config files from before the rename are still read, then written under the new names.

In-game, NEI-driven flowchart production planner for GT New Horizons: Minecraft 1.7.10, Forge 10.13.4.1614,
client-side only. The planner UI is ModularUI2 in `ui/` (the Solve-mode board), the balancer is an ojAlgo ILP
solved in the background (`data/flowchart/balancer/`, `SolveService`), auto-layout and wire routing are in
`layout/`, recipe sources per mod are in `data/provider/`, and the NEI glue is in `nei/`.

## Remotes and branches

- `origin` = github.com/jackwrichards/gtnh-planner-mod (ours; it was jackwrichards/PlanNH, a fork, until the rename),
  `upstream` = github.com/sbancuz/PlanNH.
- `main` is our trunk and the fork's default branch; it started from upstream `dev` (a0896df). The other branches
  on the fork are untouched copies of upstream's. The plan is to gut and rebuild the codebase in our own image, so
  upstream's branches, bugs and CI are not our concern; don't spend effort on them.
- Changing the mod is fine when it makes developing or testing easier (hooks, debug output, testability
  refactors). Don't do end-user work yet (UX polish, features, user-facing bug fixes): it is all going to change.
- Dev tooling lives in `src/main/java/com/gtnhplanner/dev/`, `tools/dev/` and `docs/`; it is wired in through
  `DevHarness.initIfDev()` in `ClientProxy#init`.

## The rebuild

- `docs/design/board-solve-mode.md` is the spec for the new planner: Solve mode only, Factory Flow's recipe card
  and drawers with NEI parts, what every control does, what we keep from the engine, and the porting order.
- Mockups live on the design canvas linked from that file (row 4, "Hybrid", is the chosen direction).
- All six porting steps are on `main` (2026-10-06) and the old `gui/` package is gone. `ui/` map: `BoardScreen`
  (top bar, notices), `OverviewRail` (the overview on the left), `BoardSession` (the open plan: every edit, undo,
  slots, solve hookup, notices, totals), `canvas/` (board, wires, port drags, arrange), `card/` (recipe card),
  `drawer/`, `popup/` (menus, number and text boxes; recipes are looked up in NEI itself). Known gaps and next
  steps are at the top of `docs/design/build-prompt.md`.
- `docs/design/card-redesign.md` is the clean card now on the board, the minimap and the world (`CardLayout`,
  `CardPaint`, `CardChips`, `RecipeCard`, `CleanCardView`): what is settled, where every control and setting went,
  and what is left to design (`call cards` pages through a plan's cards).
- `docs/design/ff-card-spec.md` is Factory Flow's card measured from its source (sizes, colours, tooltips, the
  power panel's formulas). Our card is 320 wide to its 380 with chrome kept at 1 px; the clean card replaced its
  look (`docs/design/card-redesign.md`); `ui/card/CardLayout` holds the geometry. Board tooltips are `ui/popup/Tip` panels; a multiblock's power chips show `ui/card/PowerPanel`.
- AUTO's rule (the owner, 2026-10-13; `docs/design/solver-plan.md`): buy as far upstream as it can (raw goods, your own
  machines running, never buying more to sell more), sell as far downstream as it can (spare goes on to every machine
  that takes it and is sold at the brick wall), then the least internal flow; machine steps in `ModelData.portSteps`,
  a loop counting as one, settled level by level in `TradeStage`. A machine nothing pinned feeds or needs sits at zero
  and asks for a count (`SolutionView.unneeded`). There is no setting for it: the owner dropped one.
- Wires are routed by `layout/WireRouter`: exact A* on a 10 px grid with planned docks, kept per board so a change
  re-routes only what it touched. Arrange is `layout/arrange/`, a port of the website's arrange (column pass,
  optimiser, free placement, polish) judged by that router, run in the background from the top bar's key.
  `docs/design/routing-and-arrange.md` has both designs and the benchmark they are tuned against.
- `importer/` converts Factory Flow plans (JSON, plan codes, links) to graphs: a pure core plus `importer/game/`,
  the NEI and GregTech side. The "+" plan tab pastes one from the clipboard; `call 'importff?file=<path>'` does it
  from the harness. Fixtures are in `src/test/resources/factory-flow/`. A card's machine is found by the website's
  handler id among the blocks `handlers.json` lists for it (`WebCards.handlerItems`; names alone never match a GT
  single block, named per tier), a single block at the card's tier (the nearest it comes in, reported). Our cards are
  sized differently, so the board pulls an imported plan's overlapping cards apart once it has solved
  (`layout/Unstack`). Real plans to try it on: `POST /api/community/plans/<id>/download` gives a post's plan.
- Shared machines (one card, several recipes time-sharing one machine; `docs/design/ff-shared-machine-spec.md`) are
  `MachineGroup`s with ordered `sections`: each recipe stays a node with its own ports and wires, the group draws as
  one card, its machine settings are copied to every recipe, and a pinned count is an exact solver pool.
- Saves are backed up to `backups/` beside the save (`gtnhplanner/plans.dat`), once per session and before any save
  that drops a plan.
- Non-recipe machines (the top bar's Non-recipe key, `ui/power/PowerPicker`) are the website's power sources, ported
  to `power/` from its `src/lib/power` (57 sources; the workbook tables, resource map and machine icons are copied
  JSON in `assets/gtnhplanner/power/`, never hand-edited; parity with the website is tested per source). A power card
  is a `Node` with `powerSource` and `powerSettings` instead of an NEI recipe: `power/game/PowerPorts` rebuilds its
  ports from the model at exact per-second rates (`Port.exactAmount`, a craft a second), EU first. EU is a resource
  (`Energy`, key `power:eu`) that only drawers take, shown in EU/t; the overview shows power used, made and net.
  Power cards post to and import from the website as its power cards. `call power` checks every flow against the
  game (unmapped names, items or fluids this pack lacks).
- The picker's first column, Other (`PowerGroup.OTHER`, `PowerRegistry.others()`, never in the generator catalog), holds
  the planning cards: the custom rate card and the fluid tank. Both are holders (`power/Holders`): empty, they show two
  sockets; a port dropped on one, or a socket dragged to a port, makes it take that resource (`BoardSession.holdAndWire`),
  and every edit lets a holder with nothing wired go of it (`releaseHolders`).
- The custom rate card (`power/CustomRate`, the website's custom-rate.ts) is a power card with source `custom-rate`: a
  dial (Supply or Request, a rate a second, EU/t for EU) and one port. Pinned at one when placed, so the dial is the
  rate. The overview counts it as the plan's edge (what it supplies is an input), never a machine. It posts and imports
  as the website's own custom rate card.
- The fluid tank (`power/FluidTank`, source `fluid-tank`; the website's Tank, its free mirror of every fluid Canner
  recipe; drawn as GregTech's LV Fluid Tank, meta 818, on the card and as its ghost in the world) empties a filled cell into its
  fluid and the empty cell, or fills cells, free and instant, at the litres
  Forge's fluid container registry gives (never a guessed 1000). What it is given decides: a cell given or a fluid asked
  of it empties cells, a fluid given or a cell asked of it fills them. One craft a second, unpinned: its count is cells
  a second, and the overview lists no machine for it. It posts as the website's Tank (map `planner.tank`, a tick a
  craft, so the count goes over divided by 20), and the website's Tank cards and loose cell wires (a cell wired straight
  into a fluid slot) import as fluid tanks.
- Crops are ported (`machines/CropsNh`, the website's model, checked on its goldens; `data/provider/CropsNhProvider`
  reads CropsNH's NEI Crops page; `machines/game/CropCards` is the card): a card per crop, added from that page, its
  count crops planted, harvested by a Crop Manager or the Industrial Farm (the `cropHarvester` setting, the website's
  `machineHandlerId`), its settings under the website's own keys. They import from and post to the website as its
  crop farm cards (`metadata.cropsNh`). The full pack has CropsNH; the dev pack does not.
- Bees (issue 7, `docs/design/bees.md`): `machines/Bees` is worked from the game's code (Forestry's getFinalChance, the
  Industrial Apiary's checkRecipe), not the website's bee maths, which it parts from in ways the doc lists; the goldens
  check the parts they share. A card per species from NEI Addons' bee products page (`data/provider/BeeProvider`, read
  through Forestry's API; `machines/game/BeeCards`), named for it, its housing a setting (`beeHousing`: Apiary, Magic
  Apiary, Industrial Apiary) picked from its machine menu, the rest the website's keys (speed gene, `beeEnvironment` as
  Jubilant, frames, aura, acceleration, production upgrades). Plans carry them both ways as the website's bee cards.
  `PLANNH_GTNH=1 PLANNH_BEES=1 tools/dev/mc.sh start` adds Forestry and NEI Addons to the dev game.
- GregTech's steam machines (bronze and steel single blocks, the steam multiblocks: `GtMachines.Kind.steam`, the
  website's "Steam ..." or "High Pressure ..." names) run at no voltage: no tier chip, a voltage left in their settings
  is ignored by the maths, and the card's power row bills steam in L/s (the website's steam report).
- NEI's recipe pages carry a plan button above + and the star (`nei/PlanButton`, `nei/PlanMenu`, added through NEI's
  `UpdateRecipeButtonsEvent`): it asks which plan (most recently open first, `Plan.byRecency`) and which machine
  (`ui/card/MachineChoices`: GT single blocks are one choice at the recipe's tier), then opens the board on that plan
  with the new card centred and selected (`BoardSession.addAndFocus`). Shift-click skips both menus. The machine pick is
  remembered per tab (`MachinePicks`, `config/gtnhplanner-machine-picks.properties`) and NEI's + uses it too.
  `call 'nei?item=<key>&planner=0&tab=<name>'` opens a recipe page from the inventory.
- Plans work as on the website: a tab is an open plan (`Graph.open`); closing one keeps it, in the Library's My
  plans, where every plan is listed, most recently open first (`Plan.byRecency`, `lastOpen`). Deleting asks first.
- Sticky notes (`ui/note/NoteCard`, a `Note` in `graph.notes`): right-click empty board to add one; drag anywhere
  to move (even while writing), drag the folded corner or an edge to resize, click to write, right-click for colour,
  text size and delete; hovering or writing shows keys (delete top left, text size top right) at a fixed size on
  screen whenever they fit on the note. A selection outline shows only when several things are selected.
- On the board a left press on anything (a card's keys and chips too, a drawer's, a note's) is a click when let go
  within 3 GUI pixels (`BoardCanvas.DRAG_SLOP`) and acts then; moved further, it carries the thing. Carried things
  glide (about 120 ms) between 10 px grid spots and never land on another card or drawer: each frame they go to the
  clear spot nearest the mouse (`BoardCanvas.nearestClear`, the website's ring search), so they hug what is in the way
  and come out its far side past its middle; notes pass over. Hovering a port, drawer or wire lights only it, its
  wires and what they reach (`ui/HoverScope`, the website's flow scope); an overview row lights its resource
  everywhere. A wire in hand makes every card that would take it glow green, its outline and its whole face, and
  every port that would take it, all breathing (`RecipeCard.acceptGlow`); it snaps onto the port it would be wired to
  (that port and card steady and brighter), and is green where letting go wires or makes a drawer, red where it does
  nothing. A port picked up off its card snaps home when carried back over it, shown just as it was (no green: only
  other places glow), and letting go there puts it back; no tips show while something is in hand (a port has one
  drawer at most: "Drawer already exists"). Every tooltip (board, overview, tabs, top bar, Library, the non-recipe
  picker) waits until the mouse has rested on its thing for 0.8 s (`Tip.Wait`); moving about on it keeps the wait,
  leaving resets it. Tier chips everywhere (the card's header, the overview's machine lines, the non-recipe picker,
  the Library, the power panel) are flat in the tier's colours with a black or white label and no shadow
  (`RecipeCard.chip`, `Hyb.tierTag`); the card's label is sized to whole screen pixels at any zoom. The overview's
  machine lines take the wheel and a click on their count as the card's count does; trash leaves its outputs. A
  port set to a rate on its card with nothing wired to it is listed under its resource there, as a drawer is
  (`OverviewRail.PortPin`); a line nothing sets has a "~ Set" whose rate goes to its one open port, or to a drawer
  wired to all of them (`BoardSession.setLineRate`). Wherever nothing sets a part of the plan (no pinned count or rate anywhere along its wires:
  `balancer/Unanchored`, `BoardSession.unset`), its unpinned counts say "(set count)" and its empty drawers "Set rate",
  pulsing gold together (`CardPaint.prompt`), however much of the rest is set;
  the count keeps its place, a line kept under it. Scrolling a pinned count below one unpins it. The gear's See-through slider (0 to 100%) fades
  the board, its dots and the overview (`Hyb.seeThrough`) down to nothing; cards and text stay solid. The gear opens
  `ui/popup/SettingsPanel`, a centred box of sections. Drawers are drawn by `ui/drawer/DrawerPaint` (board and
  minimap): the clean card's look in the website's shapes per kind (tank, crate, shield, bin). Where the solve sends
  something out of the plan, or brings it in, at a wired port with no drawer (the balancer's open gates), the card
  shows it as a row of its own under that port (`BoardSession.extraFlows`, `CardPaint.extraRow`): "Forced" in a
  byproduct drawer's green going out or a source's pink coming in, faintly striped; the port's own row shows what its wires
  carry. It is part of the port, so dragging it makes a drawer, which takes the flow, and the row goes.
  They are Factory Flow's text annotations (`kind: "text"`, its colour tags and font sizes), so they come in with an
  imported plan and go out with a posted one; they take no part in the solve, the wiring or Arrange.
- The tour (`ui/tutorial/`, `docs/design/tutorial.md`): the "?" key's Take the tour, or the first-run offer (`TourOffer`:
  a note over the dimmed board the first time the planner opens, asked once). Thirty-seven steps, on the planner from NEI to the world: each acts something out on the real UI, then a
  note (`Callout`) beside what it did says what it is (gold words between asterisks in `Script`) and waits for next; no
  autoplay, no chapters. It drives the UI with
  a cursor of its own (`Pointer`, through mixins on the mouse position screens and NEI read and on Shift;
  `VirtualInput` delivers presses, drags and keys to the open screen), in plans of its own (`Plan.enterSandbox`,
  never saved; leaving it, from any step, keeps the player where they are: the plan in front of them joins theirs as
  "Tour plan" and the screen stays, and `Sandbox` puts back the settings it borrowed). `Targets` finds what steps point at by name, `Director` plays them
  (Back restores a step's saved start). Text that names a key reads the player's bindings. `call 'tutorial?step=N'`
  starts or jumps to a step, `call tutorial` says where it is; every step's start ships in
  `assets/gtnhplanner/tutorial/starts.json` (`call 'tutorial?export=1'` after a full run rewrites it, needed when a
  script change alters a step's start). Hurrying must never outrun the game: the pointer settles on screen draws, not
  frames (the GUI does not draw every frame, and hover is found as it draws). It leaves the game's GUI scale alone: the
  board takes one of its own with room for the tour's moves (`PlannerScale`: 960x540 board pixels; GTNH ships scale 4,
  a 480x270 GUI at 1080p where its drags run out of board), and `Targets` converts board rectangles to the game's
  pixels. It opens NEI's folded item groups (the pack folds every fluid into one) as it looks for items; `Sandbox` puts
  them back. After a script change,
  walk every step and check the log for `[tutorial]` warnings.
- The Library (top bar; the + tab menu; right-click on an overview row: setups that make it) has two shelves: My
  plans, and Factory Flow's public setups from gtnhplanner.com (`library/CommunityApi`, `LibraryFeed`;
  `ui/library/LibraryView`), opened through the importer as a new plan. Signing in uses the website's own username
  and password accounts; the session (never the password) is kept in `config/gtnhplanner-account.properties`
  (`library/Account`). The top bar's Share key (`BoardScreen.openShare`, labels only) offers the open plan to the
  library, its plan code to the clipboard, Share in chat (`client/ChatShare`: the whole plan on NEI's chat item link,
  which NEI's server side relays from anyone to everyone, no website; NEI from beta-3 carries it in the link's payload
  beside a paper named after the plan, under 30 KB; older NEI writes the item twice into the chat line, so plans over
  14,000 characters are refused there, as a chat packet over 32 KiB drops every player it goes to; players with the
  planner see "X shared the plan [name]", whose click, `/gtnhplanner_import <key>`, opens it as a new plan and then
  reopens that one; `call 'chat?share=1'` shares and lists the chat), and Screenshot... (`ui/popup/ScreenshotPanel`,
  rendered by `ui/canvas/PlanPicture`): the board
  drawn by itself into a raw GL framebuffer at a render tick's end (`WidgetTree.drawTree` on the canvas, resized to the
  plan and framed on it, at the GUI scale; nothing lit, selected, breathing or pulsing while `PlanPicture.drawing()`),
  cards Detailed or Simple (the zoomed-out glance view, with room for the names), an optional footer as the website's
  export bar (a title row: icon, name, machines and EU/t; Inputs and Outputs as tinted panels in even columns, from the
  overview's totals) and a small "GTNH Planner" mark; previewed in a box nearly the screen's size, then saved as a PNG
  in `screenshots/` or copied. "Share
  to library..." (`AccountForms.shareRow`, also in a tab's or a plan tile's menu) sends the plan as Factory Flow
  project JSON (`library/PlanExport`, checked against the website's own schema) with a title, a description and an
  icon (picked from the plan's items; `library/Posting` looks up the website's picture of it by name in its item list,
  as the site draws an item from nothing else). A plan remembers its post (`Graph.postId`, `description`, `icon`, saved
  with the slot, never in a plan code), so sharing it again updates that post ("Update in library...", the website's
  PUT: votes, downloads and comments stay); a post gone from the site (404, or 403) is forgotten and shared anew. The
  player's own posts (the site's `isMine`) have Edit (title, description, icon) and Delete in the Library's pane and a
  tile's right-click menu; opening one links the new plan to it. Test it all against
  `node tools/dev/mock-library.mjs` (a local stand-in; `call 'library?url=http://127.0.0.1:8789'`), never the real site.
- Players never see the website (the owner, 2026-10-09): no links or keys to it, and no text naming gtnhplanner.com or
  Factory Flow in the UI, its messages, the config files it writes or `mcmod.info`. The library is "the library";
  pasted plans are "a plan link or code". Code comments and docs may still name the website as the design's source.
- Feedback (the top bar's red bug key, `ui/popup/FeedbackPanel`): three mood faces, Bug / Idea / Help chips, one text
  box (`ui/popup/TextArea`, our own: MUI2's multi-line editor crashed the game), a line naming what goes with it (the
  open plan by name, the game log, a crash report if there is one) and Send. `library/Reports` sends it, with a Simple
  picture of the plan and the log's tail with names, the home folder, addresses and tokens taken out, to the website's
  `POST /api/feedback`, which opens it as a public issue on this repo (labels "from game" and bug, enhancement or
  question) with the site's own token, so players need no GitHub account; the box then links the issue. The endpoint
  answers 503 until `GITHUB_FEEDBACK_TOKEN` (a fine-grained token, Issues read and write on this repo) is in the
  droplet's `/opt/shared/env.local`. The site guards it against spam (its `src/lib/server/feedback-guard.ts`, after a
  flood on 2026-10-12): a report must carry the mod's `deviceId` and `versions` shapes (change them there first), and
  there are limits per address and device, on the same words, and on issues site-wide. `client/CrashReports` puts the planner's state in every crash report and, on the
  next open, offers to send the newest report the planner was part of (`ui/popup/CrashOffer`: a planner frame in the
  error's stack, or a ModularUI frame with the planner open); asked once per crash. The mock answers
  `/api/feedback` too, writing each report to `build/dev-client/mock-feedback/`.
- Updates: `client/UpdateCheck` asks this repo's GitHub releases once a game, in the background (pre-releases too;
  tags compared as `0.2.0-alpha.N` < `-beta.N` < `-rc.N` < `0.2.0`, a dev build counting as its tag; jars before
  beta.1 read only alphas, so to them a beta is the release). When a newer one is out the board says so once
  per version as it opens (`ui/popup/UpdateOffer`, after any crash offer), with a key to that release's page; either
  answer is remembered (`update.offered` in the settings). `call 'update?latest=<tag>'` fakes one; `clear=1&reset=1`
  puts it back.
- While playing (`ui/world/`, `docs/design/minimap-and-world-links.md`): the minimap of the plan last open
  (`Minimap`, from the board's `PlanSnapshot`), and the plan over the world: a card's place key (or L on a block)
  places it on a spot, the imaginary block in front of the face looked at (`LinkPicker`, `LinkTarget`,
  `WorldLinks`, `Node.worldLinks`, a spot and a facing per machine; breaking blocks never removes one), and
  `PlanOverlay` (Y) draws each placed card just above its machine's ghost, never on it, as the board does
  (`ui/card/CleanCardView`), with the plan's wires as connectors, only for the machine you look at (Focus, Shift+U), and
  a label in each ghost (Labels, Shift+I: name, tier, EU/t, count, as much as fits in it); wire tags always, where you
  look or off; machines see-through, solid
  or hidden, and the keys strip on or off, in the gear. Every world key is a binding in Controls (`PlannerKeys`, read
  by key code; `call keybind` lists and sets them, `probe=<code>` says what a press runs). Placing
  builds against placed machines' ghosts as against blocks (`LinkPicker.aim`), and the ghost's front is lit with an
  arrow (`WorldMarks.front`); `call 'worldplace?card=N&x&y&z&facing'` places a card, `pick=N` starts placing it. Multiblocks show as their whole structure (`ui/gt/StructureGhosts`), drawn every frame from a
  kept fake world: display lists of block rendering come out invisible under Angelica. They are built as the card needs
  them (its coil, a tower's height, a line's length) through GregTech's structure channels. Placed machines are adjusted
  from the world with sneak held (`PlacementKeys`: R turn, G pick up and move, Del remove, U Focus: cards only where you
  look, Y hides the whole overlay). World code never loads the plans (`Plan.loaded()`). The pin list (`PinList`, issue 5) lists
  the plan's pinned cards in a corner while playing (machine, tier tags, how many; clear of the minimap and the keys
  strip): a card's pin key, or the one right of each overview machine line's EU/t, opens Place in the world / Pin to
  the screen (`RecipeCard.openPlaceMenu`); right-click unpins both (`clearPins`). Pins are `Graph.pinList` (card ids,
  saved with the plan); show and corner are in the gear's Pin list section, and a key (unbound) shows or hides it. The earlier lens of every machine's live
  state is shelved on `shelf/ar-machine-lens`. Settings are the top bar's gear (`PlannerSettings`).
- Sound (`ui/sound/Sfx`, `docs/design/sound.md`): every sound is synthesized (`tools/sound/engine.mjs` the
  material, `sounds.mjs` the recipes, `synth.mjs` renders them with ffmpeg; `sounds.json` names them) and balanced
  there against the click; the lab (`tools/sound/lab`) plays the same recipes live in a browser to pick from; the game plays them at one volume matched
  to the vanilla click, times the gear's Sounds setting. Anything pressed clicks unless the action has a sound of its
  own; wires and drawers sound of what they carry (item clack, fluid bubbles, power sparks). `call sfx` lists what
  played (the dev game is muted).
- The Tree Growth Simulator and the Bacterial Vat are modelled from the game's code, as on the website
  (`docs/design/bio-vat-and-tgs.md`): pure models in `machines/` (`TreeGrowthSimulator`, `BacterialVat`,
  `FormulaLine`), tested on the website's golden values; the game side is `machines/game/MachineModels` (each TGS
  output's mode and a Forestry sapling's genes from the NEI recipe, the vat's glass and sieverts, the radio hatch's
  materials) and `MachineModelStep` (the TGS's fixed 100 ticks at VP[t]). They set the solver's per-port multipliers
  (`MachineConfig.inputConsumption`/`outputProductivity`) after every refresh (`Node.afterRefresh`) and add the
  radio hatch's burned material as an input (`Port.fromModel`: never posted). Their settings sit in the card's gear
  sheet under the website's own keys (`machine:<key>` controls, plain strings, "" = the recipe's default), so plans
  carry them both ways (`FfSettings`, `PlanExport`); the sheet ends in their worked formulas, as the website's cards
  show them (read-only, never pinned). `call 'machinedata?dir=...'` in the full pack writes the website's side files
  (its `tools/audits/import-machine-side-data.mjs` reads them).
- Every other GregTech card runs on the website's machine maths (`docs/design/machine-table-port.md`): `machines/web/` is
  a pure port named after its files (machine table with its 96 entries, overclocks, hatches and power, heat, parallels,
  power report, fusion, the special machines, runtime variants), checked by `MachineGoldensTest` against goldens the
  website writes with its own functions (`src/test/resources/machine-goldens.jsonl.gz`, 0 mismatches over 42,250
  cases). Its data comes from the website, never hand-edited: `assets/gtnhplanner/machines/handlers.json` (each recipe
  map's machines and settings, with their item ids) and `fusion-startups.json`; regenerate both files with the
  website's `node tools/audits/export-mod-machine-data.mjs`. The game side is `machines/game/`: `WebCards` (a card as
  the website's recipe and node: GT++ maps are gtpp.recipe.* in its data, gt.recipe.* here; a machine matched by item
  id, then name), `RuntimeVariants` (GT's OverclockCalculator per tier, as the website's oracle runs it), `WebEffect`
  (through `MachineConfig.nodeEffect`: exact duration and EU/t, parallels, per-output multipliers, the stall) and
  `WebSettings`. A card's settings are the website's: `voltage`, `amp` (to 16,777,216), `coil`,
  `energy_hatch_type` and `machine:<id>` for each machine option; the engine's old knobs are gone and an old
  `machine_heat` becomes a coil on load. Every GT card's gear sheet is its settings beside its working
  (`machines/web/Working`). `tools/dev/webcheck.sh <dir> [regex]` compiles and tests the port with javac alone;
  `call machinemath` lists every card's maths (`maps=1`: the game's recipe maps the data lacks).
- Multiblock pictures in `assets/gtnhplanner/textures/structures/` are Factory Flow's renders (`public/power-art`: the
  owner's processing multiblocks and the Power Planner workbook's power plants), scaled to 320 px palette PNGs with
  transparency. `ui/card/StructureArt` maps in-game machine names to them; `call 'gtmachines?art=1'` checks coverage.

## Build and test

- Gradle provisions the JDKs (25 for the build, JetBrains Runtime 25 for `runClient25`). Keep the checkout at a
  short path: deep Windows paths break the clone and the Minecraft dev setup (MAX_PATH).
- `./gradlew test`: 614 headless JUnit tests (the machine maths and power sources against the website, drawers, solve service, routing, layout, serialization),
  many over gtnh-flow YAML charts in `src/test/resources/gtnh-flow/`. About 40s, no Minecraft. `addon.gradle`
  opts `test` out of the configuration cache; without that a clean build reports `:test NO-SOURCE` and silently
  runs nothing, so if you ever see NO-SOURCE, check the count in `build/test-results/test/*.xml`.
- `./gradlew spotlessApply` before committing; CI checks formatting.
- One jar runs on every GTNH 2.9 build, beta-1 to the newest (2.8 is not supported). It compiles against the newest;
  where an older build's mods lack something, the code catches the LinkageError or reaches it by reflection.
  `./gradlew compatCheck` checks it: every class, method, field and mixin target our classes use, against each pack's
  mod versions (`tools/dev/compat/manifests/`, GTNH's release lists from DreamAssemblerXXL; add the next build's there),
  and fails on any gap not in `tools/dev/compat/guarded.txt` (each with why it is safe). Run it after moving the pins
  and before a release. It cannot see an API that still exists but behaves differently: test in an older pack for that.
- The next release's notes build up as work lands: `.changelogs/unreleased.md`. Any commit to main that a player would
  notice adds or edits its line there, in the same commit (the owner, 2026-10-12: always ready to ship). How they read:
  Discord-casual and in a player's words, short and not daunting. Written as the engineers who made it, never as
  marketing or as AI (the owner, 2026-10-13): short sentences with almost no commas, no slogans. One line a change,
  grouped New / Better / Fixed; the
  thing in bold only for a headline feature; no class names, settings keys, paths or numbers a player would not know;
  never the website (pasted plans are "plan links and codes"). Fold small changes together rather than letting the
  list grow; a change undone before release comes off the list.
- Releases are tagged `0.2.0-beta.N` on main (annotated, "GTNH Planner <tag>"; alpha.1 to alpha.5 came before, as
  pre-releases) and published as full releases (the owner, 2026-10-13), notes in
  `.changelogs/<tag>.md`: rename `unreleased.md` to it, top it with the last release's short intro (beta warning,
  which GTNH builds) and end it with its Install steps, and start a fresh empty `unreleased.md`. The release page is
  for players only: no technical words at all, about 400 words (the owner, 2026-10-13). Everything technical goes in
  `.changelogs/<tag>-technical.md`, linked from the end of the notes: what was broken, what we did, numbers, and
  links to code lines and commits pinned to a SHA (never `main`), with the old code at the commit before the change.
  Pictures: the owner takes them and pastes them into the draft on GitHub; the notes mark each place with a
  `> 📷 **IMAGE N – ...**` line saying what to show. The fork's Actions have never run, so the tag builds nothing: run `./gradlew compatCheck`,
  smoke-test the jar in the full pack, then build at the tag with a clean tree (`./gradlew build -x test`, so the jar takes the tag's version) and
  `gh release create <tag> --repo jackwrichards/gtnh-planner-mod --draft --title "GTNH Planner <tag>" -F <notes>
  build/libs/gtnhplanner-<tag>.jar` (plain `gh` picks upstream), for the owner to review and publish.
- The solver (`docs/design/solver-plan.md`: what it promises, what was wrong, the phases) is checked against
  goldens: `./gradlew test --tests '*SolverGoldenTest*' -Pbench=check` compares every corpus plan's whole answer
  (`src/test/resources/solver-corpus/`, plans exported with `call 'solvedump?dir=...'`) to the recorded one, with
  `-PwriteGoldens` records them afresh. `SolverPathTest` (answers the same however the simplex gets there) and
  `SolverIslandTest` (a plan's islands answer as if alone) run in the normal suite. `*SolverBench*` and
  `*SolverCensus*` time it and count its models; compare before and after in one sitting.
- The layout benchmark (`./gradlew test -Pbench=<label> --tests '*LayoutBenchmark*'`, tagged `bench`, left out of the
  normal run) routes and arranges every plan in the corpus, measures it (`layout/RouteMetrics`) and draws it to
  `build/bench/<label>/`; `DragBench`, `RouterTrace` and `RouterProfile` look at dragging, single searches and
  profiles. Usage and the numbers so far: `docs/design/routing-and-arrange.md`.

## Running and seeing the game

Use the dev harness; full reference in `docs/dev-harness.md`.

```bash
tools/dev/mc.sh start      # build, launch, auto-load the creative test world; blocks until ready
tools/dev/mc.sh call open  # open the flowchart
tools/dev/mc.sh shot x.png ['x=..&y=..&w=..&h=..']   # screenshot (optionally cropped), then Read the PNG
tools/dev/mc.sh part 1 TIER     # click card 1's tier chip by name (also: move, scroll 'amount=-1')
tools/dev/mc.sh swap --reopen   # hot-swap code changes into the running game (~12s, no restart)
tools/dev/mc.sh stop
tools/dev/mc.sh smoke      # automated launch/open/screenshot/log-scan check
tools/dev/board-check.sh  # GT runs: end-to-end board check (recipe, port drag, solve, add, undo) in a throwaway plan
```

- `PLANNH_GTNH=1 tools/dev/mc.sh start` loads GregTech and the pack's recipes through the GTNH core mod (72 mods,
  about 30s once the jars are cached) in its own test world, `plannh-dev-gtnh`. Use it whenever real GT recipes,
  machines or items are needed; the plain start (29 mods, no GT) is enough for anything else.
- The target pack is GTNH 2.9 (2.9.0-RC-2 as of 2026-10-09). `dependencies.gradle` pins that pack's versions of NEI,
  GT, MUI2, GTNHLib, the core mod and every mod a recipe provider reads, so what compiles runs there; move them
  together when the pack moves (NEI 2.8.155 changed the recipe button event and broke the plan button, so the
  older event is guarded; `compatCheck` keeps the older builds working). The full
  pack, all of its mods and recipes, is the Prism instance `GTNH_2.9.0-RC-2_planner`: build, swap the
  `gtnhplanner-*.jar` in its `.minecraft/mods`, launch it with `prismlauncher.exe --launch GTNH_2.9.0-RC-2_planner`
  (from `%LOCALAPPDATA%\Programs\PrismLauncher2`). Its JVM arguments turn the harness on and load
  `gtnhplanner-dev`, so `mc.sh call`, `shot` and the rest work against it (screenshots land in the instance's
  `.minecraft/screenshots`); `swap` and `start` do not. It opens at 854x480 whatever Prism's settings say:
  `call 'window?w=1920&h=1080'` (`scale=` sets the GUI scale). It runs Java 21, so it runs the fully downgraded
  classes (`gradle.properties`: the partial downgrade to 21 broke pattern switches), which the dev game on Java 25
  never does. Its harness answers on port 25601 (`-Dgtnhplanner.dev.port` in its JVM arguments), so it runs beside a
  dev game: `PLANNH_DEV_PORT=25601 tools/dev/mc.sh call ...`. Before sending input, check which game answers (a
  screenshot's path names its folder): sessions share these ports.
- The board has its own GUI scale (`ui/PlannerScale`, the gear's Size: Tiny, Small, Normal, Large; Normal is a step
  below the game's, never below 2; Large is the game's): it lays out in its own pixels and draws scaled to the game's,
  while NEI's list beside it keeps the game's. Two mixins carry the mouse (`GuiContextMixin`) and NEI's hidden slots
  (`MuiNeiHandlerMixin`) across; NEI's drop converts in `FlowchartGuiHandler`. Anything measuring the screen on the
  board uses `PlannerScale`, not `ScaledResolution`. While the tour plays the board's scale is the game's, or smaller where that leaves it under 960x540.
- Input endpoints and `shot` crops take GUI coordinates (960x540), not screen pixels: the game's, also on the board
  (`call board` and `widgets` report the board's parts converted to them). On the board, act on a card
  control by name: `mc.sh part 1 MACHINES` (from `call board`, which also returns each card's state). Elsewhere
  find targets with `call widgets` and check `hovered`/`focused` in `call status` rather than estimating.
- `mc.sh stop` kills the game on purpose: a clean quit with GT loaded hangs on a "really close?" dialog.
- GTNH Planner text fields need a double-click (`click?x&y&count=2`) before `type` works.
- After changing mod code: `tools/dev/mc.sh swap --reopen`. Restart instead (`mc.sh restart`, ~30s) when swap
  exits 2 (new or not-yet-loaded classes), for mixins, resources and startup-only code, and when you add a field
  with an initialiser: widgets already on screen get it as null, and a draw that touches it crashes out of the world.
  Decide before swapping: swap applies every class it can before it exits 2, so swapping code that uses a new class
  or an initialised field on an existing object (an event handler's `INSTANCE` too) crashes the user's game at once. So
  does removing a field (a frame mid-draw still reads it) or changing what an anonymous class captures (its
  constructor changes): restart for those too.
- The game window opens on the user's desktop at 1920x1080, GUI scale 2, muted (`PLANNH_DEV_SOUND=1.0` for sound,
  `call 'sound?volume=1'` live); keep it that way. Leave it running while the user is iterating on the UI with you;
  stop it when the work is done.
- The user can press F2 in game to screenshot what they see; when they refer to "this" or "my screenshot", Read
  the newest file in `run/client/screenshots/`. Harness screenshots land there too, under the names you gave them.
- Plans of thousands of cards: `call 'stress?copies=N'` tiles the open plan, `stress?world=1` places every machine,
  `stress?animate=pan` moves the camera for `perf` (`docs/design/performance-at-scale.md`: numbers, what was slow, the
  profiling steps). Only the board's cards and drawers in view are ModularUI's children (`BoardCanvas.getChildren`;
  the rest are built but not walked); placed machines are found through `ui/world/WorldIndex`; wires look again only
  when the plan, its solve or the board's placing moved something (`WireLayer.moved`): anything new that moves cards
  outside an edit must call it. A hot swap of a class whose nested record or class it constructs crashes the game
  (IllegalAccessError): restart instead.
- Performance: `call 'perf?start=1'`, do something (drag, pan, open a plan), then `call perf` for FPS, frame-time
  percentiles and per-part timings (cards, drawers, wires, overview, routing, solve; over the world `ar.*`: ghosts,
  wires, labels, cards, tags). Wires route on the router's
  thread (`layout/WireRouter`), incrementally: a drag, a new wire or a new card re-routes only what it touched, and
  the frame waits for a route expected back within 10 ms. They are drawn from a display list; keep per-frame drawing batched (`Hyb.beginBatch`). Dev runs keep
  each plan opened from the Library in `run/client/library-downloads/` for `call 'importff?file=...'`.
- UI feedback loop: change code, `swap --reopen`, check with a cropped `shot`, then tell the user it's live in
  their window. They can interact with the game at the same time; just don't send synthetic input while they are
  mid-action. Gate input and restarts on `tools/dev/mc.sh idle`: it succeeds when the game window is unfocused or
  nothing has come from their mouse or keyboard for 30 s (`idleSeconds` in `call status`). A focused window alone
  means nothing: the game takes focus when it starts and keeps it while they work elsewhere.
- Trailer footage and the cut: `tools/trailer/README.md`. The game records its own frames (`call record`), the tour's
  cursor can build any finished plan as a player would (`call 'rebuild?file=...'`, `ui/tutorial/Rebuild`), and
  `tools/trailer/Trailer.java` cuts a shot file (`trailer.shot`) to synthesized music (`track.mjs`). The owner wants it
  plain: the whole screen, no tilt or drifting camera, few captions.
