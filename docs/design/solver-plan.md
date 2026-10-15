# The solver: what it is for, what is wrong with it, and the plan

Written 2026-10-12. The solver is `data/flowchart/balancer/`: an ojAlgo LP/MILP chain run on `SolveService`'s
thread. This file is the master plan for making it fast without changing what it answers, and for the few places
where what it answers is wrong. It grew out of the scale tests (`performance-at-scale.md`).

## 1. What the solver is for

A player wires recipes together and sets something: a machine count, a product's rate, a drawer's rule. The
solver answers three questions:

1. **How many of every machine?** Every wired resource must balance: what is made equals what is used.
2. **Where does the mismatch go?** Recipe ratios rarely line up (an electrolyzer makes hydrogen and oxygen 2:1, the
   machine after it wants 1:1), so a plan almost never balances exactly. Something leaves the plan as a surplus or
   comes in as an import, at some wired port. Each such place is a **gate**: every resource network (the ports one
   wire tree joins) has an import gate and a surplus gate. Every way of placing the mismatch is a valid plan.
3. **Where else could it go?** The other valid places, so the player can pick one or split a leftover between
   several (`Spares`, the leftover popup).

So there is one answer when the plan balances exactly (the fast path, one LP), and many when it does not; then
the solver picks by fixed rules, in order (the owner's, 2026-10-13; phase 7 below):

1. buy as far upstream as it can: never buy a good with more machine steps in it while one with fewer would do, so
   the plan buys raw goods and runs its own machines; and never buy more in order to sell more;
2. sell as far downstream as it can: spare goes on to every machine that can take it and is sold only where nothing
   further on can (a pinned machine is a brick wall);
3. the least flow inside the plan, then a canonical tie-break so equal answers always come out the same.

A machine nothing pinned feeds or needs is left at zero, and the answer says so (`SolutionView.unneeded`): the board
asks for a count there, as it does for a part of the plan nothing sets.

### The contract, in priority order

1. **Correct.** Every number shown conserves (`SolveContext.validate`); a plan that cannot balance says why.
2. **Deterministic.** The same plan with the same choices and splits gives the same numbers on any computer, at any
   load, with any number of threads.
3. **Independent.** A part of the plan never changes because of a part it shares no wire, shared machine or drawer
   rule with.
4. **Explainable.** The default follows the rules above; every other valid place for a leftover is reachable.
5. **Responsive.** The board never waits. A plan of a normal size (up to about 200 machines) answers in well under a
   tenth of a second, everything included; an edit costs what it touched, not the whole plan.

Speed work may never break 1 to 4. Today 2, 3 and 4 are broken and 5 is far off; sections 3 and 4 have the evidence.

## 2. How it works today

`Balancer.solveWithAlternatives(SolveInput)`, AUTO mode (`BalanceMode.AUTO`):

| Step | Code | Models |
| --- | --- | --- |
| Fast path: does the plan balance with every gate closed? | `FastPathStage` | 1 LP |
| Trades, level by level: buying at each machine-step count (most first), then selling at each | `TradeStage`, `Solver.levels` | one LP a level |
| Least flow, canonical tie-break | `FlowMinStage`, `Solver.canonicalHolding` | 2 LPs |
| Drawers that cannot be kept are loosened and the chain runs again | `DrawerRelaxation` | rerun |
| Alternatives: every open gate swapped for every closed one, each tried | `Enumerator.enumerate` | up to 1024 LPs, then 2 per kept option |
| Leftover splits mixed from the alternatives' points | `Spares.apply` | none |

Every model is built from scratch from `ModelData` and solved by ojAlgo's `ExpressionsBasedModel`. The table is as of
phase 7; sections 3 to 5 describe the solver as it was before phase 1.

## 3. What we measured

Tools (all in `src/test/java/com/gtnhplanner/solver/`, tagged `bench`, outside the normal test run):

- `SolverCorpus`: 14 real plans exported from the game (`call 'solvedump?dir=...'`, `dev/SolveInputJson`), the
  gtnh-flow charts, and platline tiled 4 and 10 times (unconnected copies).
- `SolverGoldenTest`: every corpus answer (the solution, alternatives, notes and splits) compared against goldens
  recorded from the solver before any of this work (`solver-corpus/goldens/`), to 1e-6 relative. Run with
  `Budget.timeless(true)`: no wall-clock limit fires, so a run is reproducible whatever the machine is doing.
- `SolverBench`: least wall and CPU time over several runs. `SolverCensus`: every model each plan's chain runs.
- Profiles: `-Pjfr` writes `build/bench/profile.jfr`.

Numbers are compared before against after on one machine in one sitting, never against other days' numbers (the
owner games on the same computer).

