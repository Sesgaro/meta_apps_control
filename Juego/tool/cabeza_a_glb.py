"""Cabeza del personaje gato (modelos/cabeza_gato.glb) y sus caras (modelos/sprites.png).

Salen, en android/app/src/quest/assets/models:
  cabeza_x.glb, cabeza_o.glb   la cabeza gris (ficha X) y naranja (ficha O)
  cara_1.glb … cara_4.glb      1 normal, 2 ficha perdida, 3 perdió, 4 acertó/ganó

Todo de 1 m de diámetro, Y arriba y la cara hacia -Z (la escena lo escala y
lo gira hacia donde mira el jugador). Cada cara es un parche curvo pegado al
frente de la cabeza con el sprite como textura (transparente donde no hay trazo).

    python3 tool/cabeza_a_glb.py     # desde Juego/
"""

import io
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image

CABEZA = Path("modelos/cabeza_gato.glb")
SPRITES = Path("modelos/sprites.png")
SALIDA = Path("android/app/src/quest/assets/models")

PELAJE = {"cabeza_x.glb": [0.62, 0.62, 0.66, 1.0], "cabeza_o.glb": [1.0, 0.62, 0.25, 1.0]}
# Recorte de cada cara en sprites.png (x, y, ancho, alto): mismo tamaño y los
# bigotes alineados abajo, para que no brinque al cambiar de expresión.
CARAS = [(221, 140, 560, 378), (795, 140, 560, 378), (233, 685, 560, 378), (809, 685, 560, 378)]
CARA_ANCHO = 1.6          # en radios de la cabeza (los bigotes llegan a los cachetes)
CARA_CENTRO_Y = -0.03
SEPARACION = 0.012        # sobre la piel, para que no parpadee


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
        k = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}[a["type"]]
        off = bin0 + bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        return np.frombuffer(b, dtype=tipo, count=a["count"] * k, offset=off).reshape(a["count"], k)

    at = p["attributes"]
    return acc(at["POSITION"]).astype(np.float64), acc(at["NORMAL"]).astype(np.float64), acc(p["indices"]).ravel()


def empacar(gltf: dict, partes: list) -> bytes:
    """GLB con las partes (arrays o bytes) en un solo buffer, una vista cada una."""
    datos, vistas = b"", []
    for x in partes:
        x = x if isinstance(x, bytes) else x.tobytes()
        datos += b"\0" * (-len(datos) % 4)
        vistas.append({"buffer": 0, "byteOffset": len(datos), "byteLength": len(x)})
        datos += x
    datos += b"\0" * (-len(datos) % 4)
    gltf.update({"asset": {"version": "2.0", "generator": "cabeza_a_glb.py"}, "scene": 0,
                 "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                 "buffers": [{"byteLength": len(datos)}], "bufferViews": vistas})
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * (-len(js) % 4)
    total = 12 + 8 + len(js) + 8 + len(datos)
    return (struct.pack("<III", 0x46546C67, 2, total)
            + struct.pack("<II", len(js), 0x4E4F534A) + js
            + struct.pack("<II", len(datos), 0x004E4942) + datos)


def cabeza(pos, nor, idx, color) -> bytes:
    gltf = {
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1}, "indices": 2, "material": 0}]}],
        "materials": [{"pbrMetallicRoughness": {"baseColorFactor": color, "metallicFactor": 0.0, "roughnessFactor": 0.8}}],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(pos), "type": "VEC3",
             "min": pos.min(0).tolist(), "max": pos.max(0).tolist()},
            {"bufferView": 1, "componentType": 5126, "count": len(pos), "type": "VEC3"},
            {"bufferView": 2, "componentType": 5125, "count": len(idx), "type": "SCALAR"},
        ],
    }
    return empacar(gltf, [pos, nor, idx])


