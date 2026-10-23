"""Fichas y resortera del tablero 3D, desde los GLB de Blender en modelos/.

Cada GLB de Blender trae la escena completa (cruz, anillo, resortera…): de cada
uno se toman solo los objetos que le tocan, con su transformación horneada.

  modelos/cross2.glb     nodo Cube                  -> pieza_x.glb
  modelos/circle2.glb    nodo Cylinder.005          -> pieza_o.glb
  modelos/slingshot3.glb nodos Cylinder.002/.003/.004 -> slingshot.glb

Fichas: centradas, apoyadas en y = 0 y de 1 m de ancho (la escena las escala
a la casilla). Resortera: en unidades del modelo, apoyada en y = 0 y con el
centro de las puntas de la horquilla en x = z = 0; imprime las puntas para
SLING_TIP_L/R en GameActivity3D.kt.

    python3 tool/modelos_a_glb.py     # desde Juego/
"""

import json
import struct
from pathlib import Path

import numpy as np

SALIDA = Path("android/app/src/quest/assets/models")
PIEZAS = {
    "pieza_x.glb": ("modelos/cross2.glb", ["Cube"], [0.74, 0.0, 0.29, 1.0]),        # rosa del tema
    "pieza_o.glb": ("modelos/circle2.glb", ["Cylinder.005"], [0.95, 0.85, 0.90, 1.0]),
}
RESORTERA = ("modelos/slingshot3.glb", ["Cylinder.002", "Cylinder.003", "Cylinder.004"], [0.55, 0.36, 0.20, 1.0])
BRAZOS = ["Cylinder.003", "Cylinder.004"]   # izquierdo, derecho: sus puntas son la horquilla


def rot(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def leer(path: str, nombres: list[str]) -> dict[str, tuple[np.ndarray, np.ndarray]]:
    """Triángulos (n, 3, 3) y normales por vértice (n, 3, 3) de cada nodo pedido, en coordenadas de la escena."""
    b = Path(path).read_bytes()
    n = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + n])
    bin0 = 20 + n + 8

    def acc(i):
        a = j["accessors"][i]
        bv = j["bufferViews"][a["bufferView"]]
        tipo = {5126: np.float32, 5123: np.uint16, 5125: np.uint32}[a["componentType"]]
        k = {"SCALAR": 1, "VEC3": 3}[a["type"]]
        assert "byteStride" not in bv or bv["byteStride"] == np.dtype(tipo).itemsize * k
        off = bin0 + bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        return np.frombuffer(b, dtype=tipo, count=a["count"] * k, offset=off).reshape(a["count"], k)

    out = {}
    for nodo in j["nodes"]:
        if nodo.get("name") not in nombres:
            continue
        assert "children" not in nodo and "matrix" not in nodo, f"{nodo['name']}: nodo anidado, no soportado"
        r = rot(nodo.get("rotation", [0, 0, 0, 1]))
        s = np.array(nodo.get("scale", [1, 1, 1]))
        t = np.array(nodo.get("translation", [0, 0, 0]))
        tris, nors = [], []
        for p in j["meshes"][nodo["mesh"]]["primitives"]:
            idx = acc(p["indices"]).ravel()
            v = acc(p["attributes"]["POSITION"]).astype(np.float64)[idx]
            nv = acc(p["attributes"]["NORMAL"]).astype(np.float64)[idx]
            tris.append((v * s) @ r.T + t)
            nn = (nv / s) @ r.T    # normales con escala no uniforme: inversa transpuesta
            nors.append(nn / np.linalg.norm(nn, axis=1, keepdims=True))
        out[nodo["name"]] = (np.concatenate(tris).reshape(-1, 3, 3), np.concatenate(nors).reshape(-1, 3, 3))
    faltan = set(nombres) - set(out)
    assert not faltan, f"{path}: no encontré {faltan}"
    return out


