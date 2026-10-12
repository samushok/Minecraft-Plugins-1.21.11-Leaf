# Signal67 — live server test checklist

Target: **Leaf / Paper 1.21.11, Java 21**

Run these checks on a test server before merging or using on production.

## Basic trigger
- Join as a normal non-OP player with `signal67.use`.
- Type exactly `67`.
- The chat message should be hidden when `hide-trigger-message: true`.
- Exactly one ritual should start.
- Seven TextDisplay sectors should appear and follow the player.
- The ritual should finish after roughly the configured duration.
- Exactly one configured reward should be executed.
- All temporary displays should disappear after the ending animation.

## Cooldown
- Immediately type `67` again.
- No second ritual should start.
- The remaining cooldown should be shown.
- Run `/signal67 status` and verify the remaining time is consistent.
- Restart the server and verify the cooldown still remains.
- Run `/signal67 reset <player>` as admin and verify the player can use `67` again.

## Rapid / duplicate trigger
- Send `67` twice as quickly as possible.
- Only one active ritual should exist for that player.
- No duplicate reward should be granted.

## Lifecycle cleanup
Start a ritual and separately test:
- disconnect;
- die;
- change world.

Expected for all three:
- the ritual stops safely;
- temporary TextDisplays are removed;
- no repeating task keeps producing particles;
- no exception appears in console.

## Concurrent users
With `max-concurrent-rituals: 4`:
- Start four rituals on four players.
- A fifth player should receive the configured busy message.
- TPS should remain stable and each completed ritual should clean up its own displays.

## Rewards and chances
- Run `/signal67 chances`.
- Confirm percentages match current reward weights.
- Change several weights in `config.yml`, then run `/signal67 reload`.
- Run `/signal67 chances` again and verify percentages update.
- Add a deliberately nonexistent test command to a temporary reward.
- The failed command should be logged, while the ritual still completes and cleans up.

## Config safety
Temporarily test extreme values and reload:
- `ritual.update-period-ticks: 0`
- `ritual.radius: 100`
- `max-concurrent-rituals: 999`

Signal67 should clamp them to safe runtime limits and continue without crashing.

## Chat-controller compatibility
If the server has a separate chat controller:
- test `67` in the normal chat channel;
- verify Signal67 still detects the exact message;
- verify ordinary chat messages are unaffected;
- verify messages such as `67 hello`, `067`, and `6767` do not trigger.

## Admin commands
Verify:
- `/signal67 status`
- `/signal67 chances`
- `/signal67 reload`
- `/signal67 reset <player>`
- `/signal67 test [player]`

`/signal67 test` must bypass cooldown for testing but should not overwrite the player's normal cooldown timestamp.

## Production gate
Before production:
- GitHub Actions build is green.
- Leaf smoke boot is green.
- This checklist passes on a real test server.
- Replace or remove any placeholder reward commands.
- Keep the STORM reward disabled until Spheres exposes a supported player-targeted give API.
