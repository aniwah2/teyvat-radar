"""Explora a API do mapa interativo da HoYoLAB e salva as respostas em out/.
Roda no GitHub Actions (esta sessao de desenvolvimento nao alcanca a HoYoLAB)."""
import json, os, re, sys, urllib.parse, urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
os.makedirs(OUT, exist_ok=True)
H = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
     "Referer": "https://act.hoyolab.com/", "Origin": "https://act.hoyolab.com"}
BASE = "https://sg-public-api-static.hoyolab.com/common/map_user/ys_obc"
Q = "app_sn=ys_obc&lang=pt-pt"

def get(url, binary=False):
    req = urllib.request.Request(url, headers=H)
    with urllib.request.urlopen(req, timeout=60) as r:
        b = r.read()
    return b if binary else b.decode("utf-8", "replace")

def save(name, text):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(text)

log = []
def try_get(name, url):
    try:
        t = get(url)
        save(name, t)
        log.append(f"OK   {name} {len(t)} {url}")
        return t
    except Exception as e:
        log.append(f"FAIL {name} {e} {url}")

try_get("map_tree.json", f"{BASE}/v1/map/tree?{Q}")
try_get("map_info_2.json", f"{BASE}/v1/map/info?map_id=2&{Q}")
try_get("label_tree_2.json", f"{BASE}/v1/map/label/tree?map_id=2&{Q}")
for v in ("v1", "v2", "v3"):
    try_get(f"point_list_{v}_2.json", f"{BASE}/{v}/map/point/list?map_id=2&{Q}")
try_get("game_list.json", f"{BASE}/v1/map/game_list?{Q}")

# Bundles JS do site, para achar o padrao de URL dos tiles do detail_v2
html = try_get("index.html", "https://act.hoyolab.com/ys/app/interactive-map/index.html")
hits = []
if html:
    js = sorted(set(re.findall(r'(?:src|href)="([^"]+\.js)"', html)))
    for i, src in enumerate(js):
        url = urllib.parse.urljoin("https://act.hoyolab.com/ys/app/interactive-map/index.html", src)
        if "interactive-map" not in url:
            continue
        t = try_get(f"bundle_{i}.js", url)
        if not t:
            continue
        for kw in ("detail_v2", "map_version", "min_zoom", "original_map_size", "tiles", ".webp", "map_manage/"):
            for m in re.finditer(re.escape(kw), t):
                hits.append(f"[{i}:{kw}] " + t[max(0, m.start()-200):m.end()+300].replace("\n", " "))
                if sum(1 for h in hits if h.startswith(f"[{i}:{kw}]")) > 6:
                    break
save("js_hits.txt", "\n\n".join(hits))
save("log.txt", "\n".join(log))
print("\n".join(log))
