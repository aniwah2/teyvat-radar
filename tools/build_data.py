"""Monta os dados do Teyvat Radar a partir da API do mapa interativo da HoYoLAB.

Saidas (em OUT/):
  map_z-4.jpg, map_z-3.jpg, map_z-2.jpg  mapa de Teyvat costurado em 3 escalas
  meta.json      tamanho total, origem, versao do mapa
  categories.json arvore de categorias (id, nome, icone, filhos)
  points.json    pontos agrupados por categoria: {label_id: [[x, y, z_level], ...]}
                 x, y em pixels do mapa no zoom 0 (ja somada a origem)
  icons/<id>.png icones das categorias (64 px)
"""
import io, json, os, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
os.makedirs(os.path.join(OUT, "icons"), exist_ok=True)
H = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile Safari/537.36",
     "Referer": "https://act.hoyolab.com/"}
API = "https://sg-public-api-static.hoyolab.com/common/map_user/ys_obc/v1/map"
Q = "app_sn=ys_obc&lang=pt-pt"
TILE = "https://act-webstatic.hoyoverse.com/map_manage/map/{mid}/{ver}/{x}_{y}_{z}.webp"
MAP_ID = 2


def fetch(url, tries=3):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=60) as r:
                return r.read()
        except Exception as e:
            err = e
    raise err


def api(path):
    return json.loads(fetch(f"{API}/{path}{'&' if '?' in path else '?'}{Q}"))["data"]


info = api(f"info?map_id={MAP_ID}")["info"]
d2 = info["detail_v2"]
W, Hh = d2["total_size"]
meta = {"map_id": MAP_ID, "total_size": d2["total_size"], "origin": d2["origin"],
        "padding": d2["padding"], "original_map_size": d2["original_map_size"],
        "map_version": d2["map_version"], "zooms": [-4, -3, -2]}

# ---------------------------------------------------------------- mapa
def stitch(z):
    f = 2 ** (-z)                       # fator de reducao
    w, h = -(-W // f), -(-Hh // f)
    nx, ny = -(-w // 256), -(-h // 256)
    canvas = Image.new("RGB", (nx * 256, ny * 256), (14, 28, 40))
    zs = ("N" if z < 0 else "P") + str(abs(z))

    def one(xy):
        x, y = xy
        try:
            b = fetch(TILE.format(mid=MAP_ID, ver=d2["map_version"], x=x, y=y, z=zs))
            return x, y, Image.open(io.BytesIO(b)).convert("RGB")
        except Exception:
            return x, y, None

    with ThreadPoolExecutor(16) as ex:
        for x, y, im in ex.map(one, [(x, y) for y in range(ny) for x in range(nx)]):
            if im is not None:
                canvas.paste(im, (x * 256, y * 256))
    canvas = canvas.crop((0, 0, w, h))
    canvas.save(os.path.join(OUT, f"map_z{z}.jpg"), quality=88)
    print("mapa", z, canvas.size)

for z in meta["zooms"]:
    stitch(z)

# ---------------------------------------------------------- categorias
tree = api(f"label/tree?map_id={MAP_ID}")["tree"]
icons = {}

def node(n):
    if n.get("icon"):
        icons[n["id"]] = n["icon"]
    return {"id": n["id"], "name": n["name"], "children": [node(c) for c in n.get("children", [])]}

cats = [node(n) for n in tree]

def get_icon(item):
    i, url = item
    try:
        im = Image.open(io.BytesIO(fetch(url))).convert("RGBA")
        im.thumbnail((64, 64))
        im.save(os.path.join(OUT, "icons", f"{i}.png"))
    except Exception:
        pass

with ThreadPoolExecutor(16) as ex:
    list(ex.map(get_icon, icons.items()))

# -------------------------------------------------------------- pontos
pl = api(f"point/list?map_id={MAP_ID}")["point_list"]
ox, oy = d2["origin"]
pts = {}
for p in pl:
    pts.setdefault(str(p["label_id"]), []).append(
        [round(p["x_pos"] + ox, 1), round(p["y_pos"] + oy, 1), p.get("z_level", 0)])

# so mantem categorias que tem pontos (ou filhos com pontos)
def prune(n):
    n["children"] = [c for c in (prune(c) for c in n["children"]) if c]
    n["count"] = len(pts.get(str(n["id"]), [])) + sum(c["count"] for c in n["children"])
    return n if n["count"] else None

cats = [c for c in (prune(c) for c in cats) if c]
json.dump(cats, open(os.path.join(OUT, "categories.json"), "w"), ensure_ascii=False)
json.dump(pts, open(os.path.join(OUT, "points.json"), "w"), separators=(",", ":"))
json.dump(meta, open(os.path.join(OUT, "meta.json"), "w"), indent=1)
print("categorias", len(cats), "pontos", len(pl), "icones", len(icons))

# ------------------------------------------------ assinatura visual do mapa
# ORB no mapa em zoom -2 (1/4 do zoom 0). Mesmos parametros usados no app.
import cv2, numpy as np, struct, zipfile
g = cv2.imread(os.path.join(OUT, "map_z-2.jpg"), cv2.IMREAD_GRAYSCALE)
orb = cv2.ORB_create(nfeatures=200000, scaleFactor=1.3, nlevels=3, fastThreshold=8)
kp, des = orb.detectAndCompute(g, None)
xy = np.float32([k.pt for k in kp])
with open(os.path.join(OUT, "features.bin"), "wb") as f:
    f.write(struct.pack("<ii", len(kp), 4))          # quantidade, fator p/ zoom 0
    f.write(xy.astype("<f4").tobytes())
    f.write(des.tobytes())
print("features", len(kp))

# --------------------------------------------- pacote unico para o app
ver = meta["map_version"][:8] + "-" + str(len(pl)) + "-m2"   # m2: inclui mapas p/ o minimapa
meta["pack_version"] = ver
json.dump(meta, open(os.path.join(OUT, "meta.json"), "w"), indent=1)
with zipfile.ZipFile(os.path.join(OUT, "pack.zip"), "w", zipfile.ZIP_DEFLATED) as z:
    for name in ("meta.json", "categories.json", "points.json", "features.bin", "map_z-2.jpg", "map_z-3.jpg"):
        z.write(os.path.join(OUT, name), name)
    for fn in os.listdir(os.path.join(OUT, "icons")):
        z.write(os.path.join(OUT, "icons", fn), "icons/" + fn)
open(os.path.join(OUT, "pack_version.txt"), "w").write(ver)
print("pack", ver, os.path.getsize(os.path.join(OUT, "pack.zip")))
