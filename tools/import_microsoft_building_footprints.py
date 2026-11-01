#!/usr/bin/env python3
"""Supplement OSM buildings with Microsoft Global ML satellite-derived footprints."""
from __future__ import annotations
import csv, gzip, hashlib, io, json, math, os, subprocess, tempfile, time
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "data/facades/cities/catalog.json"
DATA = ROOT / "data/offline-world"
INDEX_URL = "https://bfppub.blob.core.windows.net/$web/2026-08-13/dataset-links.csv"
INDEX_VERSION = "2026-08-13"
ZOOM = 9
USER_AGENT = "AdamWorld-MicrosoftFootprints/1.0 (open data importer)"

def fetch_bytes(url, timeout=240):
    last = None
    for attempt in range(4):
        try:
            req = Request(url, headers={"User-Agent": USER_AGENT})
            with urlopen(req, timeout=timeout) as response:
                return response.read()
        except Exception as exc:
            last = exc
            time.sleep(2 + attempt * 3)
    raise RuntimeError(f"download failed: {url}: {last}")

def tile_xy(lat, lon, zoom=ZOOM):
    n = 1 << zoom
    lat = max(-85.05112878, min(85.05112878, lat))
    x = int((lon + 180.0) / 360.0 * n)
    rad = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(rad)) / math.pi) / 2.0 * n)
    return max(0, min(n-1, x)), max(0, min(n-1, y))

def quadkey(x, y, zoom=ZOOM):
    key = []
    for level in range(zoom, 0, -1):
        bit = 1 << (level - 1)
        digit = (1 if (x & bit) else 0) + (2 if (y & bit) else 0)
        key.append(str(digit))
    return "".join(key)

def city_quadkeys(city, radius):
    lat0, lon0 = float(city["center"]["lat"]), float(city["center"]["lon"])
    dlat = radius / 111132.0
    dlon = radius / (111320.0 * max(0.2, math.cos(math.radians(lat0))))
    keys = set()
    for iy in range(5):
        lat = lat0 - dlat + (2*dlat*iy/4)
        for ix in range(5):
            lon = lon0 - dlon + (2*dlon*ix/4)
            keys.add(quadkey(*tile_xy(lat, lon)))
    return keys

def read_index():
    raw = fetch_bytes(INDEX_URL)
    text = raw.decode("utf-8-sig")
    rows = list(csv.DictReader(io.StringIO(text)))
    if not rows or not {"QuadKey", "Url"}.issubset(rows[0]):
        raise RuntimeError("Microsoft dataset-links.csv schema is not recognized")
    result = {}
    for row in rows:
        key = str(row.get("QuadKey", "")).strip()
        url = str(row.get("Url", "")).strip()
        if key and url:
            old = result.get(key)
            if not old or str(row.get("UploadDate", "")) > str(old.get("UploadDate", "")):
                result[key] = row
    print(f"MICROSOFT_INDEX_OK version={INDEX_VERSION} quadkeys={len(result)}", flush=True)
    return result

def polygon_metrics(coords, lat0, lon0):
    pts = []
    kx = 111320.0 * math.cos(math.radians(lat0))
    for pair in coords:
        if not isinstance(pair, (list, tuple)) or len(pair) < 2:
            continue
        lon, lat = float(pair[0]), float(pair[1])
        if not math.isfinite(lat) or not math.isfinite(lon):
            continue
        pts.append(((lon-lon0)*kx, (lat-lat0)*111132.0, lat, lon))
    if len(pts) < 4:
        return None
    if pts[0][:2] != pts[-1][:2]:
        pts.append(pts[0])
    twice = 0.0
    cx = cy = 0.0
    for i in range(len(pts)-1):
        x1,y1=pts[i][0],pts[i][1]
        x2,y2=pts[i+1][0],pts[i+1][1]
        cross=x1*y2-x2*y1
        twice += cross
        cx += (x1+x2)*cross
        cy += (y1+y2)*cross
    area=abs(twice)*0.5
    if area < 5:
        return None
    if abs(twice)>1e-8:
        center_x=cx/(3*twice);center_y=cy/(3*twice)
    else:
        center_x=sum(p[0] for p in pts[:-1])/max(1,len(pts)-1)
        center_y=sum(p[1] for p in pts[:-1])/max(1,len(pts)-1)
    bbox=(min(p[0] for p in pts),min(p[1] for p in pts),max(p[0] for p in pts),max(p[1] for p in pts))
    return pts,area,center_x,center_y,bbox

