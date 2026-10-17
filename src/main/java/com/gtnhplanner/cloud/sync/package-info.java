/**
 * The live edit engine for shared plans, the game's mirror of the website's {@code src/lib/live/ops.ts}
 * ({@link com.gtnhplanner.cloud.sync.LiveOps}: what an edit to a plan is, how it applies, how two plans differ, how an
 * edit is undone) and {@code client-state.ts} ({@link com.gtnhplanner.cloud.sync.LiveState}: one client's confirmed and
 * pending copies). docs/design/cloud-live.md, "Ops in detail", is the contract. Plain Java over Gson's JSON trees, no
 * game and no network; both engines run the same test vectors ({@code live-vectors.json}).
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.gtnhplanner.cloud.sync;
