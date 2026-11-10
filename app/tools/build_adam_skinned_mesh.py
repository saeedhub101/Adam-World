#!/usr/bin/env python3
"""Convert the repository's animated Adam GLB into the native AWM5 skinned format.

Only the compact runtime mesh is written to Android assets. The source GLB remains
in the repository and is never copied into the APK. Large rigs are reduced to the 24 most influential joints while retaining required ancestors.
"""
from __future__ import annotations
import json, math, struct
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "Adam_Walking.glb"
OUT = ROOT / "app/src/main/assets/models/runtime/characters/adam.awmesh5"
MAX_BONES = 24
MAX_VERTICES = 250000
MAX_KEYS_TOTAL = 20000

def glb_parts(path):
    raw = path.read_bytes()
    if raw[:4] != b"glTF" or struct.unpack_from("<I", raw, 4)[0] != 2:
        raise ValueError("Adam source is not a GLB v2 file")
    declared = struct.unpack_from("<I", raw, 8)[0]
    if declared != len(raw): raise ValueError("GLB length mismatch")
    pos, doc, binary = 12, None, None
    while pos < len(raw):
        size, kind = struct.unpack_from("<II", raw, pos); pos += 8
        chunk = raw[pos:pos+size]; pos += size
        if kind == 0x4E4F534A: doc = json.loads(chunk.decode("utf-8").rstrip(" \0"))
        elif kind == 0x004E4942: binary = chunk
    if doc is None or binary is None: raise ValueError("GLB JSON/BIN chunk missing")
    return doc, binary

COMPONENTS = {5120:("i1",1),5121:("u1",1),5122:("<i2",2),5123:("<u2",2),5125:("<u4",4),5126:("<f4",4)}
WIDTHS = {"SCALAR":1,"VEC2":2,"VEC3":3,"VEC4":4,"MAT4":16}

def accessor(doc, binary, index):
    a = doc["accessors"][index]
    if "sparse" in a: raise ValueError("sparse glTF accessors are not supported by this converter")
    if "bufferView" not in a: raise ValueError("accessor without bufferView")
    view = doc["bufferViews"][a["bufferView"]]
    typ, width = COMPONENTS[a["componentType"]]
    n, count = WIDTHS[a["type"]], a["count"]
    stride = view.get("byteStride", width*n)
    start = view.get("byteOffset",0)+a.get("byteOffset",0)
    if stride == width*n:
        arr=np.frombuffer(binary,dtype=typ,count=count*n,offset=start).copy().reshape(count,n)
    else:
        arr=np.empty((count,n),dtype=np.dtype(typ))
        for i in range(count): arr[i]=np.frombuffer(binary,dtype=typ,count=n,offset=start+i*stride)
    if a.get("normalized",False) and a["componentType"] != 5126:
        if a["componentType"] in (5120,5122):
            info=np.iinfo(arr.dtype); arr=np.maximum(arr.astype(np.float32)/info.max,-1.0)
        else: arr=arr.astype(np.float32)/np.iinfo(arr.dtype).max
    return arr[:,0] if n==1 else arr

def quat_matrix(q):
    x,y,z,w=map(float,q)
    n=math.sqrt(x*x+y*y+z*z+w*w) or 1.0
    x,y,z,w=x/n,y/n,z/n,w/n
    return np.array([[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),0],
                     [2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),0],
                     [2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y),0],
                     [0,0,0,1]],dtype=np.float64)

def compose(t,r,s):
    m=quat_matrix(r); m[:3,0]*=s[0];m[:3,1]*=s[1];m[:3,2]*=s[2];m[:3,3]=t
    return m

def node_local(node):
    if "matrix" in node: return np.array(node["matrix"],dtype=np.float64).reshape((4,4),order="F")
    return compose(node.get("translation",[0,0,0]),node.get("rotation",[0,0,0,1]),node.get("scale",[1,1,1]))

