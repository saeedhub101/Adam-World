#!/usr/bin/env python3
"""Build-time GLB -> Adam World Mesh converter.

Downloads only the existing repository's individually catalogued CC0-1.0 vehicle
sources, converts their transformed geometry, UVs, base-color textures, and material data to AWM4, and writes
only the custom binary format into Android assets. Source GLBs stay in a temporary
directory and are never packaged or required by the Android runtime.
"""
from __future__ import annotations

import io
import os
import struct
import tempfile
import urllib.request
from pathlib import Path

import numpy as np
import trimesh

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/models/runtime/vehicles"
ASSETS = {
    "sedan": {
        "url": "https://files.innerscene.com/library/sedan-car-42109739/75c64c1b3c8b03734314/sedan-original.glb",
        "length": 4.45,
        "fallback": (0.16, 0.30, 0.48),
    },
    "pickup": {
        "url": "https://files.innerscene.com/library/pickup-truck-da45aa3f/aef5bc0897dcaebfa6a2/pickup-original.glb",
        "length": 5.20,
        "fallback": (0.20, 0.27, 0.34),
    },
    "city-bus": {
        "url": "https://files.innerscene.com/library/low-floor-city-bus-9e7ce899/9c3a0074b0e553ba181a/city-bus-original.glb",
        "length": 8.30,
        "fallback": (0.82, 0.70, 0.16),
    },
    "motorcycle": {
        "url": "https://files.innerscene.com/library/race-replica-superbike-fully-faired-badbd83b/9ce443dc7d0c14f443e9/race-replica-superbike-fully-faired.glb",
        "length": 2.10,
        "fallback": (0.72, 0.08, 0.06),
    },
}


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={
        "User-Agent": "AdamWorld-AWM-Builder/1.0",
        "Accept": "model/gltf-binary,*/*",
    })
    with urllib.request.urlopen(request, timeout=180) as response:
        raw = response.read()
    if len(raw) < 1024 or raw[:4] != b"glTF":
        raise ValueError("source did not return a GLB v2 file")
    if int.from_bytes(raw[4:8], "little") != 2 or int.from_bytes(raw[8:12], "little") != len(raw):
        raise ValueError("invalid GLB v2 header or declared length")
    return raw


def scene_meshes(raw: bytes):
    loaded = trimesh.load(io.BytesIO(raw), file_type="glb", force="scene", process=False)
    if isinstance(loaded, trimesh.Trimesh):
        return [loaded]
    result = []
    for node in loaded.graph.nodes_geometry:
        transform, geometry_name = loaded.graph[node]
        geometry = loaded.geometry[geometry_name].copy()
        geometry.apply_transform(transform)
        if len(geometry.vertices) and len(geometry.faces):
            result.append(geometry)
    if not result:
        raise ValueError("GLB contains no renderable triangle geometry")
    return result


def color_for(mesh, fallback):
    count = len(mesh.vertices)
    base = np.repeat(np.asarray(fallback, dtype=np.float32)[None, :], count, axis=0)
    try:
        visual = mesh.visual
        kind = getattr(visual, "kind", None)
        if kind == "vertex":
            colors = np.asarray(visual.vertex_colors, dtype=np.float32)[:, :3] / 255.0
            if len(colors) == count:
                return colors
        if kind == "face":
            face_colors = np.asarray(visual.face_colors, dtype=np.float32)[:, :3] / 255.0
            sums = np.zeros((count, 3), dtype=np.float32)
            weights = np.zeros((count, 1), dtype=np.float32)
            for face_index, face in enumerate(mesh.faces):
                sums[face] += face_colors[face_index]
                weights[face] += 1.0
            mask = weights[:, 0] > 0
            base[mask] = sums[mask] / weights[mask]
            return base
        material = getattr(visual, "material", None)
        factor = getattr(material, "baseColorFactor", None)
        if factor is not None and len(factor) >= 3:
            c = np.asarray(factor[:3], dtype=np.float32)
            if float(c.max()) > 1.0:
                c /= 255.0
            return np.repeat(np.clip(c, 0.0, 1.0)[None, :], count, axis=0)
    except Exception:
        pass
    return base


