# Battle Royale Mobile HUD Rebuild

**Branch:** `main`  
**Implementation commit:** `d7a232cdd8a2f4cd69cc6e987fd96179916439ad`  
**Status:** Code committed; Android release workflow and in-device verification are pending.

## Rebuild decision

The previous mobile action panel, scrollable action rows, separate vehicle/flight rows, duplicated action-pad containers, and the old label-driven HUD positioning have been replaced in `MainActivity` by one contextual Battle Royale HUD layer.

The rebuild uses original Adam World controls and artwork; it follows Fortnite Battle Royale's mobile interaction conventions and spatial hierarchy, without copying proprietary Fortnite artwork or assets.

## New mobile layout

- One movement joystick at lower-left.
- One unobstructed camera-look surface across the play area.
- One right-side contextual action cluster for fire, aim, jump, crouch and reload.
- Five compact weapon quick slots along the lower center.
- Pickup, healing and shield actions are compact secondary controls.
- Build controls appear only while build mode is active.
- Vehicle controls appear contextually while driving; aircraft throttle, pitch, brake and landing-gear controls appear only while piloting.
- Health/shield and weapon/ammunition information are compact status panels.
- Non-gameplay status text and map attribution are hidden during gameplay.

## Input behavior

Touch actions continue to call the existing `Game` request/state fields and `CityRenderer` vehicle actions. This avoids introducing a second combat, movement, or flight simulation. Held actions are released through the existing touch press/release handling and Activity pause cleanup.

## Validation required before declaring completion

- Android APK and AAB build must succeed in the existing `android-release.yml` workflow.
- Install and launch on a phone; verify the HUD at portrait and landscape/small-screen sizes.
- Verify no overlapping controls and confirm joystick + camera + simultaneous action multi-touch.
- Verify fire/aim/reload/jump/crouch, all five weapon slots, pickup/heal/shield, build-mode controls, vehicle entry/exit, car throttle/brake, and aircraft controls.
- Verify the TV/gamepad path is unchanged.
- Compare actual in-game screenshots with the intended Fortnite mobile HUD hierarchy and tune safe-area coordinates if needed.

A successful source commit is not proof of a successful build or verified in-game parity. Do not mark the HUD complete until the workflow and device checks pass.