def existing_index(elements, lat0, lon0):
    cells={}
    kx=111320.0*math.cos(math.radians(lat0))
    for e in elements:
        tags=e.get("tags") or {}
        if e.get("type")!="way" or not tags.get("building") or tags.get("building")=="no":
            continue
        geometry=e.get("geometry") or []
        coords=[[p.get("lon"),p.get("lat")] for p in geometry if p.get("lon") is not None and p.get("lat") is not None]
        metric=polygon_metrics(coords,lat0,lon0)
        if not metric: continue
        _,area,cx,cy,bbox=metric
        cell=(math.floor(cx/25),math.floor(cy/25))
        cells.setdefault(cell,[]).append((area,cx,cy,bbox))
    return cells

def overlaps_existing(metric, cells):
    _,area,cx,cy,bbox=metric
    cell=(math.floor(cx/25),math.floor(cy/25))
    for dx in (-1,0,1):
        for dy in (-1,0,1):
            for old_area,ox,oy,ob in cells.get((cell[0]+dx,cell[1]+dy),[]):
                if math.hypot(cx-ox,cy-oy)>3.2:
                    continue
                if min(bbox[2],ob[2])<=max(bbox[0],ob[0]) or min(bbox[3],ob[3])<=max(bbox[1],ob[1]):
                    continue
                ratio=min(area,old_area)/max(area,old_area)
                if ratio>=0.48:
                    return True
    return False

def features_from_line(line):
    obj=json.loads(line)
    if obj.get("type")=="FeatureCollection":
        return obj.get("features") or []
    if obj.get("type")=="Feature":
        return [obj]
    return []

def process_tile(url, city, existing_cells, seen_ids, lat0, lon0, radius):
    req=Request(url,headers={"User-Agent":USER_AGENT})
    with urlopen(req,timeout=300) as response:
        with gzip.GzipFile(fileobj=response) as gz:
            for line_no,line in enumerate(gz,1):
                if not line.strip(): continue
                for feature in features_from_line(line):
                    props=feature.get("properties") or {}
                    confidence=props.get("confidence")
                    try:
                        conf=float(confidence) if confidence is not None else -1
                    except (TypeError,ValueError):
                        conf=-1
                    if conf>=0 and conf<0.35:
                        continue
                    geom=feature.get("geometry") or {}
                    kind=geom.get("type")
                    raw=geom.get("coordinates") or []
                    polygons=[raw] if kind=="Polygon" else raw if kind=="MultiPolygon" else []
                    for polygon in polygons:
                        if not polygon: continue
                        ring=polygon[0]
                        metric=polygon_metrics(ring,lat0,lon0)
                        if not metric: continue
                        pts,area,cx,cy,bbox=metric
                        if math.hypot(cx,cy)>radius+20: continue
                        if overlaps_existing(metric,existing_cells): continue
                        canonical=[[round(p[3],7),round(p[2],7)] for p in pts]
                        digest=hashlib.sha1(json.dumps(canonical,separators=(",",":")).encode()).hexdigest()
                        if digest in seen_ids: continue
                        seen_ids.add(digest)
                        source_id=int(digest[:12],16)
                        tags={"building":"yes","source":"Microsoft Global ML Building Footprints","source:license":"CDLA-Permissive-2.0","source:id":digest[:16]}
                        try:
                            height=float(props.get("height",-1))
                            if math.isfinite(height) and height>1:
                                tags["height"]=f"{height:.2f}"
                        except (TypeError,ValueError):
                            pass
                        if conf>=0: tags["source:confidence"]=f"{conf:.3f}"
                        geometry=[{"lat":p[2],"lon":p[3]} for p in pts]
                        cell=(math.floor(cx/25),math.floor(cy/25))
                        existing_cells.setdefault(cell,[]).append((area,cx,cy,bbox))
                        yield {"type":"way","id":8000000000000000+source_id,"tags":tags,"geometry":geometry}

def git(*args,check=True):
    return subprocess.run(["git",*args],cwd=ROOT,check=check,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)