def vertex_normals(vertices: np.ndarray, faces: np.ndarray) -> np.ndarray:
    """Compute stable area-weighted vertex normals without optional SciPy."""
    normals = np.zeros_like(vertices, dtype=np.float32)
    triangles = vertices[faces]
    face_normals = np.cross(
        triangles[:, 1] - triangles[:, 0],
        triangles[:, 2] - triangles[:, 0],
    ).astype(np.float32, copy=False)
    np.add.at(normals, faces[:, 0], face_normals)
    np.add.at(normals, faces[:, 1], face_normals)
    np.add.at(normals, faces[:, 2], face_normals)
    lengths = np.linalg.norm(normals, axis=1, keepdims=True)
    invalid = (~np.isfinite(lengths[:, 0])) | (lengths[:, 0] < 1e-8)
    normals = normals / np.maximum(lengths, 1e-8)
    normals[invalid] = (0.0, 1.0, 0.0)
    return np.nan_to_num(normals, nan=0.0, posinf=0.0, neginf=0.0)


def convert(raw: bytes, target_length: float, fallback):
    meshes = scene_meshes(raw)
    all_vertices, all_normals, all_uvs, all_colors = [], [], [], []
    face_groups, material_records = [], []
    texture_payloads, texture_ids = [], {}
    uv_meshes = 0
    uv_vertices = 0
    offset = 0
    for mesh in meshes:
        vertices = np.asarray(mesh.vertices, dtype=np.float32)
        faces = np.asarray(mesh.faces, dtype=np.int64)
        normals = vertex_normals(vertices, faces)
        colors = color_for(mesh, fallback)
        uv = getattr(mesh.visual, "uv", None)
        if uv is None or len(uv) != len(vertices):
            uv = np.zeros((len(vertices), 2), dtype=np.float32)
        else:
            uv = np.asarray(uv, dtype=np.float32)
            valid_uv = np.isfinite(uv).all(axis=1)
            uv_vertices += int(valid_uv.sum())
            if valid_uv.any():
                uv_meshes += 1
            uv = np.nan_to_num(uv, nan=0.0, posinf=0.0, neginf=0.0)
        all_vertices.append(vertices)
        all_normals.append(normals)
        all_uvs.append(uv)
        all_colors.append(colors)
        face_groups.append(faces.copy())
        material = getattr(mesh.visual, "material", None)
        def mf(name, default):
            try: return float(getattr(material, name, default)) if material is not None else float(default)
            except (TypeError, ValueError): return float(default)
        emissive = getattr(material, "emissiveFactor", (0, 0, 0)) if material is not None else (0, 0, 0)
        try:
            emissive = np.asarray(emissive, dtype=np.float32).reshape(-1)[:3]
            if len(emissive) != 3: emissive = np.zeros(3, dtype=np.float32)
            if float(emissive.max()) > 1.0: emissive /= 255.0
        except Exception: emissive = np.zeros(3, dtype=np.float32)
        texture_index = -1
        source_image = getattr(material, "baseColorTexture", None) if material is not None else None
        if source_image is None and material is not None:
            source_image = getattr(material, "image", None)
        if source_image is not None:
            try:
                if hasattr(source_image, "convert") and hasattr(source_image, "save"):
                    rgba = source_image.convert("RGBA")
                    encoded = io.BytesIO()
                    rgba.save(encoded, format="PNG", optimize=True)
                    payload = encoded.getvalue()
                    digest = __import__("hashlib").sha256(payload).hexdigest()
                    if digest not in texture_ids:
                        texture_ids[digest] = len(texture_payloads)
                        texture_payloads.append((int(rgba.width), int(rgba.height), payload))
                    texture_index = texture_ids[digest]
            except Exception as texture_error:
                print(f"AWM_TEXTURE_SKIPPED: {texture_error}", flush=True)
        material_records.append((1.,1.,1.,1.,float(emissive[0]),float(emissive[1]),float(emissive[2]),
            float(np.clip(mf("metallicFactor",0.0),0,1)),float(np.clip(mf("roughnessFactor",.72),.04,1)),.5,
            0,int(bool(getattr(material,"doubleSided",False))) if material is not None else 0,texture_index))
        offset += len(vertices)

    vertices = np.concatenate(all_vertices, axis=0)
    normals = np.concatenate(all_normals, axis=0)
    # Apply a single world-space transform to all scene parts before flattening.
    bounds = np.vstack((vertices.min(axis=0), vertices.max(axis=0)))
    extents = bounds[1] - bounds[0]
    if extents[1] <= 1e-5:
        raise ValueError("model has no measurable vertical extent")
    # glTF is Y-up. Align the longer horizontal dimension to the game's +Z axis.
    if extents[0] > extents[2]:
        rotation = np.array([[0, 0, 1], [0, 1, 0], [-1, 0, 0]], dtype=np.float32)
        vertices = vertices @ rotation.T
        normals = normals @ rotation.T
    bounds = np.vstack((vertices.min(axis=0), vertices.max(axis=0)))
    extents = bounds[1] - bounds[0]
    horizontal = max(float(extents[0]), float(extents[2]))
    scale = target_length / max(horizontal, 1e-5)
    vertices *= scale
    vertices[:, 0] -= (float(vertices[:, 0].min()) + float(vertices[:, 0].max())) * .5
    vertices[:, 2] -= (float(vertices[:, 2].min()) + float(vertices[:, 2].max())) * .5
    vertices[:, 1] -= float(vertices[:, 1].min())
    normals /= np.maximum(np.linalg.norm(normals, axis=1, keepdims=True), 1e-8)

    # Expand indexed triangles, preserving normals and each source material's color.
    uv_by_vertex = np.concatenate(all_uvs, axis=0)
    colors_by_vertex = np.concatenate(all_colors, axis=0)
    expanded = np.empty((sum(len(g) for g in face_groups) * 3, 11), dtype=np.float32)
    submeshes = []
    cursor = 0
    vertex_offset = 0
    for material_index, group in enumerate(face_groups):
        first = cursor
        for face in group:
            for local_index in face:
                vertex_index = vertex_offset + int(local_index)
                color = colors_by_vertex[vertex_index]
                if np.asarray(color).ndim == 2: color = color[0]
                expanded[cursor, 0:3] = vertices[vertex_index]
                expanded[cursor, 3:6] = normals[vertex_index]
                expanded[cursor, 6:8] = uv_by_vertex[vertex_index]
                expanded[cursor, 8:11] = np.clip(color[:3], 0.0, 1.0)
                cursor += 1
        submeshes.append((first, cursor-first, material_index))
        vertex_offset += len(all_vertices[material_index])
    total_vertices = sum(len(v) for v in all_vertices)
    print(f"AWM_UV meshes_with_uv={uv_meshes}/{len(all_vertices)} vertices_with_uv={uv_vertices}/{total_vertices}", flush=True)
    return expanded, submeshes, material_records, texture_payloads


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    built = 0
    for name, item in ASSETS.items():
        destination = OUT / (name + ".awmesh")
        try:
            raw = fetch(item["url"])
            vertices, submeshes, materials, textures = convert(raw, item["length"], item["fallback"])
            with destination.open("wb") as stream:
                stream.write(struct.pack("<4sIIIII", b"AWM4", len(vertices), len(submeshes), len(materials), 11, len(textures)))
                stream.write(vertices.astype("<f4", copy=False).tobytes())
                for first, count, material_index in submeshes:
                    stream.write(struct.pack("<III", first, count, material_index))
                for material in materials:
                    stream.write(struct.pack("<10fIII", *material[:10], int(material[10]), int(material[11]), (int(material[12]) & 0xFFFFFFFF)))
                for width, height, payload in textures:
                    stream.write(struct.pack("<IIII", width, height, 1, len(payload)))
                    stream.write(payload)
            print(f"AWM_BUILT {name} format=AWM4 textures={len(textures)} vertices={len(vertices)} submeshes={len(submeshes)} materials={len(materials)} bytes={destination.stat().st_size}", flush=True)
            built += 1
        except Exception as exc:
            # Keep the release build resilient; the renderer uses its detailed procedural
            # fallback for this model if a source is unavailable.
            if destination.exists():
                destination.unlink()
            print(f"AWM_SKIPPED {name}: {exc}", flush=True)
    print(f"AWM_RESULT built={built} total={len(ASSETS)}", flush=True)


if __name__ == "__main__":
    main()
