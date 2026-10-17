# The tour

A guided tour, not a video: the planner drives itself in your game, a step at a time, and a note beside what it did
says what it is. Placing machines in the world is not in it (that will be a video). Built in `ui/tutorial/`
(2026-10-09); this file is what it does and how.

## How it looks and feels

- **Steps, not a show.** Each step acts something out at a brisk pace (the cursor clicks, drags, types), then stops on
  a note and waits. Nothing moves on until the player says so. Thirty-seven steps; the rest is the player's reading. Two of them hand the
  board's camera to the player instead (`freeLook`, below).
- **The note** (`Callout`): a dark box with a gold edge beside the thing it is about, a tip pointing at it, and
  the thing framed in gold (the first card shows bare, so it is seen as it is: `noteBare`). It is drawn a size up
  from the game's text, one more screen pixel to each pixel of the font, so it reads easily and stays crisp. A note
  can also point at nothing (`noteAt`, placed on the screen): split into lines by new lines, a small mouse at the
  start of a line ({M}, {W}, {L}) in a column of its own, the line the tour is showing lit (`line`). Words can be
  the inputs' blue or the outputs' orange ({b:Blue}, {o:Orange}). A note whose target never shows says its piece in
  the middle after a moment rather than leave the tour waiting. Its words are short and plain; the words that matter most are gold (written between
  asterisks in the script). It sits where it covers least of the board's cards, drawers, notes, popups and the
  overview: below the thing if there is room, else above, right or left, slid along that side, and off the tour's
  cursor. The note fades and slides in.
- **The keys** are in the note: × (leave) on the left, ◀ (back) and ▶ (next) on the right. ▶ turns gold when the step
  is done; on the last step it is a tick. The right arrow, Space or Enter is next, the left arrow back, Esc leaves.
  While a step plays, next (or a click anywhere) hurries it to its note. The function keys stay the game's.
