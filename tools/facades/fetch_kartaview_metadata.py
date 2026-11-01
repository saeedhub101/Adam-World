#!/usr/bin/env python3
"""Fetch public KartaView street-level imagery metadata for facade discovery.

Uses the anonymous 1.0 nearby-photos endpoint. Results are candidates only:
the exact building identity must still be visually confirmed before texturing.
"""
from __future__ import annotations
import argparse, json, math, os, subprocess, tempfile, time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT=Path(__file__).resolve().parents[2]
DEFAULT_ROOT=ROOT/"data/facades/cities"
ENDPOINT="https://api.openstreetcam.org/1.0/list/nearby-photos/"

def atomic(path:Path,obj:dict)->None:
    path.parent.mkdir(parents=True,exist_ok=True)
    data=json.dumps(obj,ensure_ascii=False,indent=2)+"\n"
    fd,tmp=tempfile.mkstemp(prefix=path.name+".",dir=path.parent)
    try:
        with os.fdopen(fd,"w",encoding="utf-8") as f:f.write(data)
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)

def post(lat:float,lon:float,radius:int,timeout:int)->dict:
    body=urlencode({"lat":lat,"lng":lon,"radius":radius}).encode()
    req=Request(ENDPOINT,data=body,headers={"Content-Type":"application/x-www-form-urlencoded","Accept":"application/json","User-Agent":"AdamWorld-FacadeEvidence/1.0"})
    with urlopen(req,timeout=timeout) as res:
        if res.status!=200:raise RuntimeError(f"HTTP {res.status}")
        obj=json.loads(res.read().decode("utf-8"))
    status=obj.get("status") or {}
    code=status.get("apiCode")
    if code not in (None,600,"600"):
        raise RuntimeError(f"KartaView API code {code}: {status.get('apiMessage')}")
    return obj

def records(obj):
    result=obj.get("result") or {}
    for key in ("data","photos","items"):
        value=result.get(key) if isinstance(result,dict) else None
        if isinstance(value,list):return value
    for key in ("photos","data","result"):
        value=obj.get(key)
        if isinstance(value,list):return value
    return []

def getv(p,*keys):
    for k in keys:
        v=p.get(k)
        if v not in (None,""):return v
    return None

def city_bbox(city:Path):
    fc=json.loads((city/"buildings.geojson").read_text(encoding="utf-8"))
    pts=[]
    def walk(v):
        if isinstance(v,list):
            if len(v)>=2 and isinstance(v[0],(int,float)) and isinstance(v[1],(int,float)):pts.append((float(v[0]),float(v[1])))
            else:
                for x in v:walk(x)
    for f in fc.get("features",[]):walk((f.get("geometry") or {}).get("coordinates",[]))
    if not pts:raise ValueError("no building coordinates")
    return min(x for x,y in pts),min(y for x,y in pts),max(x for x,y in pts),max(y for x,y in pts)