### Times, timeless (all the work a solve is meant to do)

| Plan | Machines | Open gates | Before | Solving side by side (`Parallel`) |
| --- | --- | --- | --- | --- |
| platline | 49 | 50 | 0.99 s | 0.25 s |
| mv | 33 | 31 | 0.76 s | 0.23 s |
| palladium_line (gtnh-flow) | 56 | 9 | 2.4 s | 0.56 s |
| 230_platline (gtnh-flow) | 28 | 2 | 1.03 s | 0.97 s |
| nanocircuits (gtnh-flow, fast path) | 394 | 0 | 0.95 s | 1.0 s |
| platline x4 | 196 | 200 | 9.1 s | 1.7 s |
| platline x10 | 490 | 500 | 67 s | 12.7 s |

In the game (wall-clock limits on, a busy machine): 49 cards 1.8 s, 490 cards 34 s, 1,960 cards did not finish in
15 minutes (`performance-at-scale.md`).

### Where the time goes

Profile of the old solver: 85% in ojAlgo's presolve (`ExpressionsBasedModel.presolve`, `Presolvers.doCase2`), doing
`BigDecimal` and `BigInteger` arithmetic; 11% simplex and branch-and-bound; 0.5% building models; about 4% our own
code. By step: deletion filter 69%, alternatives 15%, gate MILP 10%.

Census of the chain (alternatives not included), timeless:

| Plan | Gates | Deletion-filter LPs | Their time | Per LP | Gate MILP |
| --- | --- | --- | --- | --- | --- |
| platline | 106 | 51 | 65 ms | 1.3 ms | 53 ms |
| platline x4 | 424 | 201 | 1.5 s | 7.6 ms | 219 ms |
| platline x10 | 1,060 | 501 | 22 s | 44 ms | 2.2 s |
| 230_platline | 62 | 6 | 2 ms | - | 3 MILPs, 493 ms; quantity MILPs 346 ms |
| nanocircuits | 786 | 0 | - | - | fast path; canonicalise 1 LP, 828 ms |

Platline's chain is about 120 ms of its 991 ms: **about 87% of a platline solve is the alternatives search**, and
section 4.1 shows its answers are thrown away.

Why one LP of platline x10 costs 34 times one of platline at 10 times the size: ojAlgo's `presolve()` restarts its
whole pass over the rows after every variable it fixes (`Presolvers.ZERO_ONE_TWO`), in `BigDecimal`, so it costs
about fixings times rows, and both grow with the plan.

## 4. What is wrong

Worst first. File references are to `balancer/` as of this writing.

### 4.1 The alternatives never reach a real plan (contract 4)

`Enumerator.enumerate` puts one "what it does now" row per open gate into `options` (`Enumerator:68-70`) before any
swap is evaluated, and evaluation stops once `options` holds `maxAltOptions` (8). So with 8 or more open gates no
real alternative is ever evaluated: the list is cut to 8 current rows, `complete` is false, and `Spares.apply` lists
only those 8 leftovers, each with one place and nothing to choose. The swap LPs (up to 1024) still all run first.

| Corpus plans | Open gates | Real alternatives |
| --- | --- | --- |
| alumina line, cetane, jet fuel, two_decisions, symmetric_choice, hv-diesel | 1-4 | 1-5 |
| pa-cell loop, titanium line (tour), glass, sulfuric acid, palladium_line, titanium (chem bath) | 8-12 | 0 |
| nitrobenzene power plant, mv, full platline, platline | 20-50 | 0 |

Every real plan in the corpus past toy size offers no choice, and most of its solve time buys nothing.

