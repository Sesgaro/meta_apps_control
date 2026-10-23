"""Riggea la pata de gato (modelos/pata_modelo.glb) para el tablero 3D.

Rig de 2 huesos: palma (raíz, centro de la palma = pose de la mano) y dedos
(en el nudillo, se doblan hacia los cojinetes); los vértices cerca del nudillo
se reparten entre los dos para que doble suave. El doblez se hornea en
pata_{x,o}_{0..CUADROS-1}.glb, de abierta (0) a cerrada: la app muestra el
cuadro que toca (no depende de que el visor anime pieles de glTF).

La pata queda en los ejes de la mano (-Z hacia los dedos, -Y del lado de
los cojinetes), en metros, con dos materiales: pelaje y cojinetes.

    python3 tool/pata_a_glb.py     # desde Juego/
"""

import json
import math
import struct
from pathlib import Path

import numpy as np

ENTRADA = Path("modelos/pata_modelo.glb")
SALIDA = Path("android/app/src/quest/assets/models")

LARGO_M = 0.15            # de la muñeca a la punta de los dedos
# En unidades del modelo (Y hacia los dedos, Z hacia los cojinetes, mide 2 de largo):
CENTRO_PALMA_Y = 0.1
NUDILLO_Y = 0.3           # a media altura del cojinete grande
MEZCLA = 0.1              # ± alrededor del nudillo: piel repartida entre los 2 huesos
CURL_RAD = 1.4            # cuánto se doblan los dedos con la mano cerrada
# Pelaje del personaje de cada ficha (igual que tool/cabeza_a_glb.py).
PELAJE = {"x": [0.62, 0.62, 0.66, 1.0], "o": [1.0, 0.62, 0.25, 1.0]}
CUADROS = 4               # igual que PAW_FRAMES en GameActivity3D.kt
PAD = [1.0, 0.55, 0.65, 1.0]


def leer_glb(path: Path):
    b = path.read_bytes()
    n = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + n])
    bin0 = 20 + n + 8
    p = j["meshes"][0]["primitives"][0]

    def acc(i):
        a = j["accessors"][i]
        bv = j["bufferViews"][a["bufferView"]]
        tipo = {5126: np.float32, 5123: np.uint16, 5125: np.uint32}[a["componentType"]]
        k = {"SCALAR": 1, "VEC3": 3}[a["type"]]
        assert bv.get("byteStride", np.dtype(tipo).itemsize * k) == np.dtype(tipo).itemsize * k
        off = bin0 + bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        return np.frombuffer(b, dtype=tipo, count=a["count"] * k, offset=off).reshape(a["count"], k)

    at = p["attributes"]
    return acc(at["POSITION"]).astype(np.float64), acc(at["NORMAL"]).astype(np.float64), acc(p["indices"]).reshape(-1, 3)


def cojinetes(pos: np.ndarray, tri: np.ndarray) -> np.ndarray:
    """Triángulos de los cojinetes: lo que sobresale del frente de la palma y los dedos."""
    c = pos[tri].mean(1)
    # El frente de la pata está a z ~0.22 en esa zona; los cojinetes llegan a 0.26-0.31.
    return (c[:, 1] > 0.2) & (c[:, 2] > 0.245)


def suave(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3 - 2 * t)


