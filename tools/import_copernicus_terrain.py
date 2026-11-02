#!/usr/bin/env python3
"""Fetch free Copernicus DEM GLO-30 COG windows for enabled Adam World areas.

Amman and Tabarbour are terrain-enabled destinations.

No API key or paid endpoint is used. Each city's 201x201 grid is saved and
committed independently so a rerun skips valid checkpoints and resumes later
cities after a transient source failure.
"""
from __future__ import annotations

import json
import math
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np
import rasterio
from rasterio.windows import Window

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "data/facades/cities/catalog.json"
OUTPUT = ROOT / "data/facades/cities"
GRID_SIZE = 201
RADIUS_M = 3000.0
BUCKET = "https://copernicus-dem-30m.s3.eu-central-1.amazonaws.com"


def tile_component(value: int, positive: str, negative: str, width: int) -> str:
    return (positive if value >= 0 else negative) + f"{abs(value):0{width}d}_00"


def tile_url(lat_degree: int, lon_degree: int) -> str:
    name = (
        "Copernicus_DSM_COG_10_"
        + tile_component(lat_degree, "N", "S", 2)
        + "_"
        + tile_component(lon_degree, "E", "W", 3)
        + "_DEM"
    )
    return f"{BUCKET}/{name}/{name}.tif"


def git(*args: str, check: bool = True):
    return subprocess.run(
        ["git", *args], cwd=ROOT, check=check, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )


def valid_grid(path: Path, center: dict) -> bool:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return (
            data.get("schema_version") == 1
            and data.get("source") == "Copernicus DEM GLO-30"
            and data.get("center") == center
            and data.get("rows") == GRID_SIZE
            and data.get("cols") == GRID_SIZE
            and len(data.get("elevations_m", [])) == GRID_SIZE
            and all(len(row) == GRID_SIZE for row in data["elevations_m"])
            and all(value is not None and math.isfinite(float(value))
                    for row in data["elevations_m"] for value in row)
        )
    except Exception:
        return False


def sample_city(city: dict) -> dict:
    center = city["center"]
    lat0, lon0 = float(center["lat"]), float(center["lon"])
    lat_radius = RADIUS_M / 111132.0
    lon_radius = RADIUS_M / (111320.0 * math.cos(math.radians(lat0)))
    bounds = {
        "west": lon0 - lon_radius, "east": lon0 + lon_radius,
        "south": lat0 - lat_radius, "north": lat0 + lat_radius,
    }
    lats = np.linspace(bounds["north"], bounds["south"], GRID_SIZE)
    lons = np.linspace(bounds["west"], bounds["east"], GRID_SIZE)
    values = np.full((GRID_SIZE, GRID_SIZE), np.nan, dtype=np.float32)

    groups = defaultdict(list)
    for row, lat in enumerate(lats):
        for col, lon in enumerate(lons):
            groups[(math.floor(float(lat)), math.floor(float(lon)))].append(
                (row, col, float(lat), float(lon))
            )

    urls = []
    with rasterio.Env(
        AWS_NO_SIGN_REQUEST="YES",
        GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR",
        CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif",
        VSI_CACHE="TRUE",
        VSI_CACHE_SIZE="67108864",
    ):
        for (lat_degree, lon_degree), points in sorted(groups.items()):
            url = tile_url(lat_degree, lon_degree)
            last_error = None
            dataset = None
            for attempt in range(1, 4):
                try:
                    dataset = rasterio.open("/vsicurl/" + url)
                    break
                except Exception as exc:
                    last_error = exc
                    time.sleep(attempt * 2)
            if dataset is None:
                raise RuntimeError(f"{city['slug']}: could not open DEM tile {url}: {last_error}")
            with dataset:
                if dataset.crs is None or dataset.crs.to_epsg() != 4326:
                    raise RuntimeError(f"Unexpected DEM CRS for {url}: {dataset.crs}")
                indexed = []
                for row, col, lat, lon in points:
                    rr, cc = dataset.index(lon, lat)
                    if 0 <= rr < dataset.height and 0 <= cc < dataset.width:
                        indexed.append((row, col, rr, cc))
                if not indexed:
                    continue
                min_row = min(item[2] for item in indexed)
                max_row = max(item[2] for item in indexed)
                min_col = min(item[3] for item in indexed)
                max_col = max(item[3] for item in indexed)
                window = Window(min_col, min_row, max_col - min_col + 1, max_row - min_row + 1)
                band = dataset.read(1, window=window, masked=True)
                for row, col, rr, cc in indexed:
                    value = band[rr - min_row, cc - min_col]
                    if not np.ma.is_masked(value) and np.isfinite(float(value)) and float(value) > -1000:
                        values[row, col] = float(value)
            urls.append(url)

    valid = np.isfinite(values)
    coverage = float(valid.mean())
    if coverage < 0.98:
        raise RuntimeError(
            f"{city['slug']}: DEM grid coverage only {coverage:.1%}; refusing to commit incomplete terrain"
        )
    raw_coverage = coverage
    # Fill the few residual voids with the nearest valid 30 m neighbour.
    # This avoids holes in the rendered terrain mesh while retaining the
    # original source coverage metric for quality reporting.
    for row, col in np.argwhere(~valid):
        filled = False
        for radius in range(1, 9):
            r0, r1 = max(0, row-radius), min(GRID_SIZE, row+radius+1)
            c0, c1 = max(0, col-radius), min(GRID_SIZE, col+radius+1)
            patch = values[r0:r1, c0:c1]
            good = patch[np.isfinite(patch)]
            if good.size:
                values[row, col] = float(np.median(good))
                valid[row, col] = True
                filled = True
                break
        if not filled:
            raise RuntimeError(f"{city['slug']}: could not fill DEM nodata at {row},{col}")
    finite = values[np.isfinite(values)]
    base = float(np.median(finite))
    rows = [[round(float(v), 2) for v in row] for row in values]
    return {
        "schema_version": 1,
        "source": "Copernicus DEM GLO-30",
        "source_urls": sorted(set(urls)),
        "source_license": "Copernicus DEM free and open access; attribution required",
        "source_resolution_m": 30,
        "center": center,
        "bounds": bounds,
        "rows": GRID_SIZE,
        "cols": GRID_SIZE,
        "base_elevation_m": round(base, 2),
        "min_elevation_m": round(float(finite.min()), 2),
        "max_elevation_m": round(float(finite.max()), 2),
        "coverage_fraction": round(raw_coverage, 5),
        "elevations_m": rows,
    }