### 4.2 The clock decides answers (contract 2)

Wall-clock limits change what a solve does, not only when it stops: the alternatives' 750 ms (half for swaps), the
20 s solve budget, each model's time limit (`ModelBuilder:59-64`), the quantity MILP's lack of a node budget, and
the gate MILP's proven-optimal flag (which reads the clock, `Solver:152-165`). A busy computer finds fewer
alternatives, and a stored leftover split whose place was not found this time is spread over the places that were
(`Spares.shares`), so the plan's numbers move. With `Parallel`, a budget that runs out mid-batch skips a set of swaps
that depends on the thread count (`Enumerator:116`).

### 4.3 One plan, one model (contracts 3 and 5)

Unwired parts of a plan are solved as one model, so the work grows with the square of the plan: ten copies of
platline cost 68 times one. Worse, unrelated parts change each other's answers:

- a part that cannot balance fails the whole plan, and AUTO then shows no counts anywhere (`Balancer:214-221`);
- a part whose flows are under a millionth of another part's loses its gates (`SolveContext.gateSupport` measures
  "zero" against the largest flow in the plan);
- the "every machine runs" floors are set from the smallest machine anywhere (`SolveContext.machineRunFloors`);
- disagreeing pins are named across the whole plan (`diagnosePins`), so innocent pins elsewhere get blamed;
- the fast path is all or nothing, and the caps (8 options, 1024 swaps, 64 tied supports, 512 MILP nodes) are shared
  by every part;
- in the alternatives, 9 in 10 swaps on platline x10 pair gates in different copies, which can never succeed.

### 4.4 Every check is a whole new model

The deletion filter asks "does the plan still solve with this gate closed?" once per open gate, each time building
the model from nothing and running ojAlgo's presolve on it (85% of the time, in exact `BigDecimal` arithmetic). The
same holds for every swap. An LP that differs from the last one by one bound is the textbook case for re-solving
from the previous basis (section 5).

### 4.5 Edits wait on old work

`SolveService` never cancels a running solve: an edit made during a 30 s solve waits for it to finish and be
thrown away. Every edit re-solves every part of the plan. The snapshot (`SolveInput.of`) runs on the client thread,
and its resource matching is O(ports x resources) of ore-dictionary comparisons (`SolveInput:282-289`).

### 4.6 Smaller things

- Ties go to whichever optimal vertex ojAlgo returns: the deletion filter reads the LP's support after each success
  (`GateCountStage.deletionFilter`), so the answer depends on the solve path. Turning presolve off changed
  230_platline's answer.
- Sorting on exact doubles (`gateFlow` in the deletion order, `externalQuantity` in the shortlist) lets float noise
  decide before the key tie-break.
- `canonicalize` silently returns the uncanonical point when its LP fails (`Solver:295`).
- The solve budget is said to scale with effort (`Numerics`) and does not (`Balancer.ctxOf`).
- Our benchmark's big plans are unconnected copies: the easy case once parts are solved alone. Connected scale needs
  its own plan.

What holds up: every answer is checked independently before it is shown; equal answers are canonicalised; the
corpus answers are reproducible under `Budget.timeless`; and platline x10's machine counts are exactly ten copies of
platline's (checked against the goldens), so splitting the plan into parts has a ready-made oracle.

## 5. What others do, and what the literature says

Research notes, 2026-10-12.

**Other planners.** Most solve one weighted LP: surplus free or cheap, imports and raw inputs expensive, a small cost
per recipe, lexicographic order imitated with weights orders of magnitude apart. The player picks what may be in
surplus (YAFC's links, Factory Planner's free items, Helmod's main product, Kirk McDonald's priority tiers). None
re-solves per candidate or runs a MILP. For speed: Foreman2, a wired graph editor like ours, solves each connected
component on its own; Factory Planner caches the simplex basis per floor and warm-starts; FactorioLab only models
recipes the targets reach; Kirk McDonald's calculator walks the plain tree directly and sends only the part with
real choices (several outputs, cycles) to the LP, in exact rationals. gtnh-flow solves equalities only with SymPy,
and its author found per-item costs impractical for GTNH, which argues for our structural rules (fewest leftovers)
over costs. Sources: kirkmcdonald.github.io/posts/calculation.html, github.com/factoriolab/factoriolab
(src/solver/solver.ts), github.com/ClaudeMetz/FactoryPlanner (modfiles/backend/calculation),
github.com/shpaass/yafc-ce (ProductionTable.cs), github.com/DanielKote/Foreman2 (ProductionSolver.cs),
github.com/OrderedSet86/gtnh-flow (src/graph/_solver.py).