def parche(pos, ancho, alto, cols=48, filas=32):
    """Malla curva sobre el frente (-Z) de la cabeza: cada vértice va a la piel
    que tiene enfrente (el punto de la cabeza más cercano en x, y) + SEPARACION."""
    frente = pos[pos[:, 2] < 0]
    u, v = np.meshgrid(np.linspace(0, 1, cols), np.linspace(0, 1, filas))
    x = (0.5 - u) * ancho   # visto de frente (desde -Z), la derecha es -X
    y = CARA_CENTRO_Y * 0.5 + (0.5 - v) * alto
    xy = np.stack([x.ravel(), y.ravel()], 1)
    z = np.empty(len(xy))
    for i in range(0, len(xy), 256):
        d = ((xy[i:i + 256, None, :] - frente[None, :, :2]) ** 2).sum(-1)
        z[i:i + 256] = frente[d.argmin(1), 2] - SEPARACION
    p = np.column_stack([xy, z]).astype(np.float32)
    uv = np.column_stack([u.ravel(), v.ravel()]).astype(np.float32)
    q = np.arange(cols * filas).reshape(filas, cols)[:-1, :-1].ravel()
    # Triángulos en sentido antihorario vistos desde -Z (de frente).
    tri = np.column_stack([q, q + cols, q + 1, q + 1, q + cols, q + cols + 1]).reshape(-1, 3)
    return p, uv, tri.astype(np.uint16).ravel()


def cara(pos, sprite: Image.Image) -> bytes:
    ancho = CARA_ANCHO * 0.5
    alto = ancho * sprite.height / sprite.width
    p, uv, idx = parche(pos, ancho, alto)
    n = np.tile(np.array([0, 0, -1], np.float32), (len(p), 1))
    png = io.BytesIO()
    sprite.save(png, "PNG", optimize=True)
    gltf = {
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "indices": 3, "material": 0}]}],
        "materials": [{
            "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0.0, "roughnessFactor": 0.9},
            "alphaMode": "MASK", "alphaCutoff": 0.5,
        }],
        "textures": [{"source": 0, "sampler": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}],
        "images": [{"bufferView": 4, "mimeType": "image/png"}],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(p), "type": "VEC3",
             "min": p.min(0).tolist(), "max": p.max(0).tolist()},
            {"bufferView": 1, "componentType": 5126, "count": len(p), "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": len(p), "type": "VEC2"},
            {"bufferView": 3, "componentType": 5123, "count": len(idx), "type": "SCALAR"},
        ],
    }
    return empacar(gltf, [p, n, uv, idx, png.getvalue()])


if __name__ == "__main__":
    pos, nor, idx = leer_glb(CABEZA)
    # La cara del modelo mira a +X (las orejas están en ±Z): se gira para que mire a -Z.
    giro = np.array([[0, 0, 1], [0, 1, 0], [-1, 0, 0]], dtype=np.float64)
    pos = pos @ giro.T * 0.5 / np.abs(pos[:, [0, 2]]).max()   # 1 m de diámetro
    nor = nor @ giro.T
    assert abs(pos[:, 2].min() + 0.5) < 0.01, "la cara debería quedar en z = -0.5"
    pos32, nor32 = pos.astype(np.float32), nor.astype(np.float32)
    for nombre, color in PELAJE.items():
        out = cabeza(pos32, nor32, idx.astype(np.uint32), color)
        (SALIDA / nombre).write_bytes(out)
        print(f"{CABEZA} -> {SALIDA / nombre}: {len(idx) // 3} triángulos, {len(out) // 1024} KB")
    hoja = Image.open(SPRITES).convert("RGBA")
    for i, (x, y, w, h) in enumerate(CARAS, 1):
        sprite = hoja.crop((x, y, x + w, y + h)).resize((512, round(512 * h / w)), Image.LANCZOS)
        assert np.array(sprite)[:, :, 3].max() > 0, f"la cara {i} salió vacía"
        out = cara(pos, sprite)
        (SALIDA / f"cara_{i}.glb").write_bytes(out)
        print(f"{SPRITES} [{x},{y} {w}×{h}] -> {SALIDA / f'cara_{i}.glb'}: {len(out) // 1024} KB")
