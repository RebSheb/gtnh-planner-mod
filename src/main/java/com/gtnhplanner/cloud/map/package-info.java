/**
 * The game's board as a shared plan and back ({@link com.gtnhplanner.cloud.map.CloudPlans}): the website's plan JSON,
 * as the library's posting export writes it, with the game's own data on every thing, read the importer's way where
 * the website made or changed something. docs/design/cloud-live.md, "Game mapping", is the contract. No network and
 * no UI: the game's side comes in through {@link com.gtnhplanner.cloud.map.CloudGame}.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.gtnhplanner.cloud.map;