**The gate search.** "Feasible with only these gates open" is monotone (opening more never hurts), so step 1 is a
minimal set over a monotone predicate (Marques-Silva, Janota, Belov, CAV 2013); the minimum is fixed-charge flow,
NP-hard (Amaldi, Pfetsch, Trotter 2003). A deletion filter run in a fixed order, each test made against the set as
decided so far, returns one well-defined answer: the lexicographically first feasible one (Chinneck, Feasibility and
Infeasibility in Optimization, 2008). Ours is not that: after each success it shrinks the set to the LP vertex's own
support, so its answer depends on which vertex ojAlgo returns. A vertex may still be used to skip a test whose answer
it already proves, which changes nothing. QuickXplain (Junker 2004) reaches the ordered filter's answer in about
2k log(n/k) tests instead of n. Exact minimum without big-M: cores (sets of gates that cannot all close, read off
failed tests) and a small hitting-set master (Codato and Fischetti 2006; Moreno-Centeno and Karp 2013); k disjoint
cores prove a k-gate answer minimal with no MILP.

**Lexicographic levels.** Solve in sequence and hold each level by fixing what its optimum fixes (Gurobi's method
for multi-objective LP) rather than by a tolerance row; packed weights drown the lower levels in floating point.

**Splitting.** When the rows split into blocks and every level is a sum over blocks, the optimum is the product of
the blocks' optima, level by level, and the ordered deletion filter splits the same way; a swap across blocks never
succeeds. What couples blocks: shared rows (pools, drawer rules), tolerances relative to the whole, global tie-breaks
and caps, shared budgets.