def normalize(raw:dict)->dict|None:
    lat=getv(raw,"lat","latitude","Lat")
    lon=getv(raw,"lng","lon","longitude","Lon")
    try:lat=float(lat);lon=float(lon)
    except (TypeError,ValueError):return None
    pid=str(getv(raw,"id","photoId","photo_id") or "")
    if not pid:return None
    full=getv(raw,"fileUrl","file_url","imageUrl","image_url","url")
    thumb=getv(raw,"thumbUrl","thumb_url","thumbnailUrl","thumbnail_url") or full
    if full and not str(full).startswith("http"):
        full="https://api.openstreetcam.org/"+str(full).lstrip("/")
    if thumb and not str(thumb).startswith("http"):
        thumb="https://api.openstreetcam.org/"+str(thumb).lstrip("/")
    if not full:return None
    az=getv(raw,"heading","direction","azimuth","view:azimuth")
    try:az=float(az)%360 if az is not None else None
    except (TypeError,ValueError):az=None
    fov=getv(raw,"fieldOfView","field_of_view","fov")
    try:fov=float(fov) if fov is not None else None
    except (TypeError,ValueError):fov=None
    dt=getv(raw,"shotDate","shot_date","date","timestamp","createdAt","created_at")
    props={"source":"kartaview","license":"CC-BY-SA-4.0","view:azimuth":az,"pers:field_of_view":fov,"datetime":dt,"sequence_id":getv(raw,"sequenceId","sequence_id"),"sequence_index":getv(raw,"sequenceIndex","sequence_index"),"attribution":"© KartaView contributors"}
    return {"type":"Feature","id":pid,"geometry":{"type":"Point","coordinates":[lon,lat]},"properties":props,"assets":{"thumbnail":{"href":str(thumb),"roles":["thumbnail"]},"visual":{"href":str(full),"roles":["visual"]}}}

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--cities-root",type=Path,default=DEFAULT_ROOT)
    ap.add_argument("--city",default="amman")
    ap.add_argument("--radius-m",type=int,default=500)
    ap.add_argument("--timeout",type=int,default=40)
    ap.add_argument("--delay",type=float,default=1.0)
    ap.add_argument("--git-checkpoint",action="store_true")
    args=ap.parse_args()
    if not 50<=args.radius_m<=500:ap.error("--radius-m must be 50..500")
    city=args.cities_root/args.city
    west,south,east,north=city_bbox(city)
    # Grid spacing is one radius; every point is queried once and duplicate photos are removed.
    lat_step=args.radius_m/111320.0
    mid=(south+north)/2
    lon_step=args.radius_m/(111320.0*max(.01,math.cos(math.radians(mid))))
    points=[]
    lat=south
    while lat<=north+1e-10:
        lon=west
        while lon<=east+1e-10:
            points.append((lat,lon));lon+=lon_step
        lat+=lat_step
    out=city/"kartaview-items.geojson"
    state_path=city/".kartaview-fetch-state.json"
    state=json.loads(state_path.read_text()) if state_path.exists() else {"next":0,"bbox":[west,south,east,north],"seen":[]}
    if state.get("bbox")!=[west,south,east,north]:state={"next":0,"bbox":[west,south,east,north],"seen":[]}
    fc=json.loads(out.read_text()) if out.exists() else {"type":"FeatureCollection","features":[]}
    seen=set(state.get("seen",[]))|{str(f.get("id")) for f in fc["features"]}
    for i in range(int(state.get("next",0)),len(points)):
        lat,lon=points[i]
        try:
            raw=post(lat,lon,args.radius_m,args.timeout)
            items=records(raw);added=0
            for item in items:
                f=normalize(item)
                if f and f["id"] not in seen:
                    fc["features"].append(f);seen.add(f["id"]);added+=1
            print(f"KARTAVIEW {city.name} point={i+1}/{len(points)} returned={len(items)} added={added}",flush=True)
        except Exception as exc:
            print(f"KARTAVIEW_ERROR {city.name} point={i+1}/{len(points)} {exc}",flush=True)
        state.update({"next":i+1,"seen":sorted(seen),"bbox":[west,south,east,north],"complete":i+1==len(points)})
        atomic(out,fc);atomic(state_path,state)
        if args.git_checkpoint:
            rel=[str(out.relative_to(Path.cwd())),str(state_path.relative_to(Path.cwd()))]
            subprocess.run(["git","add",*rel],check=True)
            changed=subprocess.run(["git","status","--porcelain"],capture_output=True,text=True,check=True).stdout.strip()
            if changed:
                subprocess.run(["git","commit","-m",f"Checkpoint {city.name} KartaView imagery {i+1}"],check=True)
                subprocess.run(["git","pull","--rebase","origin","main"],check=True)
                subprocess.run(["git","push","origin","HEAD:main"],check=True)
        time.sleep(args.delay)
    print(f"KARTAVIEW_SUMMARY city={city.name} photos={len(fc['features'])} complete={state['complete']}",flush=True)

if __name__=="__main__":main()