def doblar(pos, nor, f):
    """Pose de la pata con los dedos doblados f (0 abierta … 1 cerrada), en los
    ejes de la mano y en metros. Devuelve también el peso del hueso dedos."""
    w = suave((pos[:, 1] - (NUDILLO_Y - MEZCLA)) / (2 * MEZCLA))
    a = CURL_RAD * f          # alrededor de X en el nudillo: los dedos (+Y) van a +Z (cojinetes)
    c, s = math.cos(a), math.sin(a)
    giro = np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    q = pos - [0, NUDILLO_Y, 0]
    p = pos * (1 - w[:, None]) + (q @ giro.T + [0, NUDILLO_Y, 0]) * w[:, None]
    n = nor * (1 - w[:, None]) + (nor @ giro.T) * w[:, None]
    n /= np.linalg.norm(n, axis=1, keepdims=True)
    # Ejes del modelo -> ejes de la mano: x -> -x, y (dedos) -> -z, z (cojinetes) -> -y.
    rot = np.array([[-1, 0, 0], [0, 0, -1], [0, -1, 0]], dtype=np.float64)
    k = LARGO_M / (pos[:, 1].max() - pos[:, 1].min())
    return (((p - [0, CENTRO_PALMA_Y, 0]) * k) @ rot.T).astype(np.float32), (n @ rot.T).astype(np.float32), w


def glb(p, n, tri, pad, pelaje):
    idx_fur = tri[~pad].astype(np.uint16).ravel()
    idx_pad = tri[pad].astype(np.uint16).ravel()
    partes = [p, n, idx_fur, idx_pad]
    datos, vistas = b"", []
    for arr in partes:
        datos += b"\0" * (-len(datos) % 4)
        vistas.append({"buffer": 0, "byteOffset": len(datos), "byteLength": arr.nbytes})
        datos += arr.tobytes()
    datos += b"\0" * (-len(datos) % 4)

    def material(c):
        return {"pbrMetallicRoughness": {"baseColorFactor": c, "metallicFactor": 0.0, "roughnessFactor": 0.8}}

    gltf = {
        "asset": {"version": "2.0", "generator": "pata_a_glb.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0}],
        "meshes": [{"primitives": [
            {"attributes": {"POSITION": 0, "NORMAL": 1}, "indices": 2, "material": 0},
            {"attributes": {"POSITION": 0, "NORMAL": 1}, "indices": 3, "material": 1},
        ]}],
        "materials": [material(pelaje), material(PAD)],
        "buffers": [{"byteLength": len(datos)}],
        "bufferViews": vistas,
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(p), "type": "VEC3",
             "min": p.min(0).tolist(), "max": p.max(0).tolist()},
            {"bufferView": 1, "componentType": 5126, "count": len(p), "type": "VEC3"},
            {"bufferView": 2, "componentType": 5123, "count": len(idx_fur), "type": "SCALAR"},
            {"bufferView": 3, "componentType": 5123, "count": len(idx_pad), "type": "SCALAR"},
        ],
    }
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * (-len(js) % 4)
    total = 12 + 8 + len(js) + 8 + len(datos)
    return (struct.pack("<III", 0x46546C67, 2, total)
            + struct.pack("<II", len(js), 0x4E4F534A) + js
            + struct.pack("<II", len(datos), 0x004E4942) + datos)


if __name__ == "__main__":
    pos, nor, tri = leer_glb(ENTRADA)
    assert len(pos) < 65536, "hacen falta índices de 32 bits"
    pad = cojinetes(pos, tri)
    for f in range(CUADROS):
        p, n, w = doblar(pos, nor, f / (CUADROS - 1))
        if f == 0:
            # Abierta: mide LARGO_M, los dedos van hacia -Z y solo ellos tienen peso.
            assert abs(p[:, 2].max() - p[:, 2].min() - LARGO_M) < 1e-4
            assert w[p[:, 2].argmin()] == 1 and w[p[:, 2].argmax()] == 0
        else:
            # Cerrando: la punta de los dedos baja hacia el lado de los cojinetes (-Y).
            assert p[w == 1][:, 1].mean() < 0
        for ficha, pelaje in PELAJE.items():
            out = glb(p, n, tri, pad, pelaje)
            (SALIDA / f"pata_{ficha}_{f}.glb").write_bytes(out)
    print(f"{ENTRADA} -> {SALIDA}/pata_{{x,o}}_{{0..{CUADROS - 1}}}.glb: {len(tri)} triángulos, {len(out) // 1024} KB c/u")