def glb(tri: np.ndarray, nor: np.ndarray, color) -> bytes:
    pos = tri.reshape(-1, 3).astype(np.float32)
    nrm = nor.reshape(-1, 3).astype(np.float32)
    cuenta = len(pos)
    # El cargador del Spatial SDK exige índices aunque sean 0, 1, 2, ...
    idx = np.arange(cuenta, dtype=np.uint16 if cuenta < 65536 else np.uint32)
    datos = pos.tobytes() + nrm.tobytes() + idx.tobytes()
    gltf = {
        "asset": {"version": "2.0", "generator": "modelos_a_glb.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1}, "indices": 2, "material": 0}]}],
        "materials": [{
            "pbrMetallicRoughness": {"baseColorFactor": color, "metallicFactor": 0.0, "roughnessFactor": 0.6},
            "doubleSided": True,
        }],
        "buffers": [{"byteLength": len(datos) + (-len(datos) % 4)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": cuenta * 12, "target": 34962},
            {"buffer": 0, "byteOffset": cuenta * 12, "byteLength": cuenta * 12, "target": 34962},
            {"buffer": 0, "byteOffset": cuenta * 24, "byteLength": idx.nbytes, "target": 34963},
        ],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": cuenta, "type": "VEC3",
             "min": pos.min(0).tolist(), "max": pos.max(0).tolist()},
            {"bufferView": 1, "componentType": 5126, "count": cuenta, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5123 if idx.dtype == np.uint16 else 5125,
             "count": cuenta, "type": "SCALAR"},
        ],
    }
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * (-len(js) % 4)
    datos += b"\0" * (-len(datos) % 4)
    total = 12 + 8 + len(js) + 8 + len(datos)
    return (struct.pack("<III", 0x46546C67, 2, total)
            + struct.pack("<II", len(js), 0x4E4F534A) + js
            + struct.pack("<II", len(datos), 0x004E4942) + datos)


if __name__ == "__main__":
    for nombre, (origen, nodos, color) in PIEZAS.items():
        partes = leer(origen, nodos)
        tri = np.concatenate([t for t, _ in partes.values()])
        nor = np.concatenate([n for _, n in partes.values()])
        v = tri.reshape(-1, 3)
        lo, hi = v.min(0), v.max(0)
        tri = (tri - [(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2]) / max(hi[0] - lo[0], hi[2] - lo[2])
        v = tri.reshape(-1, 3)
        assert abs(max(np.ptp(v[:, 0]), np.ptp(v[:, 2])) - 1) < 1e-6 and abs(v[:, 1].min()) < 1e-9
        (SALIDA / nombre).write_bytes(glb(tri, nor, color))
        print(f"{origen} {nodos} -> {SALIDA / nombre}: {len(tri)} triángulos, alto {v[:, 1].max():.3f}")

    origen, nodos, color = RESORTERA
    partes = leer(origen, nodos)
    # Punta de cada brazo: su vértice más alto.
    puntas = [partes[b][0].reshape(-1, 3)[partes[b][0].reshape(-1, 3)[:, 1].argmax()] for b in BRAZOS]
    tri = np.concatenate([t for t, _ in partes.values()])
    nor = np.concatenate([n for _, n in partes.values()])
    medio = (puntas[0] + puntas[1]) / 2
    corr = np.array([medio[0], tri.reshape(-1, 3)[:, 1].min(), medio[2]])
    tri = tri - corr
    puntas = [p - corr for p in puntas]
    izq, der = sorted(puntas, key=lambda p: p[0])
    assert abs(izq[0] + der[0]) < 1e-6 and der[0] > 0
    (SALIDA / "slingshot.glb").write_bytes(glb(tri, nor, color))
    print(f"{origen} {nodos} -> {SALIDA / 'slingshot.glb'}: {len(tri)} triángulos, alto {tri.reshape(-1, 3)[:, 1].max():.2f}")
    print(f"  SLING_TIP_L = Vector3({izq[0]:.2f}f, {izq[1]:.2f}f, {izq[2]:.2f}f)")
    print(f"  SLING_TIP_R = Vector3({der[0]:.2f}f, {der[1]:.2f}f, {der[2]:.2f}f)")
