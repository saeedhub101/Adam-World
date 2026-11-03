import argparse,json,pathlib,subprocess,sys,os,tempfile,hashlib,urllib.request
from datetime import datetime,timezone
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE=ROOT/"data/full-city-imports"
CITIES={
"manila":("Manila",(120.90,14.50,121.08,14.68)),"amman":("Amman",(35.70,31.75,36.12,32.10)),
"petra":("Petra",(35.30,30.20,35.62,30.50)),"las-vegas":("Las Vegas",(-115.40,35.90,-114.95,36.35)),
"bucharest":("Bucharest",(25.85,44.25,26.35,44.60)),"rome":("Rome",(12.15,41.70,12.90,42.10)),
"pisa":("Pisa",(10.25,43.60,10.55,43.85)),"interlaken":("Interlaken",(7.70,46.58,8.00,46.78)),
"thun":("Thun",(7.48,46.65,7.82,46.86)),"riyadh":("Riyadh",(46.30,24.35,47.15,25.10)),
"dubai":("Dubai",(54.95,24.80,55.75,25.50)),"abu-dhabi":("Abu Dhabi",(54.10,24.15,54.90,24.80)),
"jeddah":("Jeddah",(38.75,21.20,39.50,21.95)),"mecca":("Mecca",(39.55,21.15,40.10,21.80)),
"medina":("Medina",(39.25,24.15,40.00,24.90))}
TYPES={"02_buildings_overture":["building","building_part"],"09_transport_overture":["segment","connector"],"10_landcover_overture":["land","land_cover","land_use","water","infrastructure"],"11_places_addresses":["place","address"]}
def save(p,b):
 p.parent.mkdir(parents=True,exist_ok=True);t=p.with_suffix(p.suffix+".tmp");t.write_bytes(b);os.replace(t,p)
def checkpoint(paths,slug):
 rel=[str(p.relative_to(ROOT)) for p in paths if p.exists()]
 if not rel:return
 subprocess.run(["git","add","--",*rel],cwd=ROOT,check=True)
 if subprocess.run(["git","diff","--cached","--quiet"],cwd=ROOT).returncode==0:return
 subprocess.run(["git","config","user.name","github-actions[bot]"],cwd=ROOT,check=True)
 subprocess.run(["git","config","user.email","41898282+github-actions[bot]@users.noreply.github.com"],cwd=ROOT,check=True)
 subprocess.run(["git","commit","-m","Checkpoint full city data "+slug],cwd=ROOT,check=True)
 subprocess.run(["git","push","origin","HEAD:main"],cwd=ROOT,check=True)