**Determinism.** Commercial solvers bound work, not time, for reproducible results (Gurobi's WorkLimit, CPLEX's
deterministic ticks, SCIP's node and LP-iteration limits); the clock only cancels.

**ojAlgo 57.3.1**, checked against the jar and the v57.3.1 source:

- `presolve()` hard-codes `ZERO_ONE_TWO` and restarts after every fixing; it is all or nothing in 57.3.1. Presolvers
  are stateless, so models on separate threads are safe (configure the `Environment` once, never while solving).
- Re-solving in place exists: `model.prepare(...)` gives an `IntermediateSolver`, and `update(index, lower, upper)`
  then `solve` re-optimises from the previous basis (dual simplex), presolving once. A variable presolve fixed is
  silently not updated, and an infeasible result forces a cold rebuild. A warm solve may return a different tied
  vertex than a cold one.
- MILP answers are not reproducible across game launches even on one thread: the objective cut-off row is keyed by
  `UUID.randomUUID()` and cut names come from a JVM-wide counter, which reorder rows. Ties can differ per launch.
- 57.4.0 (2026-10-08) runs `ZERO_ONE_TWO` only when registered, scales with fewer digits and has a faster sparse dual
  simplex; it targets Java 11. Any of that can change tie answers.
- No pure-Java solver beats ojAlgo. Native ones (HiGHS, OR-Tools) mean per-platform binaries in the jar and a native
  crash taking the game down.

## 6. The design we want

Not a rewrite: the stages, the rules and ojAlgo stay. Four things change around them.

1. **The answer is a property of the plan, not of the solve path.** Every choice the solver makes is fixed by the
   plan and stable keys: the deletion filter runs in a fixed order (taken from a canonical first point), tests
   against the set as decided so far, and uses an LP's vertex only to skip a test it already answers; sorts never
   hinge on float noise; MILP certification does not depend on row order. Equal answers are already canonicalised
   at the end. Once this holds, how a solve runs can change freely without changing what it answers. This is the one
   deliberate change to answers, on tied plans only, reviewed plan by plan.
2. **Islands.** A plan splits into islands: what wires, capped shared machines and bounded drawer rules join. Each
   island is solved by the same chain as if it were the whole plan, side by side, and the results are merged in a
   fixed order. Notes, failures, floors and caps are per island, so a broken island leaves the others solved. Islands
   are cached by content: an edit re-solves its own island, and undo finds the old answer.
3. **Alternatives per leftover.** Every leftover lists the other places in its island that could take it, up to a cap
   per leftover, so the leftover popup has real choices on real plans. Only swaps inside the island are tried.
4. **Work is counted, not timed.** Limits count models, branch-and-bound nodes and iterations, the same on every
   computer. The clock only cancels: a newer edit stops a running solve between models. The answer is published
   first and its alternatives a moment later.

Then, safely, the cost of each model: one presolve per island and warm re-solves from the last basis for every
"close this gate" test and every swap; zeros a closed gate forces, fixed by us before ojAlgo sees them; ojAlgo 57.4.0
once it has a few weeks behind it.

Not doing: one weighted LP like the other planners (it changes the rules players see, and per-item costs do not fit
GTNH); native solvers; shipping patched copies of ojAlgo's classes (fixes go upstream instead).

## 7. Phases

Each phase lands on main by itself, with the goldens, the full test suite and a bench comparison in its commit
message. A phase that changes answers on purpose re-records the goldens and lists every plan whose answer moved, and
why.

| Phase | What | Answers | Expected |
| --- | --- | --- | --- |
| 0 | The harness: corpus, goldens, `Budget.timeless`, bench, census; add a connected platline x10 (copies chained by wires) and the island oracle (x10 answers ten copies of platline's) | unchanged | connected scale measured |
| 1 | Solving side by side (`Parallel`), budgets checked per batch so the thread count never matters | identical (verified) | 4-5x on plans with many gates |
| 2 | Canonical answers (6.1) | ties move once, reviewed | nothing by itself; unlocks 6 |
| 3 | Alternatives per leftover (6.3) | real alternatives and splits on real plans | swaps across islands gone |
| 4 | Islands and their cache (6.2) | per island; plans with several islands change where they leaked (4.3) | x10 from 12.7 s to about 1 s; an edit re-solves one island |
| 5 | Counted limits, cancelling, answer then alternatives, a cheap snapshot (6.4, 4.5) | the game matches the goldens | edits never wait on stale work |
| 6 | Cheaper models: warm re-solves, one presolve per island, forced zeros, ojAlgo 57.4.0 | identical after 2 | the 85% presolve share |
| 7 | Optional exactness: cores and a hitting-set master instead of the big-M MILP; plain chains solved directly, only the part with real choices sent to the LP | exact minimum counts | large connected plans |

Goals, checked at every phase: a plan of up to about 200 machines solves, alternatives included, in under 0.1 s; an
edit to one island of any plan costs that island; the 1,960 and 19,600 card stress plans solve.

## 8. Decisions for the owner

1. **Canonical answers (phase 2).** Accept one reviewed round of changed tie answers so that answers never again
   depend on how the solver got there. Without it, phases 6 and 7 must keep today's exact solve path, and most of the
   per-model speed stays out of reach.
2. **Alternatives per leftover (phase 3).** How many places a leftover may list (the cap is 8 for a whole plan
   today).
3. **Islands answer as if alone (phase 4).** A broken island stops blanking the whole plan; the few plans with several
   islands that leak today change.
4. **Answer first, alternatives after (phase 5).** The leftover popup may fill in a moment after the numbers.

## 9. How every change is checked

- `./gradlew test --tests '*SolverGoldenTest*' -Pbench=check`: every corpus answer against its golden, timeless.
- The island oracle and the connected x10 from phase 0.
- `./gradlew test -Pbench=<label> --tests '*SolverBench*'` before and after, in one sitting; `*SolverCensus*` for
  model counts; `-Pjfr` for a profile.
- The full suite (`./gradlew test`) and `./gradlew compatCheck`; the stress plans in the game
  (`call 'stress?copies=N'`, `performance-at-scale.md`).
- A player-visible change gets its line in `.changelogs/unreleased.md` in the same commit.

## 10. Progress

**Phase 1** (2026-10-12): `Parallel`, identical answers, 4-5x.

**Phase 2** (2026-10-12): the gate count is `GateSearch`, exact and canonical, built from LP feasibility questions
only; the big-M MILPs (`gateMILP`, `quantityMILP`, their cuts and node budgets) are gone. What the work found:

- ojAlgo's presolve declared sets of gates infeasible that balance (230_platline's second pass), and the gate MILP
  claimed 2 gates optimal where 1 balanced, and in another run that nothing balanced at all. Our models now run in an
  environment with no presolvers (`ModelBuilder`), which also removes the 85% presolve share.
- Without presolve, one simplex set-up can still call a model infeasible that another solves, or return a point that
  does not conserve. Every AUTO model now runs through `Solver.run`: a point must pass `SolveContext.validate`, and
  "no solution" is believed only when both set-ups (Ruiz-scaled revised simplex, then ojAlgo's own pick) say so.
- The conservation check compares a port's residual with what the port carries, not its per-craft amount.
- The every-machine-runs floor, when no unpinned machine ran, was 1e-7 crafts/s: down in the simplex's rounding on a
  plan moving thousands a second, so its answer was noise. It is now a thousandth of the slowest pinned rate.
- The canonical tie-break weighs edges and ports by a fixed scramble of their index (`Solver.tieWeight`); evenly
  spaced weights tied whenever two index sums matched.
- `SolverPathTest` solves the corpus with every model's variables added in reverse (a different simplex path) and
  again later in the same game, and requires identical answers.

Answers that moved, all for the better or proven: 230_platline 2 gates to 1, full platline 45 to 44, mv 31 to 30;
platline (and x4, x10) proven least at 50. gtnh-flow's palladium_line second pass is a different problem since the
floor fix (its pinned machine imports everything and every other machine runs at the floor): 10 gates, not proven; an
unbounded search proves 8 least in 85 s, so a better heuristic is open work.

**Phase 3** (2026-10-12): the alternatives are per leftover. Every leftover the answer shows that not every answer
has is a decision; each closed gate is tried in its place, through `Enumerator.evaluateSupport`'s canonical point;
each decision lists its best eight. Needed gates are never tried (they cannot move), the swaps are counted, never
timed (`altBudgetMillis` is gone), and a swap keeps open the gates the answer opens that carry too little to show
(`SolveContext.openGates`). On the corpus: platline, mv, full platline and every plan whose open gates are all needed
offer no choice because there is none (each leftover has one possible place), and say so, complete; palladium_line's
second pass offers choices on four leftovers. Platline solves in 18 ms with everything (0.25 s after phase 1), mv in
38 ms, platline x4 in 0.53 s.

**Phase 4** (2026-10-12): islands (`Islands`): machines joined by a wire, a capped shared machine or a drawer are one
island, and `Balancer.solveWithAlternatives` solves each by itself, side by side, then reads them together as one
plan (one `SolutionView`, whose wiring notes still look across islands; each island's choices keyed by the whole
plan's answer). An island with nothing to size is idle; one that cannot balance says why and leaves the rest solved.
`Balancer.Cache` keeps solved islands by their snapshot (and the solver effort), so an edit re-solves only its island
and undo finds the old answer; `SolveService` keeps one. `SolverIslandTest`: every copy of a tiled plan answers
exactly as the plan alone, and the cache never answers for an island that changed. Platline x10 solves in 0.58 s
(67 s before phase 1), x4 in 27 ms.

**Phase 5** (2026-10-12): no clock decides anything. `Budget` is gone: the work is counted (gate-search questions,
swaps, probes, ties) and each model has only a 30 s safety net (`Numerics.modelSafetyMillis`) that no working model
reaches. A newer edit stops the running solve before its next model (`SolveContext.checkStop`, `Balancer.Stopped`,
`SolveService`); islands it finished stay cached. The snapshot numbers ingredients through an index (an item's id and
damage, its ore ids, a fluid) instead of testing every port against every ingredient, with the same numbers. Not done:
publishing the answer before its alternatives, which the times above no longer call for.

**In the game** (2026-10-12, dev game with GregTech, the stress plan of tiled platline): 490 cards solve in 1.2 s cold
(34 s before), an edit re-solves its island in 0.14 s; 1,960 cards in 1.8 s (never finished before); 19,600 cards in
4.4 s cold, an edit in 0.36 s and its undo under 0.25 s. What it took at that scale: options keep their island's key
(a plan-wide key per option was 87% of a 186 s solve), the wiring notes index who makes each ingredient, and the
island cache keeps the last two solves' islands however many there are.

Open work: a better minimal answer where the needed gates do not balance (palladium_line's second pass: 10 found, 8
least); warm re-solves (`IntermediateSolver.update`) now that answers no longer depend on the solve path; a connected
plan of a few hundred machines in the corpus, to measure what islands cannot help.

**Phase 6** (2026-10-12): ojAlgo 57.4.0 (its sparse dual simplex). Answers identical on every golden, the path test
and the island test, which is what phase 2 bought; one island of 490 machines (`SolverCorpus.joined`, ten platlines
under one shared machine, in `SolverBench`) went from 13.4 s to 1.4 s, nanocircuits from 63 to 33 ms, platline from
25 to 15 ms. Warm re-solves (`IntermediateSolver.update`) do not pay here: ojAlgo rebuilds after every infeasible
answer, and most of the gate search's questions are a needed gate refusing to close.

**Phase 7** (2026-10-13): what AUTO answers, decided with the owner. Every rule tried first failed on real plans:
fewest gates found exactly gives absurd sizes (gtnh-flow's palladium_line: 10 gates, 168,000/s traded, 2,717
machines, where the old heuristic found 9 gates, 12.6/s and 421); least material and every "upstream" ranking read
from the end turned off branches and bought their parts; forcing every machine on through few gates blew up
(8,305 machines). The rule now (section 1) is the owner's, from the bread chain (farm, mill, oven): short of wheat,
buy wheat and run the mill, never buy flour; spare wheat is milled and the flour sold where the oven takes no more.

- How far along a trade is, is counted in machine steps (`ModelData.portSteps`): at an input, the fewest machines it
  takes the plan to make the good; at an output, the most it could still go through. A loop counts as one step
  (`Loops`, Tarjan's components), so a plan that is one big loop, as GregTech lines tied by recycled hydrogen,
  chlorine and ammonia are, trades by least amount inside it.
- `TradeStage` settles one level at a time (`Solver.levels`): buying, the most steps first, then selling, the most
  steps ahead first, each held exactly where it was reached; a level that reached only rounding is closed. The old
  gate search (`GateSearch`, `GateCountStage`), least quantity (`ExternalMinStage`) and the every-machine-runs
  floors and replay are gone, and with them the last hard-coded small numbers that decided answers.
- A machine nothing pinned feeds or needs stays at zero rather than at a floor nobody chose, and is reported
  (`SolutionView.unneeded`) so the board asks for a count. palladium_line pins only palladium, so its platinum,
  rhodium, iridium and osmium branches ask for one.
- What the solver leaves at its dust (a hundred-billionth of the plan's largest rate) is committed as exactly zero
  (`SolveContext.commit`), so an idle machine reads idle and not "x<0.001". The board asks for a count on every
  machine in `unneeded` as on a part nothing sets (`BoardSession.unsetParts`).
- The choices panel explains an option by the same rules (`Preference`: buys more or further down the line, sells
  more or earlier in the line, moves more), and `Enumerator.evaluateSupport` settles a support exactly as AUTO does.
- The owner considered a setting for the direction (buy downstream, sell upstream) and dropped it: the opposite
  choices make plans that look broken, and a trade can still be moved by hand.

Tests: `TradeRuleTest` (the bread chain: short, spare, farm-only, a cake maker on the flour line taking the spare, a
cake maker nothing needs); `GroundTruthTest` rewritten to the rule (excess_choice now mills all the nitrogen and sells
charcoal; symmetric_choice runs both benders and sells plate); goldens re-recorded. Times: platline 21 ms, x10 97 ms,
the 490-machine joined island 1.0 s, nanocircuits 25 ms, palladium_line 0.23 s.
