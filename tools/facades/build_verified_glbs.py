#!/usr/bin/env python3
"""Build one textured GLB per explicitly human-verified OSM building facade.

Inputs are deliberately strict: verified_facades.json, the matching city's
buildings.geojson, and local redistributable images under app assets/facades.
Proximity-only review candidates are never accepted.
"""
from __future__ import annotations
import argparse, json, math, re, sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "app/src/main/assets/facades/verified_facades.json"
ASSET_ROOT = ROOT / "app/src/main/assets/facades"
OUT_ROOT = ROOT / "app/src/main/assets/facades/glb"
REQUIRED = ("building_identity", "facade_identity", "camera_direction", "image_quality", "license")
EARTH_X = 111320.0
EARTH_Y = 111132.0

def fail(msg: str) -> None:
    raise SystemExit("FACADE GLB ERROR: " + msg)

def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))

def rings(geom: dict) -> list[list[tuple[float,float]]]:
    typ, coords = geom.get("type"), geom.get("coordinates") or []
    if typ == "Polygon":
        return [[(float(p[0]),float(p[1])) for p in coords[0]]]
    if typ == "MultiPolygon":
        return [[(float(p[0]),float(p[1])) for p in poly[0]] for poly in coords if poly]
    return []

def main() -> None:
    ap=argparse.ArgumentParser()
    ap.add_argument("--manifest",type=Path,default=MANIFEST)
    ap.add_argument("--cities-root",type=Path,default=ROOT/"data/facades/cities")
    ap.add_argument("--output",type=Path,default=OUT_ROOT)
    args=ap.parse_args()
    try:
        import numpy as np
        import trimesh
        from PIL import Image
        from shapely.geometry import Polygon, Point
        from shapely.ops import triangulate
    except Exception as exc:
        fail("install numpy trimesh Pillow shapely first: "+str(exc))

    manifest=load_json(args.manifest)
    if manifest.get("schema_version") != 1 or not isinstance(manifest.get("buildings"),list):
        fail("manifest schema_version=1 and buildings[] are required")
    assignments=manifest["buildings"]
    if not assignments:
        print("NO_VERIFIED_FACADES: manifest is empty; no GLBs generated (no guessed photo matches).")
        return

    cities={}
    for folder in sorted(p for p in args.cities_root.iterdir() if p.is_dir()):
        candidates=(folder/"layers"/"building-layer.geojson",folder/"buildings.geojson",folder/"building-footprints.geojson")
        source=next((p for p in candidates if p.is_file() and p.stat().st_size>0),None)
        if source is None: continue
        fc=load_json(source)
        for feat in fc.get("features",[]):
            props=feat.get("properties") or {}
            tags=props.get("tags") if isinstance(props.get("tags"),dict) else {}
            raw=props.get("osm_feature_id") or props.get("osm_way_id") or props.get("osm_id") or tags.get("osm_id") or props.get("id") or feat.get("id")
            if raw is None: continue
            match=re.fullmatch(r"(?:way/)?([1-9][0-9]*)",str(raw).strip(),re.I)
            if match: cities[(folder.name,match.group(1))]=feat

    grouped={}
    for item in assignments:
        osm=str(item.get("osm_way_id",""))
        city=str(item.get("city","")).strip().lower()
        if not re.fullmatch(r"[1-9][0-9]*",osm) or not city:
            fail("each verified entry must include city slug and positive osm_way_id")
        review=item.get("review") or {}
        if review.get("status")!="human_verified" or not review.get("reviewer") or not review.get("reviewed_at"):
            fail(f"{city}/{osm}: human review record missing")
        checks=review.get("checks") or {}
        missing=[k for k in REQUIRED if checks.get(k) is not True]
        if missing: fail(f"{city}/{osm}: unconfirmed review checks: {', '.join(missing)}")
        if item.get("match_method")!="visual_and_geometric": fail(f"{city}/{osm}: only visual_and_geometric matches are accepted")
        if item.get("source")!="panoramax": fail(f"{city}/{osm}: only explicitly supported Panoramax sources are accepted")
        if not item.get("photo_id"): fail(f"{city}/{osm}: photo_id missing")
        lic=item.get("license") or {}
        if not lic.get("identifier") or not lic.get("url"): fail(f"{city}/{osm}: exact licence identifier and URL required")
        rel=Path(str(item.get("local_image","")))
        if rel.is_absolute() or ".." in rel.parts: fail(f"{city}/{osm}: unsafe local_image path")
        image_path=(ASSET_ROOT/rel).resolve()
        if ASSET_ROOT.resolve() not in image_path.parents or not image_path.is_file(): fail(f"{city}/{osm}: local image missing: {rel}")
        if not rel.parts or rel.parts[0]!="verified": fail(f"{city}/{osm}: approved images must be stored under facades/verified/")
        key=(city,osm)
        if key not in cities: fail(f"{city}/{osm}: no matching OSM footprint in canonical building layer or compatibility GeoJSON")
        grouped.setdefault(key,[]).append((item,image_path))

    built=0
    for (city,osm),items in sorted(grouped.items()):
        feat=cities[(city,osm)]
        geom=feat.get("geometry") or {}
        rr=rings(geom)
        if not rr or len(rr[0])<4: fail(f"{city}/{osm}: unsupported or invalid footprint")
        outer=rr[0]
        lat0=sum(p[1] for p in outer)/len(outer); lon0=sum(p[0] for p in outer)/len(outer)
        sx=EARTH_X*math.cos(math.radians(lat0))
        def xy(ll): return ((ll[0]-lon0)*sx, -(ll[1]-lat0)*EARTH_Y)
        pts=[xy(p) for p in outer]
        poly=Polygon(pts)
        if not poly.is_valid or poly.area<4: fail(f"{city}/{osm}: invalid footprint polygon")
        props=feat.get("properties") or {}
        height=props.get("height") or props.get("building:height")
        try: h=float(str(height).replace("m","").strip()) if height else float(props.get("building:levels",2))*3.2
        except Exception: h=6.4
        h=max(2.5,min(180,h))
        meshes=[]
        # Build a closed, simple OSM-derived shell; each wall remains an independent face.
        from trimesh.visual.material import PBRMaterial
        base=PBRMaterial(name="OSM neutral masonry",baseColorFactor=[190,180,162,255],metallicFactor=0.0,roughnessFactor=0.95)
        wall_vertices=[]; wall_faces=[]
        for i,a in enumerate(pts):
            b=pts[(i+1)%len(pts)]
            k=len(wall_vertices)
            wall_vertices.extend([[a[0],0,a[1]],[b[0],0,b[1]],[b[0],h,b[1]],[a[0],h,a[1]]])
            wall_faces.extend([[k,k+1,k+2],[k,k+2,k+3]])
        wall=trimesh.Trimesh(vertices=np.asarray(wall_vertices),faces=np.asarray(wall_faces),process=False)
        wall.visual=trimesh.visual.TextureVisuals(material=base,uv=np.zeros((len(wall.vertices),2)))
        meshes.append(wall)
        # Triangulate the actual footprint for a roof; discard triangles outside concave polygons.
        roof_vertices=[]; roof_faces=[]
        for tri in triangulate(poly):
            if not poly.covers(tri.representative_point()): continue
            coords=list(tri.exterior.coords)[:3]; k=len(roof_vertices)
            roof_vertices.extend([[x,h,z] for x,z in coords]); roof_faces.append([k,k+1,k+2])
        if roof_faces:
            roof=trimesh.Trimesh(vertices=np.asarray(roof_vertices),faces=np.asarray(roof_faces),process=False)
            roof.visual=trimesh.visual.ColorVisuals(mesh=roof,face_colors=[190,180,162,255])
            meshes.append(roof)

        for item,image_path in items:
            edge=int(item.get("facade_edge_index",-1))
            if edge<0 or edge>=len(pts): fail(f"{city}/{osm}: facade edge index out of range")
            cam=item.get("camera") or {}
            required=("capture_lat","capture_lon","azimuth_deg","pitch_deg","horizontal_fov_deg")
            if any(not isinstance(cam.get(k),(int,float)) for k in required): fail(f"{city}/{osm}: incomplete camera calibration")
            if not 1<float(cam["horizontal_fov_deg"])<179: fail(f"{city}/{osm}: invalid FOV")
            cp=xy((float(cam["capture_lon"]),float(cam["capture_lat"])))
            camera=np.array([cp[0],float(cam.get("capture_height_m",1.5)),cp[1]],dtype=float)
            az=math.radians(float(cam["azimuth_deg"])); pitch=math.radians(float(cam["pitch_deg"]))
            forward=np.array([math.sin(az)*math.cos(pitch),math.sin(pitch),-math.cos(az)*math.cos(pitch)])
            right=np.array([math.cos(az),0,math.sin(az)])
            up=np.cross(right,forward)
            hfov=math.radians(float(cam["horizontal_fov_deg"]))
            with Image.open(image_path) as im:
                image=im.convert("RGB")
                iw,ih=image.size
            aspect=iw/max(1,ih); tan_h=math.tan(hfov/2); tan_v=tan_h/aspect
            a=np.array([*pts[edge][:1],0,*pts[edge][1:]],dtype=float)
            nxt=pts[(edge+1)%len(pts)]
            b=np.array([nxt[0],0,nxt[1]],dtype=float)
            face=[a,b,np.array([nxt[0],h,nxt[1]]),np.array([pts[edge][0],h,pts[edge][1]])]
            def depth(p): return float(np.dot(p-camera,forward))
            def plane_value(p,fn): 
                r=p-camera; d=float(np.dot(r,forward)); x=float(np.dot(r,right)); y=float(np.dot(r,up))
                return {"near":d-0.05,"left":x+d*tan_h,"right":d*tan_h-x,"bottom":y+d*tan_v,"top":d*tan_v-y}[fn]
            for plane in ("near","left","right","bottom","top"):
                out=[]
                for j,cur in enumerate(face):
                    nxtp=face[(j+1)%len(face)]
                    dc=plane_value(cur,plane); dn=plane_value(nxtp,plane)
                    inc=dc>=0; inn=dn>=0
                    if inc: out.append(cur)
                    if inc!=inn:
                        t=dc/(dc-dn); out.append(cur+(nxtp-cur)*t)
                face=out
                if len(face)<3: break
            if len(face)<3: continue
            uv=[]
            for p in face:
                r=p-camera; d=depth(p)
                u=0.5+0.5*float(np.dot(r,right))/(d*tan_h)
                v=0.5+0.5*float(np.dot(r,up))/(d*tan_v)
                uv.append([u,1-v])
            vertices=np.asarray(face,dtype=float)
            faces=np.asarray([[0,i,i+1] for i in range(1,len(face)-1)],dtype=int)
            patch=trimesh.Trimesh(vertices=vertices,faces=faces,process=False)
            with Image.open(image_path) as im: tex=im.convert("RGB")
            material=trimesh.visual.material.PBRMaterial(name="Human verified Panoramax facade",baseColorTexture=tex,metallicFactor=0.0,roughnessFactor=1.0)
            patch.visual=trimesh.visual.TextureVisuals(uv=np.asarray(uv,dtype=float),material=material)
            meshes.append(patch)

        scene=trimesh.Scene()
        for n,mesh in enumerate(meshes): scene.add_geometry(mesh,node_name=f"{osm}_part_{n}")
        dest=args.output/city/f"{osm}.glb"
        dest.parent.mkdir(parents=True,exist_ok=True)
        data=scene.export(file_type="glb")
        dest.write_bytes(data)
        built+=1
        print(f"GLB_BUILT city={city} osm_way_id={osm} path={dest.relative_to(ROOT)} meshes={len(meshes)} facade_photos={len(items)}")
    print(f"FACADE_GLB_TOTAL buildings_built={built} assignments={len(assignments)}")

if __name__=="__main__":
    main()
