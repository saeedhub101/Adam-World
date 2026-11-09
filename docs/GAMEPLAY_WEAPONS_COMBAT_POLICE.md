# Adam World — Weapons, Combat, Police & Wanted System

> **Document type:** Gameplay requirements and implementation roadmap  
> **Repository:** `saeedhub101/Adam-World`  
> **Target branch:** `main`  
> **Status:** Specification only — this document records the requested target experience. It does **not** claim that these features are implemented or complete.  
> **Design target:** A cohesive third-person open-world gameplay experience inspired by the broad gameplay conventions of GTA V, with original Adam World implementation, assets, UI and identity.

---

## 1. Product goal

Build a complete, connected weapons, combat, police-response and wanted-level system. It must feel like one coherent game system rather than a collection of unrelated buttons.

The system must:
- Work with the existing Android Java / OpenGL ES game architecture.
- Support both touchscreen mobile play and TV / USB / Bluetooth gamepads.
- Reuse the same gameplay actions across input devices.
- Integrate with the player character, vehicles, aircraft, health, animation, audio and the streamed city.
- Load optional content on demand and avoid unnecessarily increasing APK size or startup memory.
- Remain responsive while city data is streaming and while the player changes cities.
- Be documented and developed on `main`; do not create a development branch unless the user explicitly changes this rule.

## 2. Weapon catalogue

Provide weapon categories with distinct handling, animation, audio, recoil, reload behavior and gameplay balance.

### 2.1 Handguns
- Standard pistol.
- Heavy pistol.
- Semi-automatic pistol.
- Suppressed pistol.

### 2.2 Submachine guns
- Compact and full-size SMG variants.
- Fast handling and close-range role.

### 2.3 Assault rifles
- AK-style and M4-style original game models.
- Tactical rifle variants.
- Distinct fire modes where supported by the game design.

### 2.4 Shotguns
- Pump-action shotgun.
- Tactical shotgun.
- Close-range role and distinct reload / firing cadence.

### 2.5 Precision rifles
- Scoped sniper rifle.
- Long-range precision rifle.
- Scope view with adjustable zoom and stable aiming camera.

### 2.6 Heavy fictional-game weapons
- Rocket launcher.
- Other heavy weapons only where performance, balance and game rating permit.

Weapon names and appearances are references for broad archetypes. Adam World should use original or properly licensed assets and must not copy GTA assets.

## 3. Weapon inventory and selection

Implement a unified inventory system:
- Store owned weapons and per-weapon ammunition.
- Track currently equipped weapon and weapon slot.
- Support weapon pickup, purchase and inventory updates.
- Support quick switching and a radial Weapon Wheel.
- Show weapon icon/name and remaining ammunition in the HUD.
- Support empty-ammunition state and reload.
- Prevent incompatible actions while dead, incapacitated, or in a restricted animation state.
- Define which weapons may be used in vehicles and aircraft.

### 3.1 Character weapon presentation
- Holster suitable handguns at the waist.
- Place suitable long guns on the character's back when not equipped.
- Show the selected weapon in the correct hand / grip.
- Play draw and holster animations.
- Hide or reposition weapons appropriately when entering vehicles.
- Restore the correct weapon presentation when exiting.
- Avoid weapons floating, intersecting the body, or remaining visible in invalid states.

## 4. Combat and aiming

### 4.1 Core actions
- Draw and holster.
- Aim / stop aiming.
- Fire.
- Reload.
- Switch weapon.
- Move while aiming.
- Change stance where supported.
- Melee attack, punch, kick and guard when not using a firearm.
- Optional cover behavior as a later integrated combat capability.

### 4.2 Weapon feel
Each weapon must have its own data-driven parameters:
- Fire cadence.
- Magazine capacity and reserve ammunition.
- Reload duration and reload animation.
- Recoil strength and recovery.
- Aim sensitivity and spread.
- Effective gameplay range.
- Projectile / hit behavior.
- Audio and visual effects.
- Damage and balance values.

Do not hard-code a separate copy of the entire firing system for every weapon. Use shared systems with per-weapon configuration.

### 4.3 Hit and damage feedback
- Apply damage through a central damage / health system.
- Show appropriate hit feedback without obstructing play.
- Support impact effects on appropriate world materials, vehicles and breakable props.
- Support glass response where the world object is configured as breakable.
- Avoid expensive particle and decal accumulation; effects must be pooled, bounded and cleaned up.
- Keep the existing gradual health regeneration design; do not add a manual health-restoration button.

## 5. Police, wanted levels and pursuit

Create a wanted system with five escalating levels. Wanted status must be driven by in-game events and must be communicated clearly in the HUD.

| Level | Intended response |
|---|---|
| 1 star | Nearby patrols investigate and search for the player |
| 2 stars | Police vehicles actively pursue |
| 3 stars | Stronger pursuit and additional units |
| 4 stars | Specialized police response and police helicopter support |
| 5 stars | Broad, high-intensity pursuit with coordinated units |

