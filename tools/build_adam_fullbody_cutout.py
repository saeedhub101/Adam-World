#!/usr/bin/env python3
"""Create a transparent full-body Adam cutout for the playable in-game avatar."""
import argparse
from pathlib import Path

import mediapipe as mp
import numpy as np
from PIL import Image, ImageFilter
from scipy.ndimage import gaussian_filter


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    source = Image.open(args.input).convert("RGBA")
    rgb = np.asarray(source.convert("RGB"))
    # Preserve an already-transparent source exactly; otherwise segment the
    # person locally with MediaPipe Selfie Segmentation (no paid API/service).
    existing_alpha = np.asarray(source.getchannel("A"))
    if existing_alpha.min() < 250:
        rgba = np.asarray(source).copy()
        alpha = existing_alpha.astype(np.float32) / 255.0
    else:
        with mp.solutions.selfie_segmentation.SelfieSegmentation(model_selection=1) as segmenter:
            result = segmenter.process(rgb)
        if result.segmentation_mask is None:
            raise RuntimeError("MediaPipe could not segment the full-body reference.")
        mask = np.asarray(result.segmentation_mask, dtype=np.float32)
        # A soft edge avoids a harsh halo around hair, sleeves and shoes.
        alpha = np.clip((mask - 0.12) / 0.48, 0.0, 1.0)
        alpha = gaussian_filter(alpha, sigma=1.25)
        rgba = np.dstack((rgb, np.clip(alpha * 255, 0, 255).astype(np.uint8)))

    ys, xs = np.where(alpha > 0.08)
    if len(xs) < 1000 or len(ys) < 1000:
        raise RuntimeError("The full-body person silhouette is too small or could not be isolated.")
    pad_x = max(8, int((xs.max() - xs.min() + 1) * 0.045))
    pad_y = max(8, int((ys.max() - ys.min() + 1) * 0.025))
    left, right = max(0, xs.min()-pad_x), min(source.width, xs.max()+pad_x+1)
    top, bottom = max(0, ys.min()-pad_y), min(source.height, ys.max()+pad_y+1)
    cutout = Image.fromarray(rgba.astype(np.uint8), "RGBA").crop((left, top, right, bottom))
    # Keep the complete silhouette and preserve its aspect ratio.
    cutout.thumbnail((1024, 1536), Image.Resampling.LANCZOS)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    cutout.save(output, optimize=True)
    print(f"Saved full-body transparent cutout: {cutout.width}x{cutout.height}")


if __name__ == "__main__":
    main()
