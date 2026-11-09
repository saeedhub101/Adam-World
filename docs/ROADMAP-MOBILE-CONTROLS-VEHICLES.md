# Mobile Touch Controls, Combat, and Aircraft — Development Roadmap

**Target branch:** `main` only. Do not create feature branches.
**Scope:** Android touch play must expose the same core gameplay actions available through USB/Bluetooth gamepads.

## Current baseline (repository inspection)
- `MainActivity` currently creates a movement joystick but no on-screen action buttons.
- Gamepad mappings already exist for jump (A), kick (B), punch (X), enter/exit or summon vehicle (Y), guard (L1/L2), sprint (left stick click), dodge, map, and pause.
- `Game` already contains jump, punch/kick/combo, guard, dodge, vehicle state, aircraft altitude and lift input.
- `CityRenderer` already contains vehicle-type cycling and procedural vehicle model/render paths. `ProceduralVehicleModels` includes helicopter and airplane types.
- Vehicle interaction currently uses `toggleCar()`: when away from the active vehicle it attempts to summon it; when near it enters; while driving it exits. The same selected vehicle type is used for this interaction. Aircraft physics/altitude handling exists, but the complete player-facing aircraft experience and mobile controls are not yet established.

## Implementation progress (2026-11-09)
- **Committed to `main`:** first Android touch HUD implementation in `MainActivity`.
- **Implemented:** mobile-only action pad (kept off non-touch TV devices); jump, punch, kick, hold-to-guard, hold-to-sprint, dodge, vehicle summon/enter/exit, and vehicle-type cycling.
- **Implemented:** contextual HUD hides on-foot combat/run/dodge controls while driving and exposes climb/descent controls only while piloting a helicopter or airplane.
- **Implemented:** held sprint/guard/lift states are released on touch-up/cancel and when the activity pauses.
- **Implemented:** gamepad D-pad up/down controls aircraft climb/descent (R1 camera-zoom modifier retains priority); aircraft body pitch and bank now respond to flight input and steering, with attitude reset on vehicle entry/exit.
- **Reused:** existing `Game` request/state fields and `CityRenderer.toggleCar()/cycleVehicleType()`; no parallel gameplay system was introduced.
- **Still in progress:** flight handling needs refinement (especially airplane pitch/takeoff/landing and dedicated throttle controls), vehicle spawn/selection needs a clearer garage flow, and end-to-end build/release confirmation is pending. Existing type cycling already includes helicopter and airplane, but no dedicated airport/hangar placement has been established by this change.

## Delivery stages

### Stage 1 — Mobile action HUD
- Add accessible, thumb-reachable touch controls for jump, punch, kick, guard (hold), dodge, sprint, and context-sensitive vehicle interaction.
- Preserve multi-touch: movement joystick and camera drag must remain usable while an action button is pressed.
- Keep camera look/zoom gestures working and avoid intercepting the joystick or top navigation.
- Switch action presentation while driving: hide/disable on-foot combat/jump actions and expose vehicle controls.
- Use clear Arabic labels/icons, readable contrast, and scalable dp sizing across phone/tablet aspect ratios.

### Stage 2 — Combat and character animation
- Wire touch controls to the existing game state/action requests; do not create duplicate combat logic.
- Verify left/right punch sequencing, combo timing, kick, guard, dodge, hit reaction, and natural arm movement.
- Ensure buttons that represent held actions (guard/sprint) set state on press and reliably clear it on release/cancel/focus loss.

### Stage 3 — Vehicle interaction and selection
- Make summon / approach / enter / exit behavior understandable and reliable from touch.
- Add an accessible vehicle selector/garage for all supported types, including cars, buses, bikes, tank, helicopter, and airplane.
- Show the selected vehicle and contextual instructions; prevent changing type while driving.
- Ensure aircraft can be summoned at a safe, clear spawn point and are not presented as merely decorative models.

### Stage 4 — Helicopter and airplane flight controls
- Provide touch controls for throttle, steering, climb, and descent; use aircraft-specific layouts and hide irrelevant combat controls.
- Helicopter: vertical takeoff/landing, hover, yaw, forward/backward flight, altitude limits.
- Airplane: ground acceleration, takeoff only at suitable speed, pitch/climb/descent, steering, landing and safe deceleration.
- Keep the existing gamepad flight mapping coherent with touch mapping.

### Stage 5 — Integration and release
- Integrate the HUD without regressing map search, city selection, minimap, camera gestures, gamepad input, pause/settings, or health HUD.
- Build and publish the Android APK/AAB using the existing release workflow only after implementation is complete.
- Report actual build/release links and distinguish completed work from anything still pending. Do not claim tests or release succeeded unless the workflow confirms it.

## Acceptance criteria
1. A phone-only player can move, sprint, jump, punch, kick, guard, dodge, summon/enter/exit vehicles without a gamepad.
2. Camera drag and movement joystick work simultaneously with touch actions.
3. On-foot and in-vehicle HUDs show only relevant controls.
4. Helicopter and airplane are selectable, summonable, controllable, and landable; the controls are usable on both touch and gamepad.
5. Existing gamepad controls continue to work.
6. All repository work is committed directly to `main`; no new branch is created.

## Implementation notes
- Reuse `Game` request/state fields and `CityRenderer` actions wherever possible.
- Avoid duplicate movement/combat/flight systems.
- Prefer compact contextual HUD controls over permanently displaying every possible button.
- This roadmap is a staged engineering plan, not a claim that every stage has already shipped.
