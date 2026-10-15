/**
 * Golden cases for the mod's bee model (machines/Bees.java): a bee card on the website's own functions over a spread
 * of settings, in the three housings the mod models (the plain Apiary, as the mod posts it with a handler of its own;
 * the Magic Apiary, the website's default; the Industrial Apiary). Run with the website's vitest through
 * vitest.bees.config.mjs beside it (its header says how); writes the mod's src/test/resources/bee-goldens.jsonl.gz,
 * which BeesGoldensTest reads.
 *
 * FORMAT: gzip, UTF-8, one JSON object per line:
 *   { housing: "apiary" | "magic-apiary" | "industrial-apiary", tiers: { controlId: key },
 *     product, specialty,      // getMachineOutputMultiplier for a product and for a specialty marked
 *                              // "Needs preferred climate" (the website's jubilance rule)
 *     duration,                // getMachineDurationMultiplier
 *     durationTicks, eut }     // getOverclockedRecipeStats on the handler's recipe
 */
import fs from "node:fs";
import zlib from "node:zlib";
import { applyMachineHandlerToRecipe } from "@/lib/model/recipe-rules";
import { enrichPassiveProductionRecipe } from "@/lib/model/passive-production";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { getMachineDurationMultiplier, getMachineOutputMultiplier } from "@/lib/solver/machine-effects";
import type { Recipe } from "@/lib/model/types";

const OUT = process.env.BEE_GOLDENS_OUT ?? "bee-goldens.jsonl.gz";

function beeRecipe(): Recipe {
  return {
    id: "oracle:local-2.9.0-beta-2:forestry-bee:gregtech-bee-speciespulsatingiron",
    name: "Pulsating Iron Produce",
    kind: "bee_produce",
    category: "forestry-bee",
    machineType: "Bee Produce",
    minimumTier: "NONE",
    durationTicks: 550,
    eut: 0,
    inputs: [
      {
        kind: "item",
        id: "factoryflow:bee_species:gregtech-bee-speciespulsatingiron",
        amount: 1,
        displayName: "Pulsating Iron Bee",
        consumed: false,
      },
    ],
    outputs: [
      { kind: "item", id: "forestry:beecombs@7", amount: 1, displayName: "Parched Comb", chance: 0.3 },
      {
        kind: "item",
        id: "gregtech:gt.comb@18",
        amount: 1,
        displayName: "Pulsating Iron Comb",
        chance: 0.15,
        byproduct: true,
        tooltip: ["Specialty chance: 15%", "Needs preferred climate"],
      },
    ],
    source: { recipeMap: "Bee Produce" },
  } as Recipe;
}

const SPEEDS = ["slowest", "slower", "slow", "normal", "fast", "faster", "fastest", "blinding"];
const CLIMATES = ["wrong", "tolerated", "preferred", "controlled"];
const FRAMES = [
  "none",
  "forestry:untreated",
  "forestry:impregnated",
  "forestry:proven",
  "extrabees:cocoa",
  "extrabees:cage",
  "extrabees:soul",
  "extrabees:clay",
  "magicbees:magic",
  "magicbees:resilient",
  "magicbees:gentle",
  "magicbees:metabolic",
  "magicbees:necrotic",
  "magicbees:temporal",
  "magicbees:oblivion",
];
// Frames whose production never meets a cap in three slots (Forestry's +1 below 10, MagicBees' constants).
const STACKING = ["none", "forestry:untreated", "forestry:proven", "magicbees:magic", "magicbees:gentle", "magicbees:necrotic"];
const IA_SPEEDS = ["none", "speed-1", "speed-2", "speed-3", "speed-4", "speed-5", "speed-6", "speed-7", "speed-8", "speed-8-upgraded"];
const IA_PRODUCTION = ["0", "1", "2", "3", "4", "5", "6", "7", "8"];

function frameSets(): string[][] {
  const out: string[][] = [];
  const seen = new Set<string>();
  const add = (set: string[]) => {
    const key = set.join("|");
    if (!seen.has(key)) {
      seen.add(key);
      out.push(set);
    }
  };
  for (const f of FRAMES) add([f, "none", "none"]);
  for (const f of FRAMES) add(["none", f, "none"]);
  add(["forestry:proven", "extrabees:cocoa", "none"]);
  for (const a of STACKING) for (const b of STACKING) for (const c of STACKING) add([a, b, c]);
  return out;
}

it("writes the bee goldens", () => {
  // The plain Apiary is not one of the site's bee machines (its first, and default, is the Magic Apiary): the mod
  // posts its Apiary cards with this handler first in the recipe, which the site merges ahead of its own.
  const recipe = enrichPassiveProductionRecipe({
    ...beeRecipe(),
    machineHandlers: [
      { id: "apiary", label: "Apiary", machineType: "Apiary", minimumTier: "NONE", eut: 0, kind: "single" },
    ],
  } as Recipe);
  const lines: string[] = [];
  const write = (housing: string, tiers: Record<string, string>) => {
    const node = {
      id: "n",
      recipeId: recipe.id,
      machineCount: 1,
      parallel: 1,
      overclockTier: housing === "industrial-apiary" ? "MV" : "NONE",
      machineConfigTiers: tiers,
      machineHandlerId: housing,
    } as never;
    const effective = applyMachineHandlerToRecipe(recipe, node);
    const stats = getOverclockedRecipeStats(effective, node);
    lines.push(
      JSON.stringify({
        housing,
        tiers,
        product: getMachineOutputMultiplier(effective, node, effective.outputs[0]!, "MV"),
        specialty: getMachineOutputMultiplier(effective, node, effective.outputs[1]!, "MV"),
        duration: getMachineDurationMultiplier(effective, node),
        durationTicks: stats.durationTicks,
        eut: stats.eut,
      }),
    );
  };
  for (const speed of SPEEDS)
    for (const climate of CLIMATES)
      for (const frames of frameSets())
        write("apiary", {
          beeSpeedGene: speed,
          beeEnvironment: climate,
          beeFrameSlot1: frames[0]!,
          beeFrameSlot2: frames[1]!,
          beeFrameSlot3: frames[2]!,
        });
  for (const speed of SPEEDS)
    for (const climate of CLIMATES)
      for (const aura of ["none", "production-aura"])
        for (const frames of frameSets())
          write("magic-apiary", {
            beeSpeedGene: speed,
            beeEnvironment: climate,
            beeMagicAura: aura,
            beeFrameSlot1: frames[0]!,
            beeFrameSlot2: frames[1]!,
            beeFrameSlot3: frames[2]!,
          });
  for (const speed of SPEEDS)
    for (const climate of CLIMATES)
      for (const ia of IA_SPEEDS)
        for (const production of IA_PRODUCTION)
          write("industrial-apiary", {
            beeSpeedGene: speed,
            beeEnvironment: climate,
            beeIndustrialSpeed: ia,
            beeIndustrialProduction: production,
          });
  fs.writeFileSync(OUT, zlib.gzipSync(Buffer.from(lines.join("\n") + "\n", "utf8")));
  console.log(`wrote ${lines.length} bee cases to ${OUT}`);
}, 120_000);