- **No chapters, no titles, no counts.** The tour starts at once from the "?" key.
- **The tour's cursor**: a gold arrow (so it never passes for the player's) that glides on gentle arcs, dips and
  leaves a ring when it clicks, and fades when it steps aside (off the right edge of the screen, so it rests on
  nothing). A small mouse beside it shows the button it uses when that is the point (the middle button's pan, the
  wheel, a left drag). Keys it presses show as key caps beside it (R, Shift,
  Esc, the minimap's keys), a wheel turn as a small arrow. Key names come from the player's own bindings (NEI's R and
  U, the minimap's keys).

## Where it starts

- The "?" key on the top bar: **Take the tour** and **Controls and shortcuts**.
- The first time the planner opens, an offer along the bottom of the board: **Take the tour** or **Not now** (either
  puts it away for good; `tour.offered` in the settings file).
- The tour plays on the planner: it opens it when it is not open, and starts from NEI's search under it.
- Dev harness: `call 'tutorial?start=1'`, `call 'tutorial?step=N'` (start or jump there), `call 'tutorial?next=1'`,
  `call 'tutorial?stop=1'`, `call 'tutorial?export=1'`; every call returns where the tour is ("step 5 of 35 waiting:
  ...").
- The game's GUI scale is left alone. The board takes a scale of its own while the tour plays
  (`PlannerScale`): the game's, or smaller where that would leave the board under 960 by 540, the room its moves are
  laid out on (GTNH ships scale 4, a 480 by 270 GUI at 1080p). `Targets` gives every board rectangle in the game's
  pixels, where the tour's pointer is.

## Safe to run

`Sandbox` keeps the player's things apart:

- **Plans**: the player's are put aside (`Plan.enterSandbox`) for plans of the tour's own, which `PlanAPI.save`
  never writes. Leaving puts the player's back as they were, and the plan in front of them, if the tour put anything
  on it, joins them as "Tour plan", the one shown. A world closing mid-tour keeps nothing.
- **What else it touches** is copied first and put back: the machine picked per NEI tab, the settings remembered per
  machine, the minimap's settings and the pin list's, and NEI's item groups it opened. NEI's search stays as it is.
- **It ends where the player is**: the board stays open (laid out again at their own size), NEI's page stays, and
  from the world steps they are left playing.
- **The Library** is only browsed; nothing is signed into, posted or opened into My plans.

## How it drives the game

- **`Pointer`**: while the tour drives, the mouse position every screen is drawn with is the tour's (a mixin on
  `EntityRenderer.updateCameraAndRender`), and so is NEI's own reading of it (a mixin on `GuiDraw.getMousePosition`).
  Hover, highlights and tips all follow the tour's cursor; the player's real cursor is never moved. A third mixin
  makes `GuiScreen.isShiftKeyDown` say yes while the tour Shift-clicks (adding a card to the selection).
- **`VirtualInput`**: presses, drags, releases, wheel turns and keys go to the open screen the way the game sends the
  player's: ModularUI's screen first, then the screen itself and NEI's hooks. Nothing goes through the operating
  system, so it works the same on Java 8 and under lwjgl3ify.
- **Typing**: with lwjgl3ify a key press carries no character (text comes as events of its own), so the tour types
  into text fields by setting their text a letter at a time (NEI's search, the planner's number boxes, the Library's
  search); a sticky note takes typed keys directly.
- **`Targets`**: everything is pointed at by name, looked up afresh each frame: NEI's planner button, its search, an
  item in its list, a recipe tab, a recipe's plan button and its menus; the top bar's keys, a card's parts and ports,
  drawers, notes, overview rows, plan tabs, popup rows, the settings rows, the selection bar, the non-recipe machines,
  the Library's parts, the minimap; and free board beside a port for a new drawer. A board target off the visible
  board pans the board to it first, so the cursor never clicks what cannot be seen.
- **`Director`**: plays a step's actions a frame at a time, then waits. The tour's plans are saved at the start of
  every step that starts on a settled screen; Back puts those back (plans, screen, NEI's search) and plays the step
  again, or hurries from the nearest saved step before it.
- **Hurrying** (next part way through a step, Back to a step whose start was not saved, the harness's jumps) runs the
  actions without their glides, but never faster than the game takes them: the pointer still rests until the screen
  has drawn twice with it there (what is under the mouse is found as the screen draws, and the screen does not draw
  on every frame), on a target that has stopped moving and a board at rest (no zoom easing, no fling gliding, no cards
  being built afresh); a drag still glides (ModularUI takes no drop from a single jump, nor from one let go within
  100 ms); a pause is still a game tick; and after a jump nothing runs for 400 ms while the screen builds itself.
- **Starts**: the plans at the start of every step, from a full run (`call 'tutorial?export=1'` writes
  `assets/gtnhplanner/tutorial/starts.json`), so the harness starts any step at once. Re-export after a script change
  that changes what a step starts from.
- **Recipes** are found the way a player finds them: by name in NEI's list (this pack lists fluids by their cells; a
  cell dropped on the board is its fluid), and on NEI's page by what goes in and comes out, so a pack that moved a
  recipe still finds it, or the step carries on without it.

## The steps (`Script`)

From NEI to a card:

1. NEI's search: "iron plate", R, the bending machine's page: the plan button.
2. A new plan with the bender on it, shown bare: a recipe that does nothing yet.
3. Getting around with the mouse, as a note pointing at nothing (middle mouse, scroll, left click), the plan framed
   first; then the player tries it.
4. The same with the keys (W A S D or the arrows, Page Up and Page Down or + and -); the player tries it, and the
   next step frames the plan again.

Numbers to solve for:

5. 10/s typed into the plates' rate box: pin a number and it works out the others.
6. Back to Any, the machine count pinned instead.
7. The count cleared, the ingots' rate set instead.
8. The tier.
9. Power (shown, never wired) and the circuit.
10. The settings panel; pinned settings show on the card.

Drawers:

11. Clone (the clone lands on the nearest clear spot under its card, and Fit).
12. The plates dragged out into a drawer.
13. Iron ingots dragged in from NEI's list as a source drawer.
14. The clone's plates wired to the plate drawer, the ingot drawer dragged to both machines, the plates set to 20/s and
    one machine pinned: drag from a drawer to wire it; a drawer's rate applies to every machine wired to it.
15. Arrange and Fit.

The overview:

16. The overview.
17. Blue is always coming in, orange always leaving.
18. The machines in it.
19. Shift-click to select both, merge: two recipes on one machine.
20. Undo, the merge undone; redo beside it.
21. The rate, power and average keys turned round.

Forced inputs and outputs:

22. Sulfuric acid from NEI added to the same plan (the reactor's water, oxygen and sulfur recipe), set to 20/s.
23. A click on its oxygen input: NEI's page of what makes it; the plain electrolyzer's water recipe added, landing
    wired in.
24. The electrolyzer pinned to one: it cannot keep up, and the reactor's oxygen shows its Forced row: a forced input.
25. A right-click on the acid output: NEI's page of what uses it; the mixer's sugar and acid recipe added, wired in.
26. The mixer pinned to one and the electrolyzer high: more oxygen made than used, the excess leaving the plan as a
    forced output (the overview's Forced block).
27. Its slider: a click on each place's bar (all as oxygen, all as acid, then half each): where the excess leaves.

Products, trash and power:

28. The distillation tower from NEI; its first side output dragged out and made trash from its rule key's menu.
29. Back to a product.
30. Non-recipe machines.
31. A gas turbine at HV on the tower's benzene, every other flow given a drawer, the tower pinned to one: how many
    turbines it runs.

Plans, the Library and notes:

32. The + menu: a new plan.
33. The Library's public setups, searched.
34. A sticky note (after Fit, for room): added, written on, moved, resized, coloured, its text bigger.

In the world:

35. A card's pin button: Place in the world, and Pin to the screen.
36. Pinned to the screen; the minimap set up large, square, top right, the pin list bottom right; over the world, the
    minimap moved and zoomed with its keys.
37. Back on the board at the Feedback key: that's everything.

## Open

- Voice: each step's note could carry a clip (`assets/gtnhplanner/sounds/tutorial/`).
- The world-placement video.
