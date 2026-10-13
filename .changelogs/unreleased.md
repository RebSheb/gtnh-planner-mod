## New

- **Pipe routes in the world** – placed machines show the blocks of pipe between them, and the hatch the pipe leaves from. That hatch sits where the machine actually allows it (a Distillation Tower's input stays on the bottom, and each distilled fluid leaves from its own height — Benzene from Wood Tar on layer 5). A 2× card pipes each placed machine — first to first, a leftover to another hatch on the same target — and each run is sized on its own share. Each item or fluid takes its own hatch and its own blocks — Light Fuel and Fuel do not share, and a single-block machine gives each one its own side — and the pipes join at corners so a run looks like one line. The coloured wire follows those blocks. A run no longer walks through a neighbour and loops back into the controller, and a pipe only docks the face it leaves from. In the gear, under In the world, Shared bus joins machines moving the same thing onto one pipe.
- **Pipe sizes in the world** – a wire between two placed machines names the item or fluid pipe that can carry what that wire moves. The smallest pipe that fits, and more than one when a single pipe can't.
- **Fluid Tank** – a new card under Non-recipe → Other. Wire a cell or a fluid to it and it empties or fills cells for free, no Canner needed.
- **Split your leftovers** – when your machines make more than the plan uses (or not enough), the overview shows where the extra goes. Hit Split to share it out, or Auto to let the planner pick.
- **Surplus and Imported rows** – a card now shows what leaves or comes into the plan at a wired port with no drawer. Drag that row off to turn it into a drawer.
- **Update heads-up** – the planner tells you once when a new version is out.
- **Bigger tour** – the "?" tour now goes all the way from NEI to placing machines in the world. Quit it whenever and you keep the plan you built as "Tour plan".

## Better

- Dragging a wire lights up every card and port that can take it. Wire arrows are fewer, bigger and lighter, on the board and in the world.
- The plan over the world is calmer: a machine's card only pops up above it when you look at it (Shift + U), and each machine gets a small label with its name, tier, power and count (Shift + I). Wires say what they carry, always or only for the machine you look at. The gear explains every option, with its key. Machines can show as see-through, solid or not at all, and the keys list in the corner can be hidden (all in the gear).
- The settings show a live picture of what the world and minimap options do.
- Every world key (turn, pick up, remove, Focus, Labels) can be changed in Controls, and the minimap can be made see-through. It also shows whole cards one zoom step sooner.
- Rates you set on a card's port show up in the overview, and "~ Set" puts a rate on anything nothing sets yet.
- "(set count)" only nags on the part of the plan that actually needs a number.
- Steam machines lose the voltage chip (they don't use EU) and show how much steam they burn instead.
- Custom Rate moved into the new Other column in Non-recipe.
- Zooming out switches cards to the simple view a step later, so the text stays readable longer.
- The tour doesn't touch your GUI scale anymore.
- Plans solve several times faster on a computer with a few cores to spare, settle on fewer leftovers when a setup can go more than one way, and the same plan always comes out with the same numbers.
- Huge plans stay smooth: thousands of cards pan and zoom without slowing down, open much faster, and the plan over the world keeps up with thousands of placed machines. Wires no longer flicker when zoomed far out.
- Resource packs can restyle the plan button on NEI's recipe pages, like NEI's own buttons.

## Fixed

- Pasted plans (links and codes) get the right machines: no more Basic Extractors turning into Steam Extractors, or HV machines showing up as LV ones.
- Crop farms in pasted plans come in now, and crop cards share to the library properly.
- Generators in pasted Build plans keep their count instead of getting resized.
- Cards in pasted plans don't land on top of each other.
- Placing machines in the world: you can stack the next one on the one you just placed, and you can see which way R turns it. Each machine of a group says which it is (1/4, 2/4...).
- Placing a generator in the world shows its name, not a code.
- The rate unit (/min and friends), EU/t or Amps, and Peak or Avg stick when you close and reopen the planner.