Required behavior:
- Police vehicles can detect, pursue and attempt to intercept the player.
- Officers can leave vehicles and move on foot.
- Police AI must navigate streets and respond to obstacles rather than simply teleporting.
- Police helicopter can search for and track the player when the response level allows it.
- Search behavior should use last-known player position and line-of-sight / visibility rules.
- Hiding, breaking line of sight and leaving the search area should allow wanted level to cool down over time.
- Wanted level must not disappear instantly while police still have a valid view or pursuit.
- Police response must scale with performance and available streamed world data.
- Provide clear feedback for gaining, maintaining and losing wanted status.

## 6. Mobile touchscreen controls

Provide a configurable, readable touch layout that does not cover important world content.

Required actions:
- Movement joystick.
- Camera look by touch drag and pinch zoom.
- Aim button.
- Fire button (support hold-to-fire where the weapon permits).
- Reload.
- Weapon switch.
- Weapon Wheel access.
- Jump.
- Punch / kick / guard when unarmed or in melee mode.
- Crouch / stance where supported.
- Enter / exit vehicle.
- Contextual vehicle and aircraft controls.

Touch controls must:
- Be context-sensitive: show relevant actions for on-foot, vehicle and aircraft states.
- Avoid presenting incompatible actions simultaneously.
- Support press, hold, release and cancel correctly.
- Reset held inputs safely if a touch is interrupted or the app loses focus.
- Allow layout / size / opacity adjustment where practical.
- Keep camera gestures smooth and avoid conflicts between aiming, firing and camera movement.

## 7. TV, USB and Bluetooth gamepad controls

Use a consistent controller mapping and show an in-game help legend. Support common Android gamepad key layouts where possible.

Suggested default mapping (must be configurable and validated against actual controller devices):

| Input | Action |
|---|---|
| Left stick | Move character / steer vehicle |
| Right stick | Camera / aim |
| LT | Aim |
| RT | Fire / accelerate contextually |
| X | Reload or contextual interaction |
| RB | Quick weapon switch |
| Hold weapon-wheel control | Open weapon wheel |
| Face button | Jump / contextual action |
| Melee button | Punch / kick |
| Guard button | Hold guard |
| Vehicle interaction button | Enter / exit vehicle |
| Aircraft vertical controls | Climb / descend when flying |

The final mapping must avoid conflicts with the existing enter/exit vehicle behavior and current controller actions. Use contextual prompts and allow remapping if feasible.

## 8. Vehicle and aircraft integration

- Entering a vehicle must transition the character and weapon state cleanly.
- Exiting must restore the character's previous valid weapon state.
- Define which weapons are usable from each vehicle category.
- Prevent the character from aiming or firing through vehicle geometry incorrectly.
- Define passenger / driver restrictions and vehicle-specific firing poses.
- Aircraft controls must remain separate from on-foot combat controls.
- Police pursuit must be able to follow by road and, at higher wanted levels, by helicopter.
- Weapon and combat effects must not destabilize vehicle or aircraft frame rate.

## 9. HUD and user experience

Provide a consistent HUD for:
- Current weapon and icon.
- Magazine ammunition / reserve ammunition.
- Reload state.
- Aim state.
- Wanted stars.
- Player health (with gradual regeneration).
- Context-sensitive control hints.

Weapon Wheel requirements:
- Radial layout with clear category / weapon icons.
- Pause or slow gameplay while selecting, if technically appropriate.
- Highlight the selected weapon.
- Display ammunition.
- Close cleanly on release, cancel or selection.
- Work with touch and gamepad without requiring separate weapon logic.

## 10. Animation requirements

Animations should be state-driven and blended rather than abruptly switched:
- Idle with weapon.
- Walk / run with weapon.
- Aim and aim locomotion.
- Fire and recoil.
- Reload.
- Draw and holster.
- Melee punch, kick and guard.
- Jump and landing transitions.
- Enter / exit vehicle with weapon state transitions.
- Vehicle passenger / driver weapon poses where allowed.

If a required animation asset is unavailable, use a deliberate fallback that preserves correct hand placement and state; do not leave the weapon floating or the character frozen.

## 11. Audio and visual effects

- Distinct firing, reload, draw, holster and empty-magazine sounds.
- Appropriate distant / near sound variation where practical.
- Muzzle flash and brief smoke.
- Ejected casings for compatible weapons.
- Impact effects based on material type.
- Controlled camera recoil for powerful weapons.
- Optional subtle controller vibration on supported gamepads.
- Pool short-lived effects and audio resources to prevent memory growth.

Use original or properly licensed assets. Avoid copying proprietary GTA V models, sounds, animations, UI or other protected assets.

