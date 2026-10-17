/**
 * A signed-in account's cloud plans on this device ({@link com.gtnhplanner.cloud.session.CloudSession}): the planner's
 * plans while signed in, each a {@link com.gtnhplanner.cloud.session.LiveDoc} kept in step with the account and with
 * everyone editing it, built on the engine ({@code cloud/sync}), the mapping ({@code cloud/map}), the network
 * ({@code cloud/net}) and this device's copy ({@code cloud/store}). docs/design/cloud-live.md is the contract. No
 * Minecraft here: the game comes in through {@link com.gtnhplanner.cloud.session.CloudSession.Host}.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.gtnhplanner.cloud.session;
