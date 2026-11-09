# Adam World — Weapon Gameplay Implementation

## Adopted acquisition model
- Adam starts with a basic pistol and reserve ammunition.
- Additional weapons are bought at the in-game weapon shop.
- Ammunition can be bought for the currently equipped weapon.
- Starting cash is $2,500; prices and weapon balance are data-driven.
- World pickups and mission rewards remain planned until connected to actual world/mission events.

## Implemented source behavior
- Weapon catalogue: pistol, SMG, assault rifle, shotgun and sniper archetypes.
- Per-weapon damage, range, fire cadence, magazine size, reload duration, price and ammo packs.
- Ownership, equipped weapon, cash, magazine and reserve-ammo state.
- Touchscreen: aim, hold-to-fire, reload, cycle weapon and shop.
- Gamepad: L1/LT aim, R2/RT hold-to-fire, X reload; existing R1 camera modifier remains unchanged.
- Basic forward-facing target selection against active pedestrian agents; damage and knockdown are applied in the existing game simulation.
- HUD displays equipped weapon, magazine/reserve ammo and cash.
- A procedural low-poly weapon silhouette is attached to Adam's right hand; it is a placeholder visual, not final authored weapon art.

## Still required for full GTA-like completion
- Save/load persistence for cash, owned weapons and ammunition.
- World pickups and mission reward hooks; physical weapon-shop locations.
- Camera-ray hit detection, muzzle flash, bullet traces, impact/material effects and weapon audio.
- Dedicated draw/holster/aim/fire/reload animation sets and radial weapon wheel.
- Wanted levels, police response and vehicle-specific weapon handling.

Do not mark the whole weapon system complete until the remaining requirements are implemented and verified in-game.
