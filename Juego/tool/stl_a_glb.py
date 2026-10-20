"""Convierte las fichas STL (PiezaX.stl, PiezaO.stl) a GLB para el tablero 3D.

El Spatial SDK carga glTF, no STL. La ficha queda centrada, apoyada en y = 0,
con Y hacia arriba (el STL viene con Z arriba) y de 1 m de ancho: la escena
la escala al tamaño de la casilla.

    python3 tool/stl_a_glb.py     # desde Juego/
"""

import json
import struct
from pathlib import Path

import numpy as np

SALIDA = Path("android/app/src/quest/assets/models")
FICHAS = {
    "PiezaX.stl": ("pieza_x.glb", [0.74, 0.0, 0.29, 1.0]),   # rosa del tema
    "PiezaO.stl": ("pieza_o.glb", [0.95, 0.85, 0.90, 1.0]),
}


def leer_stl(path: Path) -> np.ndarray:
    """Triángulos (n, 3, 3) de un STL binario."""
    b = path.read_bytes()
    n = struct.unpack("<I", b[80:84])[0]
    assert len(b) == 84 + 50 * n, f"{path} no es un STL binario"
    d = np.frombuffer(b[84:], dtype=np.dtype([("n", "<f4", 3), ("v", "<f4", (3, 3)), ("a", "<u2")]))
    return d["v"].astype(np.float32)


def normalizar(tri: np.ndarray) -> np.ndarray:
    v = tri.reshape(-1, 3)
    v = np.stack([v[:, 0], v[:, 2], -v[:, 1]], axis=1)  # Z arriba -> Y arriba
    lo, hi = v.min(0), v.max(0)
    v = v - [(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2]
    v = v / max(hi[0] - lo[0], hi[2] - lo[2])
    return v.reshape(-1, 3, 3).astype(np.float32)


def glb(tri: np.ndarray, color) -> bytes:
    pos = tri.reshape(-1, 3)
    n = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
    nor = np.repeat(n, 3, axis=0).astype(np.float32)  # caras planas
    cuenta = len(pos)
    # El cargador del Spatial SDK exige índices aunque sean 0, 1, 2, ...
    idx = np.arange(cuenta, dtype=np.uint16 if cuenta < 65536 else np.uint32)
    datos = pos.tobytes() + nor.tobytes() + idx.tobytes()
    gltf = {
        "asset": {"version": "2.0", "generator": "stl_a_glb.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1}, "indices": 2, "material": 0}]}],
        "materials": [{
            "pbrMetallicRoughness": {"baseColorFactor": color, "metallicFactor": 0.0, "roughnessFactor": 0.5},
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
    SALIDA.mkdir(parents=True, exist_ok=True)
    for stl, (nombre, color) in FICHAS.items():
        tri = normalizar(leer_stl(Path(stl)))
        v = tri.reshape(-1, 3)
        assert abs(v[:, [0, 2]].max() - v[:, [0, 2]].min() - 1) < 1e-3 and abs(v[:, 1].min()) < 1e-6
        (SALIDA / nombre).write_bytes(glb(tri, color))
        print(f"{stl} -> {SALIDA / nombre}: {len(tri)} triángulos, alto {v[:, 1].max():.3f}")
