# Adam World — Weapon Gameplay Implementation

## Adopted acquisition model
- Adam starts with a basic pistol and reserve ammunition.
- Additional weapons are purchased from the weapon shop UI.
- Ammunition is purchased for the currently equipped weapon.
- Starting cash is $2,500; weapon statistics and prices are data-driven in `WeaponSystem.java`.
- World pickups and mission rewards remain planned until connected to real world and mission events.

## Implemented
- Pistol, SMG, assault rifle, shotgun and sniper archetypes.
- Data-driven damage, range, fire cadence, magazine capacity, reload time, prices and ammo packs.
- Ownership, equipped weapon, per-weapon magazine/reserve ammunition and cash.
- Local persistence for inventory, cash, selected weapon and ammunition using Android SharedPreferences.
- Mobile aim, hold-to-fire, reload, weapon cycling and shop controls.
- Gamepad L1/LT aim, R2/RT fire, X reload; existing R1 camera modifier preserved.
- Basic forward-facing hit selection against pedestrian agents; damage and knockdown are applied in the simulation.
- HUD displays weapon, magazine/reserve ammunition and cash.
- Procedural low-poly held weapon silhouette (functional placeholder, not final authored weapon art).

## Remaining work
- Physical in-world shop locations, world pickups and mission reward hooks.
- Camera-ray hit detection, projectile/tracer visuals, muzzle flash, material-aware impact effects and weapon audio.
- Dedicated draw/holster/aim/fire/reload animation sets and radial Weapon Wheel.
- Wanted levels, police response and vehicle-specific weapon handling.
- Higher-detail original weapon models and final combat polish.

Do not mark the full system complete until remaining requirements are implemented and verified in-game.


## Rocket and guided missile subsystem
- Added two heavy-weapon entries: an unguided rocket launcher and a target-seeking guided launcher.
- Added a dedicated bounded projectile simulation (ProjectileSystem) rather than resolving launcher fire as instant hitscan.
- Rockets have independent position, velocity, flight time, limited steering for the guided variant, collision checks against world obstruction / terrain and nearby agents, and radial blast damage with distance falloff and knockback.
- Guided rockets acquire a nearby forward target at launch and steer toward it while the target remains active.
- Added short-lived rocket body, exhaust, smoke-trail and blast rendering in the existing OpenGL renderer.
- Added synthesized rocket-launch and detonation sound events through the existing lightweight audio thread; no large audio assets are bundled.
- Projectile and blast counts are capped (12 rockets / 8 blast effects); effects expire and are removed to prevent unbounded memory growth.
- Launcher ammunition, reload timing, purchase data and radial wheel entries use the shared weapon inventory arrays and existing SharedPreferences persistence.
- This is an original Adam World implementation; it does not use Rockstar assets or claim to reproduce proprietary GTA V internals.
- **Status:** gameplay and renderer code added; Android release build and in-device flight / targeting / collision behavior still require verification.
