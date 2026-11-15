# Imported 3D weapon models and credits

Five detailed firearm meshes are sourced from the public project
https://github.com/cktang88/3d-fps, pinned to commit
https://github.com/cktang88/3d-fps/tree/130ac2a5c856401ec36e7d35afcfefe40c5801f4.
The upstream README documents the creator credits and licenses below.

| Adam World file | Upstream model | Creator attribution | License |
|---|---|---|---|
| pistol.glb | m1911.glb | M1911 pistol by egorbelous | CC BY 4.0 |
| submachine-gun.glb | mp5a5.glb | MP5 Submachine Gun by Rotuma | CC BY 4.0 |
| assault-rifle.glb | m4a1.glb | M4A1 by Firewarden | CC BY 4.0 |
| shotgun.glb | shotgun.glb | Shotgun by Harry_L; Remington 870 by tris09 | CC BY (upstream credit) |
| sniper-rifle.glb | awm.glb | AWM by erhanmatur | CC BY 4.0 |
| rocket-launcher.glb | Kenney rocketlauncherModern.glb | Kenney | CC0 1.0 |

Source credits: https://github.com/cktang88/3d-fps#credits--licences
CC BY 4.0: https://creativecommons.org/licenses/by/4.0/
CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/

The build pipeline preserves geometry, normals, UVs, material groups and base-colour textures, then decimates
to a mobile budget. Original source GLBs are stored in this directory; AWM4 runtime meshes are generated in
app/src/main/assets/models/runtime/weapons/.