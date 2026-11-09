#!/usr/bin/env python3
"""Build-time GLB -> Adam World Mesh converter.

Downloads only the existing repository's individually catalogued CC0-1.0 vehicle
sources, converts their transformed geometry/material colors to AWM1, and writes
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
    try:
        visual = mesh.visual
        if getattr(visual, "kind", None) == "vertex":
            colors = np.asarray(visual.vertex_colors, dtype=np.float32)[:, :3] / 255.0
            if len(colors) == count:
                return colors
        if getattr(visual, "kind", None) == "face":
            face = np.asarray(visual.face_colors, dtype=np.float32)[:, :3] / 255.0
            return np.repeat(face[:, None, :], 3, axis=1)
        material = getattr(visual, "material", None)
        factor = getattr(material, "baseColorFactor", None)
        if factor is not None and len(factor) >= 3:
            c = np.asarray(factor[:3], dtype=np.float32) / 255.0
            return np.repeat(c[None, :], count, axis=0)
    except Exception:
        pass
    return np.repeat(np.asarray(fallback, dtype=np.float32)[None, :], count, axis=0)


def convert(raw: bytes, target_length: float, fallback):
    meshes = scene_meshes(raw)
    all_vertices, all_normals, all_colors, all_faces = [], [], [], []
    offset = 0
    for mesh in meshes:
        vertices = np.asarray(mesh.vertices, dtype=np.float32)
        faces = np.asarray(mesh.faces, dtype=np.int64)
        normals = np.asarray(mesh.vertex_normals, dtype=np.float32)
        colors = color_for(mesh, fallback)
        if colors.shape[0] == len(faces) and colors.ndim == 3:
            colors = np.repeat(colors, 1, axis=0)
            # Face colors are expanded after indexing below.
        if normals.shape != vertices.shape:
            normals = np.tile(np.array([[0, 1, 0]], dtype=np.float32), (len(vertices), 1))
        all_vertices.append(vertices)
        all_normals.append(normals)
        all_colors.append(colors)
        all_faces.append(faces + offset)
        offset += len(vertices)

    vertices = np.concatenate(all_vertices, axis=0)
    normals = np.concatenate(all_normals, axis=0)
    faces = np.concatenate(all_faces, axis=0)
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
    colors_by_vertex = np.concatenate(all_colors, axis=0)
    expanded = np.empty((len(faces) * 3, 9), dtype=np.float32)
    cursor = 0
    for face in faces:
        for vertex_index in face:
            color = colors_by_vertex[vertex_index]
            if np.asarray(color).ndim == 2:
                color = color[0]
            expanded[cursor, 0:3] = vertices[vertex_index]
            expanded[cursor, 3:6] = normals[vertex_index]
            expanded[cursor, 6:9] = np.clip(color[:3], 0.0, 1.0)
            cursor += 1
    return expanded


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    built = 0
    for name, item in ASSETS.items():
        destination = OUT / (name + ".awmesh")
        try:
            raw = fetch(item["url"])
            vertices = convert(raw, item["length"], item["fallback"])
            with destination.open("wb") as stream:
                stream.write(b"AWM1")
                stream.write(struct.pack("<I", len(vertices)))
                stream.write(vertices.astype("<f4", copy=False).tobytes())
            print(f"AWM_BUILT {name} vertices={len(vertices)} bytes={destination.stat().st_size}", flush=True)
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
