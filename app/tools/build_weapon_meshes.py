#!/usr/bin/env python3
"""Convert the six repository-owned CC0 Kenney GLB weapons to compact AWM4 meshes."""
from __future__ import annotations
import struct
from pathlib import Path
import build_runtime_meshes as builder

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/assets/models/weapons"
DEST = ROOT / "app/src/main/assets/models/runtime/weapons"
WEAPONS = {
    "pistol": {"length": 0.30, "fallback": (0.18,0.19,0.20), "faces": 4500},
    "submachine-gun": {"length": 0.58, "fallback": (0.16,0.18,0.20), "faces": 6500},
    "assault-rifle": {"length": 0.72, "fallback": (0.17,0.19,0.21), "faces": 8000},
    "shotgun": {"length": 0.68, "fallback": (0.25,0.19,0.13), "faces": 7000},
    "sniper-rifle": {"length": 0.88, "fallback": (0.18,0.20,0.22), "faces": 9000},
    "rocket-launcher": {"length": 0.82, "fallback": (0.20,0.22,0.18), "faces": 8000},
}

def main():
    DEST.mkdir(parents=True, exist_ok=True)
    failures=[]
    for name, cfg in WEAPONS.items():
        src=SOURCE/(name+".glb")
        out=DEST/(name+".awmesh")
        try:
            raw=src.read_bytes()
            if len(raw)<1024 or raw[:4]!=b"glTF" or int.from_bytes(raw[4:8],"little")!=2 or int.from_bytes(raw[8:12],"little")!=len(raw):
                raise ValueError("invalid source GLB v2")
            vertices,parts,materials,textures=builder.convert(raw,cfg["length"],cfg["fallback"],cfg["faces"],512)
            with out.open("wb") as stream:
                stream.write(struct.pack("<4sIIIII",b"AWM4",len(vertices),len(parts),len(materials),11,len(textures)))
                stream.write(vertices.astype("<f4",copy=False).tobytes())
                for first,count,material_index in parts:
                    stream.write(struct.pack("<III",first,count,material_index))
                for material in materials:
                    stream.write(struct.pack("<10fIII",*material[:10],int(material[10]),int(material[11]),int(material[12]) & 0xFFFFFFFF))
                for width,height,payload in textures:
                    stream.write(struct.pack("<IIII",width,height,1,len(payload)))
                    stream.write(payload)
            size=out.stat().st_size
            if size>2_000_000:
                out.unlink()
                raise ValueError(f"converted model exceeds 2 MB budget: {size}")
            print(f"WEAPON_MESH_BUILT {name} vertices={len(vertices)} parts={len(parts)} materials={len(materials)} textures={len(textures)} bytes={size}",flush=True)
        except Exception as exc:
            failures.append((name,str(exc)))
            print(f"WEAPON_MESH_FAILED {name}: {exc}",flush=True)
    if failures:
        raise SystemExit("weapon mesh conversion failed: "+repr(failures))

if __name__=="__main__":
    main()