## 12. Technical architecture

Prefer shared, data-driven systems. Suggested logical responsibilities (names are conceptual; integrate with the existing project structure rather than creating duplicate managers):

| System | Responsibility |
|---|---|
| WeaponManager | Equipped weapon and weapon state |
| WeaponInventory | Owned weapons and ammunition |
| WeaponWheel | Radial selection UI |
| CombatController | Aim, fire, reload and melee requests |
| AnimationController | Character animation state and transitions |
| ProjectileSystem | Projectile / hitscan resolution and collision |
| DamageSystem | Health and damage application |
| PoliceAI | Officer behavior, search and pursuit |
| WantedSystem | Wanted level and escalation / cooldown |
| Audio / Effects | Weapon audio, impact effects and pooling |
| InputRouter | Normalize touch and gamepad actions |
| VehicleCombat | Contextual combat rules for vehicles |

### 12.1 Input architecture
All input devices must send the same semantic actions to the game layer. Do not implement separate combat behavior for mobile and gamepad. The UI / controller layer should only generate actions; the gameplay layer owns validation and state transitions.

### 12.2 Performance and packaging
- Load weapon models, textures, sounds and effects on demand.
- Avoid bundling large unused asset collections in the APK.
- Reuse shared materials and meshes where appropriate.
- Pool transient effects and projectiles.
- Bound AI update rates and active police counts based on distance and performance.
- Release resources when leaving a city or changing gameplay context.
- Keep startup memory bounded and do not preload every optional asset.
- Provide safe fallback behavior if optional assets fail to load.

## 13. Implementation roadmap

The roadmap is an execution order, not permission to leave core requirements unfinished or to declare the system complete after only the first step.

1. **Foundation:** shared InputRouter, weapon definitions, inventory state and player combat states.
2. **Weapon handling:** weapon selection, draw / holster, aim, fire, reload, ammunition and HUD.
3. **Character combat:** animations, melee, hit reactions and damage integration.
4. **Mobile UX:** touch layout, contextual buttons, Weapon Wheel and gesture conflict handling.
5. **Gamepad UX:** consistent mapping, prompts, hold / release handling and controller compatibility.
6. **World interaction:** impact effects, material response, breakable glass where supported, audio and pooling.
7. **Wanted system:** five levels, police detection, search, pursuit, foot officers and cooldown.
8. **Vehicle integration:** enter / exit state transitions, allowed weapons, vehicle combat and police road pursuit.
9. **Aircraft integration:** aircraft-specific controls and police helicopter search / pursuit.
10. **Polish and optimization:** animation blending, sound mix, performance limits, asset streaming, accessibility and UI refinement.

## 14. Completion criteria

The feature is complete only when all relevant criteria below are implemented in the actual game, not merely documented:

- Every listed weapon category has a working, distinct weapon implementation or an explicitly approved scope decision.
- Inventory, ammunition, selection, reload and Weapon Wheel work end-to-end.
- Draw / holster, aiming, firing, recoil and reload animations transition correctly.
- Melee actions work and do not conflict with weapon actions.
- Mobile and gamepad produce equivalent gameplay actions.
- Held inputs are released safely on touch cancellation, controller disconnect and app pause.
- Vehicle entry / exit preserves valid character and weapon states.
- Police detection, escalation, pursuit, search and cooldown work as a connected system.
- Five wanted levels have visibly different response behavior.
- Police vehicles, officers and helicopter behavior integrate with the world and available navigation data.
- HUD communicates weapon, ammunition, health and wanted state clearly.
- Audio / visual effects are distinct, pooled and bounded.
- Optional assets load on demand; APK size and memory remain controlled.
- No feature is marked complete solely because a class, enum, button or placeholder model exists.
- Final release is built through the established release workflow on `main`, with stable signing and no unnecessary duplicate workflows.

## 15. Decisions to confirm before implementation

These are design choices, not assumed decisions:
- Weapon availability: purchase from in-world weapon shops, pickups in the world, mission rewards, or a combination.
- Whether ammunition is purchased, collected, or both.
- Whether weapons can be stored in a safehouse / personal vehicle.
- Whether police can use all weapon categories or only selected equipment.
- Whether vehicle-mounted weapons are in scope.
- Final controller button mapping for the user's actual TV gamepad.
- Final weapon roster and visual style.

Until confirmed, implement the architecture so these choices are data-driven and can be changed without rewriting the combat system.

---

## 16. Project rules

- Work on `main` only; do not create branches.
- Inspect and extend the existing Android Java implementation instead of replacing it with an HTML prototype.
- Do not claim implementation or testing based on documentation alone.
- Avoid test-only workflows that do not contribute to a release or directly requested validation.
- Keep this document updated as implementation lands; distinguish **planned**, **implemented**, and **verified in-game** status for every major subsystem.
