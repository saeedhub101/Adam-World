#!/usr/bin/env python3
"""CPU-only photo-derived facial surface builder for Adam World."""
import argparse
import json
import urllib.request
from pathlib import Path

import mediapipe as mp
import numpy as np
import trimesh
from scipy.spatial import Delaunay
from PIL import Image

TASK_URL = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--references", nargs="+", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    model = Path("/tmp/adam_face_landmarker.task")
    if not model.exists():
        urllib.request.urlretrieve(TASK_URL, model)

    options = mp.tasks.vision.FaceLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(model)),
        running_mode=mp.tasks.vision.RunningMode.IMAGE,
        num_faces=8,
    )
    best = None
    with mp.tasks.vision.FaceLandmarker.create_from_options(options) as detector:
        for filename in args.references:
            path = Path(filename)
            if not path.is_file():
                continue
            image = Image.open(path).convert("RGB")
            pixels = np.asarray(image)
            result = detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=pixels))
            for face in result.face_landmarks:
                xs = [p.x for p in face]
                ys = [p.y for p in face]
                bounds = (min(xs), min(ys), max(xs), max(ys))
                area = (bounds[2]-bounds[0])*(bounds[3]-bounds[1])*image.width*image.height
                print(f"{path}: face area {area:.0f} pixels")
                if best is None or area > best[0]:
                    best = (area, path, image, face, bounds)

    if best is None:
        raise RuntimeError("No face detected in the Adam reference images.")
    _, source, image, landmarks, bounds = best
    x0, y0, x1, y1 = bounds
    pad_x, pad_y = (x1-x0)*0.16, (y1-y0)*0.18
    left = max(0, int((x0-pad_x)*image.width))
    top = max(0, int((y0-pad_y)*image.height))
    right = min(image.width, int((x1+pad_x)*image.width))
    bottom = min(image.height, int((y1+pad_y)*image.height))
    # Square crop preserves facial proportions and keeps UV coordinates undistorted.
    side = max(right-left, bottom-top)
    cx, cy = (left+right)//2, (top+bottom)//2
    square_left, square_top = cx-side//2, cy-side//2
    square = Image.new("RGB", (side, side), (110, 100, 95))
    source_left, source_top = max(0, square_left), max(0, square_top)
    source_right, source_bottom = min(image.width, square_left+side), min(image.height, square_top+side)
    patch = image.crop((source_left, source_top, source_right, source_bottom))
    square.paste(patch, (source_left-square_left, source_top-square_top))
    crop = square.resize((1024, 1024), Image.Resampling.LANCZOS)
    crop.save(output / "adam_face_texture.png", optimize=True)

    vertices, uvs = [], []
    for point in landmarks:
        vertices.append([(point.x-0.5)*0.46, (0.5-point.y)*0.54, -point.z*0.34])
        u = (point.x*image.width-square_left)/max(1, side)
        v = 1.0-(point.y*image.height-square_top)/max(1, side)
        uvs.append([float(np.clip(u, 0, 1)), float(np.clip(v, 0, 1))])

    # FaceLandmarker exposes ordered landmarks, not triangle faces.
    # Triangulate their image-plane coordinates into a valid facial surface.
    triangulation = Delaunay(np.asarray([[p.x, p.y] for p in landmarks], dtype=np.float64))
    faces = triangulation.simplices.astype(int).tolist()
    if not faces:
        raise RuntimeError("Face triangulation produced no usable triangles.")
    mesh = trimesh.Trimesh(vertices=np.asarray(vertices), faces=np.asarray(faces), process=False)
    material = trimesh.visual.material.PBRMaterial(
        baseColorTexture=crop, metallicFactor=0.0, roughnessFactor=0.85, doubleSided=True
    )
    mesh.visual = trimesh.visual.TextureVisuals(uv=np.asarray(uvs), material=material)
    (output / "adam_face_surface.glb").write_bytes(mesh.export(file_type="glb"))

    data = {
        "schema": 1,
        "source_image": str(source),
        "texture": "adam_face_texture.png",
        "vertices": np.asarray(vertices, dtype=float).round(7).tolist(),
        "uvs": np.asarray(uvs, dtype=float).round(7).tolist(),
        "triangles": faces,
        "note": "Photo-textured front facial surface; unseen side and rear geometry are not inferred as exact likeness."
    }
    (output / "adam_face_mesh.json").write_text(json.dumps(data, separators=(",", ":")))
    print(f"Saved {len(vertices)} vertices and {len(faces)} triangles.")


if __name__ == "__main__":
    main()