def node_globals(doc):
    nodes=doc["nodes"]; parents=[-1]*len(nodes)
    for i,n in enumerate(nodes):
        for c in n.get("children",[]): parents[c]=i
    globals_=[None]*len(nodes); visiting=set()
    def calc(i):
        if globals_[i] is not None:return globals_[i]
        if i in visiting:raise ValueError("cyclic node hierarchy")
        visiting.add(i); local=node_local(nodes[i]); p=parents[i]
        globals_[i]=calc(p)@local if p>=0 else local
        visiting.remove(i);return globals_[i]
    for i in range(len(nodes)):calc(i)
    return parents,globals_

def decompose(m):
    t=m[:3,3].copy()
    s=np.array([np.linalg.norm(m[:3,0]),np.linalg.norm(m[:3,1]),np.linalg.norm(m[:3,2])])
    if np.any(s<1e-8): raise ValueError("singular joint transform")
    r=m[:3,:3]/s
    # Rotation matrix -> xyzw quaternion.
    tr=float(np.trace(r))
    if tr>0:
        z=math.sqrt(tr+1.0)*2; q=[(r[2,1]-r[1,2])/z,(r[0,2]-r[2,0])/z,(r[1,0]-r[0,1])/z,.25*z]
    elif r[0,0]>r[1,1] and r[0,0]>r[2,2]:
        z=math.sqrt(1+r[0,0]-r[1,1]-r[2,2])*2;q=[.25*z,(r[0,1]+r[1,0])/z,(r[0,2]+r[2,0])/z,(r[2,1]-r[1,2])/z]
    elif r[1,1]>r[2,2]:
        z=math.sqrt(1+r[1,1]-r[0,0]-r[2,2])*2;q=[(r[0,1]+r[1,0])/z,.25*z,(r[1,2]+r[2,1])/z,(r[0,2]-r[2,0])/z]
    else:
        z=math.sqrt(1+r[2,2]-r[0,0]-r[1,1])*2;q=[(r[0,2]+r[2,0])/z,(r[1,2]+r[2,1])/z,.25*z,(r[1,0]-r[0,1])/z]
    q=np.asarray(q,dtype=np.float64);q/=np.linalg.norm(q)
    return t,q,s

def sample_channel(times, values, target_times, path, interpolation):
    times=np.asarray(times,dtype=np.float64); values=np.asarray(values,dtype=np.float64)
    if interpolation=="CUBICSPLINE":
        values=values.reshape(len(times),3,-1)[:,1,:]
    out=[]
    for t in target_times:
        k=max(0,min(len(times)-1,int(np.searchsorted(times,t,side="right")-1)))
        k1=min(k+1,len(times)-1)
        if interpolation=="STEP" or k==k1 or times[k1]-times[k]<1e-8: a=0.0
        else:a=float((t-times[k])/(times[k1]-times[k]))
        v0,v1=values[k],values[k1]
        v=(1-a)*v0+a*v1
        if path=="rotation":
            v0=v0/ max(np.linalg.norm(v0),1e-12);v1=v1/max(np.linalg.norm(v1),1e-12)
            dot=float(np.dot(v0,v1))
            if dot<0:v1=-v1;dot=-dot
            if dot>.9995:v=(1-a)*v0+a*v1
            else:
                th=math.acos(max(-1,min(1,dot))); sn=math.sin(th)
                v=math.sin((1-a)*th)/sn*v0+math.sin(a*th)/sn*v1
            v=v/max(np.linalg.norm(v),1e-12)
        out.append(v)
    return np.asarray(out)

