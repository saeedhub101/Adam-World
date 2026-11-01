#!/usr/bin/env python3
"""Create an offline Amman elevation grid from the public Copernicus DEM 30 m COG."""
from __future__ import annotations
import json, math, subprocess
from pathlib import Path
import rasterio
from rasterio.windows import from_bounds

ROOT=Path(__file__).resolve().parents[2]
URL="https://copernicus-dem-30m.s3.eu-central-1.amazonaws.com/Copernicus_DSM_COG_10_N31_00_E035_00_DEM/Copernicus_DSM_COG_10_N31_00_E035_00_DEM.tif"
LAT,LON=31.95165805,35.93939340
HALF=3000.0
ROWS=COLS=201
KY=111132.0
KX=111320.0*math.cos(math.radians(LAT))
west=LON-HALF/KX; east=LON+HALF/KX
south=LAT-HALF/KY; north=LAT+HALF/KY
with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR",CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif"):
    with rasterio.open(URL) as ds:
        win=from_bounds(west,south,east,north,ds.transform).round_offsets().round_lengths()
        data=ds.read(1,window=win,out_shape=(ROWS,COLS),resampling=rasterio.enums.Resampling.bilinear,masked=True)
        tr=ds.window_transform(win)
        scale_x=win.width/COLS;scale_y=win.height/ROWS
        transform=ds.transform * rasterio.Affine.scale(scale_x,scale_y)
        values=[]
        for row in range(ROWS):
            vals=[]
            for col in range(COLS):
                v=data[row,col]
                if hasattr(v,"mask") and bool(v.mask): vals.append(None)
                else:
                    n=float(v)
                    vals.append(round(n,2) if math.isfinite(n) and -500<n<9000 else None)
            values.append(vals)
        center_row=min(ROWS-1,max(0,round((north-LAT)/(north-south)*(ROWS-1))))
        center_col=min(COLS-1,max(0,round((LON-west)/(east-west)*(COLS-1))))
        base=values[center_row][center_col]
        valid=[v for row in values for v in row if v is not None]
        if not valid or base is None: raise SystemExit("Copernicus DEM window has no valid centre elevation")
        out={
          "schema_version":1,"source":"Copernicus DEM GLO-30","source_url":URL,
          "source_license":"Copernicus DEM free and open access; attribution required",
          "source_resolution_m":30,"capture_date":"2021-04-22",
          "center":{"lat":LAT,"lon":LON},"bounds":{"west":west,"south":south,"east":east,"north":north},
          "rows":ROWS,"cols":COLS,"base_elevation_m":base,
          "min_elevation_m":round(min(valid),2),"max_elevation_m":round(max(valid),2),
          "elevations_m":values
        }
path=ROOT/"data/facades/cities/amman/elevation-grid.json"
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps(out,separators=(",",":"))+"\n",encoding="utf-8")
print(f"AMMAN_DEM_SAVED rows={ROWS} cols={COLS} min={out['min_elevation_m']} max={out['max_elevation_m']} centre={base}")