def checkpoint(slug):
    folder=f"data/offline-world/{slug}"
    git("add",folder)
    if git("diff","--cached","--quiet",check=False).returncode==0:return
    git("config","user.name","github-actions[bot]")
    git("config","user.email","41898282+github-actions[bot]@users.noreply.github.com")
    git("commit","-m",f"Add Microsoft satellite-derived building footprints: {slug}")
    for attempt in range(1,6):
        git("fetch","origin","main")
        rb=git("rebase","origin/main",check=False)
        if rb.returncode:
            git("rebase","--abort",check=False)
            raise RuntimeError(rb.stdout)
        push=git("push","origin","HEAD:main",check=False)
        if push.returncode==0:
            print(f"{slug}: MICROSOFT_FOOTPRINT_CHECKPOINT_PUSHED",flush=True);return
        time.sleep(attempt*3)
    raise RuntimeError(f"{slug}: unable to push checkpoint")

def main():
    catalog=json.loads(CATALOG.read_text(encoding="utf-8"))
    index=read_index()
    succeeded,failed=0,[]
    for city in catalog.get("cities",[]):
        slug=city["slug"];folder=DATA/slug
        elements_path=folder/"elements.json";metadata_path=folder/"metadata.json"
        try:
            if not elements_path.is_file() or not metadata_path.is_file():
                raise RuntimeError("base OSM city extract is missing")
            base=json.loads(elements_path.read_text(encoding="utf-8"))
            metadata=json.loads(metadata_path.read_text(encoding="utf-8"))
            if metadata.get("microsoft_footprints_version")==INDEX_VERSION and metadata.get("microsoft_footprints_complete") is True:
                print(f"{slug}: Microsoft footprint import already complete; skip",flush=True);succeeded+=1;continue
            radius=max(2500,int(city.get("discovery_radius_m",650)))
            keys=city_quadkeys(city,radius)
            rows=[index[k] for k in sorted(keys) if k in index]
            if not rows:
                metadata.update({"microsoft_footprints_version":INDEX_VERSION,"microsoft_footprints_complete":True,"microsoft_footprints_status":"no_coverage","microsoft_footprints_tiles":0,"microsoft_footprints_added":0})
                metadata_path.write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
                checkpoint(slug)
                print(f"{slug}: NO_MICROSOFT_TILE_COVERAGE; OSM data retained",flush=True);succeeded+=1;continue
            lat0,lon0=float(city["center"]["lat"]),float(city["center"]["lon"])
            existing_cells=existing_index(base.get("elements",[]),lat0,lon0)
            seen=set();added=0;tile_ids=[]
            for row in rows:
                url=row["Url"]
                for element in process_tile(url,city,existing_cells,seen,lat0,lon0,radius):
                    base.setdefault("elements",[]).append(element);added+=1
                tile_ids.append({"quadkey":row["QuadKey"],"location":row.get("Location"),"url":url,"upload_date":row.get("UploadDate")})
                print(f"{slug}: processed Microsoft quadkey {row['QuadKey']} added={added}",flush=True)
            base["elements"].sort(key=lambda e:(e.get("type",""),int(e.get("id",0))))
            fd,tmp=tempfile.mkstemp(prefix="elements.",suffix=".tmp",dir=folder)
            with os.fdopen(fd,"w",encoding="utf-8") as f:
                json.dump(base,f,ensure_ascii=False,separators=(",",":"));f.write("\n")
            os.replace(tmp,elements_path)
            metadata.update({"microsoft_footprints_version":INDEX_VERSION,"microsoft_footprints_complete":True,"microsoft_footprints_status":"imported","microsoft_footprints_tiles":len(rows),"microsoft_footprints_added":added,"microsoft_footprints_license":"CDLA-Permissive-2.0","microsoft_footprints_imagery_years":"2014-2024","microsoft_footprints_tile_index":tile_ids})
            metadata_path.write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
            checkpoint(slug)
            print(f"{slug}: SAVED Microsoft footprints added={added} total={len(base['elements'])}",flush=True)
            succeeded+=1
        except Exception as exc:
            failed.append((slug,str(exc)));print(f"{slug}: FAILED; continuing: {exc}",flush=True)
    print(f"MICROSOFT_FOOTPRINT_RESULT success={succeeded} failed={len(failed)}",flush=True)
    for slug,reason in failed:print(f"MICROSOFT_FOOTPRINT_PENDING {slug}: {reason}",flush=True)
    if failed:raise SystemExit("Some city imports failed; successful city checkpoints were saved. Re-run to resume.")

if __name__=="__main__":
    main()