def main():
    if not SOURCE.is_file(): raise FileNotFoundError(f"source missing: {SOURCE}")
    doc,binary=glb_parts(SOURCE)
    parents,globals_=node_globals(doc)
    skins=doc.get("skins",[])
    candidates=[]
    for ni,node in enumerate(doc["nodes"]):
        if "mesh" in node and "skin" in node: candidates.append((ni,node["mesh"],node["skin"]))
    if not candidates: raise ValueError("Adam GLB has no skinned mesh node")
    mesh_node,mesh_index,skin_index=candidates[0]
    skin=skins[skin_index]; joints=skin["joints"]; source_bone_count=len(joints)
    print(f"ADAM_SOURCE nodes={len(doc['nodes'])} skins={len(skins)} joints={source_bone_count} animations={len(doc.get('animations',[]))}",flush=True)
    if source_bone_count<1: raise ValueError("empty skin")
    joint_pos={n:i for i,n in enumerate(joints)}
    # Rank joints by actual vertex influence, retaining skin-joint ancestors.
    influence={n:0.0 for n in joints}
    for node_id,node_mesh,node_skin in candidates:
        if node_skin!=skin_index: continue
        for primitive in doc["meshes"][node_mesh]["primitives"]:
            attrs=primitive["attributes"]
            if "JOINTS_0" not in attrs or "WEIGHTS_0" not in attrs: continue
            jj=np.asarray(accessor(doc,binary,attrs["JOINTS_0"]),dtype=np.int64)
            ww=np.asarray(accessor(doc,binary,attrs["WEIGHTS_0"]),dtype=np.float64)
            for ids,weights in zip(jj,ww):
                for ji,weight in zip(ids,weights):
                    if 0<=int(ji)<source_bone_count and weight>0:
                        influence[joints[int(ji)]]+=float(weight)
    ranked=sorted(joints,key=lambda n:influence[n],reverse=True)
    selected=set()
    for node_id in ranked:
        chain=[]; anc=node_id
        while anc>=0:
            if anc in joint_pos and anc not in selected: chain.append(anc)
            anc=parents[anc]
        if chain and len(selected)+len(chain)<=MAX_BONES: selected.update(chain)
        if len(selected)>=MAX_BONES: break
    if not selected: selected.add(joints[0])
    ordered=sorted(selected,key=lambda n:sum(1 for _ in iter_ancestors(n,parents)))
    old_to_new={old:i for i,old in enumerate(ordered)}
    node_to_selected={}
    for old in joints:
        anc=old
        while anc>=0 and anc not in old_to_new: anc=parents[anc]
        if anc<0: anc=ordered[0]
        node_to_selected[old]=anc
    bone_count=len(ordered)
    print(f"ADAM_RIG_COMPRESSED source_bones={source_bone_count} runtime_bones={bone_count} mapped={sum(1 for n in joints if node_to_selected[n]!=n)}",flush=True)
    mesh_global=globals_[mesh_node]; inv_mesh=np.linalg.inv(mesh_global)
    ibm_source=np.asarray([np.asarray(row,dtype=np.float64).reshape((4,4),order="F") for row in accessor(doc,binary,skin["inverseBindMatrices"])]) if "inverseBindMatrices" in skin else np.repeat(np.eye(4)[None,:,:],source_bone_count,axis=0)
    if len(ibm_source)!=source_bone_count:raise ValueError("source inverse bind matrix count mismatch")
    ibm=ibm_source[[joint_pos[n] for n in ordered]]
    bone_parent=[];bind_t=[];bind_r=[];bind_s=[];inverse_bind=[]
    for old in ordered:
        anc=parents[old]
        while anc>=0 and anc not in old_to_new:anc=parents[anc]
        parent_new=old_to_new[anc] if anc>=0 else -1
        local=np.linalg.inv(globals_[anc])@globals_[old] if anc>=0 else globals_[old]
        t,q,s=decompose(local)
        bone_parent.append(parent_new);bind_t.extend(t);bind_r.extend(q);bind_s.extend(s)
        # Vertices are stored in mesh-node world coordinates, so remove mesh transform here.
        inverse_bind.extend((ibm[old_to_new[old]]@inv_mesh).reshape(16,order="F"))
    vertices=[];triangles=[];base_vertex=0
    for node_id,node_mesh,node_skin in candidates:
        if node_skin!=skin_index:continue
        g=globals_[node_id];normal_matrix=np.linalg.inv(g[:3,:3]).T
        mesh=doc["meshes"][node_mesh]
        for primitive in mesh["primitives"]:
            if primitive.get("mode",4)!=4:raise ValueError("only triangle-list primitives are supported")
            attrs=primitive["attributes"]
            required=("POSITION","JOINTS_0","WEIGHTS_0")
            if any(k not in attrs for k in required):raise ValueError("skinned primitive missing POSITION/JOINTS_0/WEIGHTS_0")
            p=np.asarray(accessor(doc,binary,attrs["POSITION"]),dtype=np.float64)
            j=np.asarray(accessor(doc,binary,attrs["JOINTS_0"]),dtype=np.int64)
            w=np.asarray(accessor(doc,binary,attrs["WEIGHTS_0"]),dtype=np.float64)
            if len(p)!=len(j) or len(p)!=len(w):raise ValueError("skin attribute count mismatch")
            hp=np.concatenate([p,np.ones((len(p),1))],axis=1);p=(g@hp.T).T[:,:3]
            if "NORMAL" in attrs:
                n=np.asarray(accessor(doc,binary,attrs["NORMAL"]),dtype=np.float64)@normal_matrix.T
                n/=np.maximum(np.linalg.norm(n,axis=1,keepdims=True),1e-10)
            else:n=np.tile([0.,1.,0.],(len(p),1))
            uv=np.asarray(accessor(doc,binary,attrs["TEXCOORD_0"]),dtype=np.float64) if "TEXCOORD_0" in attrs else np.zeros((len(p),2))
            col=np.asarray(accessor(doc,binary,attrs["COLOR_0"]),dtype=np.float64) if "COLOR_0" in attrs else np.ones((len(p),4))
            if col.ndim==1:col=col[:,None]
            if col.shape[1]==3:col=np.column_stack([col,np.ones(len(col))])
            if len(p)+len(vertices)>MAX_VERTICES:raise ValueError("Adam mesh exceeds runtime vertex budget")
            for k in range(len(p)):
                accum={}
                for ji,weight in zip(j[k],w[k]):
                    if weight<=0:continue
                    if ji<0 or ji>=source_bone_count:raise ValueError("joint index outside source skin")
                    mapped_node=node_to_selected[joints[int(ji)]]
                    new=old_to_new[mapped_node]
                    accum[new]=accum.get(new,0.0)+float(weight)
                ranked=sorted(accum.items(),key=lambda x:x[1],reverse=True)[:4]
                total=sum(v for _,v in ranked)
                if total<1e-8:ranked=[(0,1.0)];total=1.0
                ids=[x[0] for x in ranked]+[0]*(4-len(ranked));weights=[x[1]/total for x in ranked]+[0.]*(4-len(ranked))
                vertices.append([*p[k],*n[k],*uv[k],*col[k,:3],*ids,*weights])
            idx=accessor(doc,binary,primitive["indices"]) if "indices" in primitive else np.arange(len(p))
            if len(idx)%3:raise ValueError("triangle index count is not divisible by three")
            triangles.extend((idx.astype(np.int64)+base_vertex).tolist())
            base_vertex+=len(p)
    if not vertices:raise ValueError("no renderable skinned vertices")
    # Convert triangle indices into the non-indexed draw stream expected by GLES2.
    data=np.asarray(vertices,dtype=np.float32)[np.asarray(triangles,dtype=np.int64)]
    if len(data)<3 or len(data)>MAX_VERTICES:raise ValueError("expanded triangle count exceeds the 250k runtime vertex budget")
    # Center horizontally and place the lowest point on the ground plane.
    lo=data[:,:3].min(axis=0);hi=data[:,:3].max(axis=0)
    center=np.array([(lo[0]+hi[0])*.5,lo[1],(lo[2]+hi[2])*.5])
    data[:,:3]-=center
    # Apply the same origin shift to root-joint transforms and inverse bind matrices.
    shift=np.eye(4);shift[:3,3]=-center
    inv_shift=np.linalg.inv(shift)
    for b,p in enumerate(bone_parent):
        if p<0:
            m=compose(bind_t[b*3:b*3+3],bind_r[b*4:b*4+4],bind_s[b*3:b*3+3])
            t,q,s=decompose(shift@m)
            bind_t[b*3:b*3+3]=t;bind_r[b*4:b*4+4]=q;bind_s[b*3:b*3+3]=s
        ib=np.asarray(inverse_bind[b*16:b*16+16]).reshape((4,4),order="F")
        inverse_bind[b*16:b*16+16]=(ib@inv_shift).reshape(16,order="F")
    clips=[]
    for ai,anim in enumerate(doc.get("animations",[])[:32]):
        node_channels={}
        max_time=0.0
        for ch in anim.get("channels",[]):
            target=ch.get("target",{});node=target.get("node");path=target.get("path")
            if node not in old_to_new or path not in ("translation","rotation","scale"):continue
            sampler=anim["samplers"][ch["sampler"]]
            times=np.asarray(accessor(doc,binary,sampler["input"]),dtype=np.float64).reshape(-1)
            vals=np.asarray(accessor(doc,binary,sampler["output"]),dtype=np.float64)
            interp=sampler.get("interpolation","LINEAR")
            if interp=="CUBICSPLINE":
                vals=vals.reshape(len(times),3,-1)[:,1,:]
                interp="LINEAR"
            node_channels.setdefault(node,{})[path]=(times,vals,interp)
            if len(times):max_time=max(max_time,float(times[-1]))
        if max_time<=1e-5:continue
        tracks=[]
        for old in ordered:
            channels=node_channels.get(old)
            if not channels:continue
            all_times=np.unique(np.concatenate([v[0] for v in channels.values()]))
            if len(all_times)>240:
                all_times=np.linspace(0,max_time,240)
            bi=old_to_new[old]
            bt=np.asarray(bind_t[bi*3:bi*3+3],dtype=np.float64)
            bq=np.asarray(bind_r[bi*4:bi*4+4],dtype=np.float64)
            bs=np.asarray(bind_s[bi*3:bi*3+3],dtype=np.float64)
            T=np.tile(bt,(len(all_times),1));R=np.tile(bq,(len(all_times),1));S=np.tile(bs,(len(all_times),1))
            for path,(times,vals,interp) in channels.items():
                sampled=sample_channel(times,vals,all_times,path,interp)
                if path=="translation" and bone_parent[old_to_new[old]]<0:sampled=sampled-center
                if path=="translation":T=sampled
                elif path=="rotation":R=sampled
                else:S=sampled
            tracks.append((old_to_new[old],all_times,T,R,S))
        if tracks:clips.append((anim.get("name",f"animation_{ai}"),max_time,tracks))
    total_keys=sum(len(t[1]) for _,_,tr in clips for t in tr)
    if total_keys>MAX_KEYS_TOTAL:raise ValueError(f"animation keys {total_keys} exceed runtime cap {MAX_KEYS_TOTAL}")
    OUT.parent.mkdir(parents=True,exist_ok=True)
    with OUT.open("wb") as f:
        f.write(struct.pack("<6I",0x354d5741,len(data),bone_count,len(clips),19,0))
        f.write(data.astype("<f4",copy=False).tobytes())
        for i,old in enumerate(ordered):
            name=doc["nodes"][old].get("name",f"bone_{i}").encode("utf-8")[:96]
            f.write(struct.pack("<iI",bone_parent[i],len(name)));f.write(name)
            f.write(struct.pack("<3f4f3f16f",*bind_t[i*3:i*3+3],*bind_r[i*4:i*4+4],*bind_s[i*3:i*3+3],*inverse_bind[i*16:i*16+16]))
        for name,duration,tracks in clips:
            bname=name.encode("utf-8")[:96];f.write(struct.pack("<I",len(bname)));f.write(bname)
            f.write(struct.pack("<fI",duration,len(tracks)))
            for bone,times,T,R,S in tracks:
                f.write(struct.pack("<II",bone,len(times)))
                for k,t in enumerate(times):
                    f.write(struct.pack("<11f",float(t),*T[k],*R[k],*S[k]))
    print(f"ADAM_AWM5_BUILT vertices={len(data)} bones={bone_count} clips={len(clips)} keys={total_keys} bytes={OUT.stat().st_size}",flush=True)

def iter_ancestors(node,parents):
    p=parents[node]
    while p>=0:
        yield p;p=parents[p]

if __name__=="__main__":
    main()