def main():
 ap=argparse.ArgumentParser();ap.add_argument("--city");ap.add_argument("--refresh",action="store_true");a=ap.parse_args()
 cities=[a.city] if a.city else list(CITIES);failed=[]
 for slug in cities:
  if slug not in CITIES:failed.append(slug);continue
  name,bbox=CITIES[slug];folder=BASE/slug;w,s,e,n=bbox
  print("START",slug,flush=True);saved=[]
  try:
   import shapely
   from shapely.geometry import shape,mapping,box
   from shapely.ops import unary_union
   # Resolve exact administrative polygon before treating any download as full-city data.
   bd=folder/"01_admin_boundary";bd.mkdir(parents=True,exist_ok=True);bp=bd/"division-area-candidates.geojson"
   if a.refresh or not bp.exists():
    subprocess.run(["overturemaps","download",f"--bbox={w},{s},{e},{n}","-f","geojson","--type=division_area","-o",str(bp)],cwd=ROOT,check=True,timeout=1800)
   saved.append(bp);raw=json.loads(bp.read_text());matches=[]
   for f in raw.get("features",[]):
    p=f.get("properties") or {};names=p.get("names") or {};nm=str(names.get("primary") or p.get("name") or "").casefold()
    if nm==name.casefold() and f.get("geometry"):matches.append((p,shape(f["geometry"]),f))
   if matches:
    matches.sort(key=lambda x:x[1].area,reverse=True);props,poly,feature=matches[0]
    if len(matches)>1 and abs(matches[0][1].area-matches[1][1].area)<1e-10:raise RuntimeError("Ambiguous city boundary")
    boundary_source="Overture Maps division_area (exact name match)"
   else:
    # Explicit approximate extent fallback; never label it as an official boundary.
    poly=box(w,s,e,n);props={}
    boundary_source="configured_bbox_extent (approximate; no exact administrative boundary)"
    print("WARNING",slug,"using configured bbox extent, not official city limits",flush=True)
   bfc={"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":name,"source":boundary_source,"subtype":props.get("subtype")},"geometry":mapping(poly)}]}
   bfile=bd/"city-boundary.geojson";save(bfile,json.dumps(bfc).encode());saved.append(bfile)
   from ijson import items as json_items
   MAX_PART_BYTES=35*1024*1024
   for dest,types in TYPES.items():
    for typ in types:
     layer_dir=folder/dest/typ
     layer_dir.mkdir(parents=True,exist_ok=True)
     manifest_path=layer_dir/"manifest.json"
     manifest={}
     if manifest_path.exists() and not a.refresh:
      try: manifest=json.loads(manifest_path.read_text())
      except Exception: manifest={}
      if manifest.get("status")=="empty" and manifest.get("empty_reason")=="source_returned_zero_features":
        print("SKIP VERIFIED EMPTY",slug,typ,flush=True);continue
     if manifest.get("status")=="complete":
       parts=manifest.get("parts",[])
       if parts and all((layer_dir/p["file"]).is_file() and (layer_dir/p["file"]).stat().st_size==p["bytes"] for p in parts):
        print("SKIP COMPLETE",slug,typ,len(parts),"parts",flush=True);continue
     if a.refresh:
      for old in layer_dir.glob("part-*.geojson"):old.unlink(missing_ok=True)
      manifest={"status":"partial","source":"Overture Maps","source_type":typ,"parts":[]}
     elif not manifest:
      manifest={"status":"partial","source":"Overture Maps","source_type":typ,"parts":[]}
     tmp=layer_dir/"download.raw.geojson"
     subprocess.run(["overturemaps","download",f"--bbox={w},{s},{e},{n}","-f","geojson","--type="+typ,"-o",str(tmp)],cwd=ROOT,check=True,timeout=3600)
     known=len(manifest.get("parts",[]));part_index=0;part_features=[];part_size=0;total_features=sum(p.get("features",0) for p in manifest.get("parts",[]));raw_feature_count=0;matched_feature_count=0
     def flush_part():
      nonlocal part_index,part_features,part_size,total_features,manifest
      if not part_features:return
      part_index+=1
      if part_index<=known:
       part_features=[];part_size=0;return
      filename=f"part-{part_index:05d}.geojson"
      outpart=layer_dir/filename
      payload=b'{"type":"FeatureCollection","source":"Overture Maps","source_type":'+json.dumps(typ).encode()+b',"features":['+b",".join(part_features)+b']}'
      if len(payload)>95*1024*1024:raise RuntimeError(f"single GeoJSON chunk exceeds safe GitHub limit: {filename} {len(payload)} bytes")
      save(outpart,payload)
      entry={"file":filename,"bytes":len(payload),"features":len(part_features)}
      manifest.setdefault("parts",[]).append(entry);manifest["status"]="partial";manifest["feature_count"]=sum(p["features"] for p in manifest["parts"])
      save(manifest_path,json.dumps(manifest,indent=2).encode())
      total_features+=len(part_features)
      print("SAVED PART",slug,typ,filename,entry["features"],entry["bytes"],flush=True)
      checkpoint([outpart,manifest_path],slug)
      part_features=[];part_size=0
     try:
      with tmp.open("rb") as stream:
       for feature in json_items(stream,"features.item"):
        raw_feature_count+=1
        if not feature.get("geometry"):continue
        try:
         geom=shape(feature["geometry"])
         if not geom.intersects(poly):continue
         geom=geom.intersection(poly)
         if geom.is_empty:continue
         matched_feature_count+=1
         feature["geometry"]=mapping(geom)
         encoded=json.dumps(feature,separators=(",",":"),ensure_ascii=False).encode()
         if part_features and part_size+len(encoded)+1>MAX_PART_BYTES:flush_part()
         part_features.append(encoded);part_size+=len(encoded)+1
        except Exception:continue
      flush_part()
     finally:tmp.unlink(missing_ok=True)
     manifest["feature_count"]=sum(p["features"] for p in manifest["parts"]);manifest["raw_feature_count"]=raw_feature_count;manifest["matched_feature_count"]=matched_feature_count
     if matched_feature_count==0 and raw_feature_count>0:
      manifest["status"]="partial";manifest["empty_reason"]="download_returned_features_but_none_intersected_city_boundary";save(manifest_path,json.dumps(manifest,indent=2).encode());checkpoint([manifest_path],slug)
      raise RuntimeError(f"{typ}: downloaded {raw_feature_count} features but 0 intersected city boundary; refusing false complete manifest")
     if matched_feature_count==0:
      manifest["status"]="empty";manifest["empty_reason"]="source_returned_zero_features"
     else:manifest["status"]="complete"
     manifest["completed_at"]=datetime.now(timezone.utc).isoformat()
     save(manifest_path,json.dumps(manifest,indent=2).encode());saved.append(manifest_path)
     checkpoint([manifest_path],slug)
     print("SAVED",slug,typ,manifest["feature_count"],"features in",len(manifest["parts"]),"parts",flush=True)
   # Public image catalogues: metadata discovery only; image bytes require per-item licence checks.
   for foldername,filename,url in [
    ("05_aerial_openaerialmap","imagery-catalog.json",f"https://api.openaerialmap.org/meta?bbox={w},{s},{e},{n}&limit=100"),
    ("06_street_panoramax","panoramax-catalog.json",f"https://api.panoramax.xyz/api/search?bbox={w},{s},{e},{n}&limit=1000")]:
    p=folder/foldername/filename
    if a.refresh or not p.exists():
     try:
      req=urllib.request.Request(url,headers={"User-Agent":"Adam-World open-data import"})
      with urllib.request.urlopen(req,timeout=120) as r:save(p,r.read())
     except Exception as ex:save(p,json.dumps({"status":"failed_retryable","error":str(ex)}).encode())
    saved.append(p)
   report=folder/"12_provenance"/"import-report.json"
   save(report,json.dumps({"city":name,"boundary":boundary_source,"boundary_bbox":list(poly.bounds),"overture_layers":len(TYPES),"updated":datetime.now(timezone.utc).isoformat(),"imagery_catalogues":"metadata discovery attempted; image downloads pending licence validation"},indent=2).encode());saved.append(report)
   checkpoint(saved,slug);print("CITY CHECKPOINT",slug,flush=True)
  except Exception as ex:
   print("FAILED",slug,str(ex),flush=True);failed.append(slug)
 print("IMPORT RESULT",json.dumps({"attempted":len(cities),"failed":failed}),flush=True)
 if failed:sys.exit(1)
if __name__=="__main__":main()