def commit_checkpoint(slug: str):
    rel = f"data/facades/cities/{slug}/elevation-grid.json"
    git("add", rel)
    staged = git("diff", "--cached", "--quiet", check=False)
    if staged.returncode == 0:
        return
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("commit", "-m", f"Add Copernicus terrain grid: {slug}")
    for attempt in range(1, 6):
        git("fetch", "origin", "main")
        rebased = git("rebase", "origin/main", check=False)
        if rebased.returncode:
            git("rebase", "--abort", check=False)
            raise RuntimeError("Could not rebase terrain checkpoint: " + rebased.stdout)
        pushed = git("push", "origin", "HEAD:main", check=False)
        if pushed.returncode == 0:
            print(f"{slug}: TERRAIN_CHECKPOINT_SAVED")
            return
        print(f"{slug}: main changed while pushing; retry {attempt}/5")
        time.sleep(attempt * 3)
    raise RuntimeError(f"{slug}: could not push terrain checkpoint after five attempts")


def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    succeeded, failed = [], []
    for city in catalog.get("cities", []):
        if city["slug"] not in {"amman", "tabarbour"}:
            print(f"{city['slug']}: terrain disabled by project policy; skipping")
            continue
        slug = city["slug"]
        target = OUTPUT / slug / "elevation-grid.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        if valid_grid(target, city["center"]):
            print(f"{slug}: valid terrain grid already exists; skip")
            succeeded.append(slug)
            continue
        try:
            print(f"{slug}: sampling Copernicus DEM GLO-30 into {GRID_SIZE}x{GRID_SIZE} grid")
            data = sample_city(city)
            temp = target.with_suffix(".json.tmp")
            temp.write_text(json.dumps(data, separators=(",", ":")) + "\n", encoding="utf-8")
            temp.replace(target)
            commit_checkpoint(slug)
            print(f"{slug}: SAVED coverage={data['coverage_fraction']:.1%} min={data['min_elevation_m']}m max={data['max_elevation_m']}m")
            succeeded.append(slug)
        except Exception as exc:
            print(f"{slug}: FAILED; continuing to next city: {exc}")
            failed.append((slug, str(exc)))
    print(f"TERRAIN_IMPORT_RESULT success={len(succeeded)} failed={len(failed)}")
    if failed:
        for slug, reason in failed:
            print(f"FAILED_CITY {slug}: {reason}")
        raise SystemExit("Some terrain grids remain incomplete; rerun to resume successful city checkpoints")
    if not succeeded:
        raise SystemExit("No city terrain grids were produced")


if __name__ == "__main__":
    main()
