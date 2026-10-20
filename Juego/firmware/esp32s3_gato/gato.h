// Reglas del gato (tic-tac-toe) con la máquina cartesiana.
//
// Sin dependencias de Arduino: se compila igual en la ESP32-S3 y en la PC
// (firmware/pruebas/test_gato.cpp), así las reglas se prueban sin hardware.
//
// Flujo de un turno:
//   TURNO        el jugador en turno mueve la cabeza; corre el reloj.
//   RESOLVIENDO  el jugador tiró (lanzó la pelota / soltó la garra) o se
//                acabó el tiempo: los motores quedan bloqueados (ya no se
//                aceptan movimientos), se espera a que la cabeza se detenga y
//                se lee la matriz IR. Un solo sensor activo en una casilla
//                libre = ficha puesta; ninguno, varios o casilla ocupada =
//                turno perdido. Luego pasa el turno al otro jugador.
//   TERMINADO    alguien hizo tres en línea o se llenó el tablero.
#pragma once

#include <stdint.h>

namespace gato {

enum Fase : uint8_t {
  ESPERANDO = 0,     // aún no empieza ninguna partida
  TURNO = 1,
  RESOLVIENDO = 2,
  TERMINADO = 3,
};

enum Resultado : uint8_t {
  NINGUNO = 0,
  PUESTA = 1,        // la cabeza estaba sobre una casilla libre
  FALLO = 2,         // ningún sensor (o varios) vio la cabeza
  OCUPADA = 3,       // le atinó a una casilla que ya tenía ficha
};

constexpr uint8_t VACIA = 0;
constexpr uint8_t JUGADOR_X = 1;
constexpr uint8_t JUGADOR_O = 2;
constexpr uint8_t EMPATE = 3;          // valor de ganador()

constexpr uint8_t SIN_CASILLA = 0xFF;  // ningún sensor activo
constexpr uint8_t VARIAS = 0xFE;       // más de un sensor activo

struct Config {
  uint32_t turnoMs = 15000;      // tiempo para mover la cabeza
  uint32_t quietoMs = 300;       // cuánto debe estar quieta antes de leer el IR
  uint32_t resolverMaxMs = 3000; // si no se detiene, se lee de todos modos
};

/// 0 = nadie, JUGADOR_X / JUGADOR_O = tres en línea, EMPATE = tablero lleno.
inline uint8_t ganador(const uint8_t t[9]) {
  static const uint8_t lineas[8][3] = {
      {0, 1, 2}, {3, 4, 5}, {6, 7, 8},  // filas
      {0, 3, 6}, {1, 4, 7}, {2, 5, 8},  // columnas
      {0, 4, 8}, {2, 4, 6},             // diagonales
  };
  for (const auto &l : lineas) {
    if (t[l[0]] != VACIA && t[l[0]] == t[l[1]] && t[l[1]] == t[l[2]]) return t[l[0]];
  }
  for (int i = 0; i < 9; i++) {
    if (t[i] == VACIA) return VACIA;
  }
  return EMPATE;
}

/// Convierte la máscara de los 9 sensores (bit i = casilla i) en una casilla.
inline uint8_t casillaDeMascara(uint16_t mascara) {
  mascara &= 0x1FF;
  if (mascara == 0) return SIN_CASILLA;
  if (mascara & (mascara - 1)) return VARIAS;
  uint8_t i = 0;
  while (!(mascara & 1)) {
    mascara >>= 1;
    i++;
  }
  return i;
}

class Juego {
 public:
  explicit Juego(Config c = Config()) : cfg(c) {}

  Config cfg;
  Fase fase = ESPERANDO;
  uint8_t turno = JUGADOR_X;
  uint8_t tablero[9] = {};
  uint8_t ganadorPartida = VACIA;
  Resultado ultimo = NINGUNO;
  uint8_t ultimaCasilla = SIN_CASILLA;
  uint8_t ultimoJugador = VACIA;

  /// Partida nueva. Quien empieza se alterna entre partidas.
  void nueva(uint32_t ahora) {
    for (auto &c : tablero) c = VACIA;
    turno = empieza;
    empieza = empieza == JUGADOR_X ? JUGADOR_O : JUGADOR_X;
    ganadorPartida = VACIA;
    ultimo = NINGUNO;
    ultimaCasilla = SIN_CASILLA;
    ultimoJugador = VACIA;
    iniciarTurno(ahora);
  }

  /// Solo el jugador en turno mueve, y solo mientras corre su reloj.
  bool puedeMover(uint8_t jugador) const { return fase == TURNO && jugador == turno; }

  /// El jugador en turno tira antes de que se acabe su reloj.
  bool tirar(uint8_t jugador, uint32_t ahora) {
    if (!puedeMover(jugador)) return false;
    empezarResolver(ahora);
    return true;
  }

  /// Llamar en cada ciclo. [cabezaQuieta]: la cabeza llegó y no se mueve;
  /// [casillaIr]: casilla que ve la matriz IR ahora (o SIN_CASILLA/VARIAS).
  void actualizar(uint32_t ahora, bool cabezaQuieta, uint8_t casillaIr) {
    if (fase == TURNO) {
      if (vencio(ahora, limite)) empezarResolver(ahora);
      return;
    }
    if (fase != RESOLVIENDO) return;

    if (cabezaQuieta) {
      if (!hayQuieto) {
        hayQuieto = true;
        quietoDesde = ahora;
      }
    } else {
      hayQuieto = false;
    }
    const bool asentada = hayQuieto && ahora - quietoDesde >= cfg.quietoMs;
    if (asentada || ahora - inicioResolver >= cfg.resolverMaxMs) resolver(ahora, casillaIr);
  }

  /// Milisegundos que le quedan al turno (0 fuera de TURNO).
  uint32_t restanteMs(uint32_t ahora) const {
    if (fase != TURNO || vencio(ahora, limite)) return 0;
    return limite - ahora;
  }

 private:
  uint8_t empieza = JUGADOR_X;
  uint32_t limite = 0;
  uint32_t inicioResolver = 0;
  uint32_t quietoDesde = 0;
  bool hayQuieto = false;

  // Comparación que sobrevive al desbordamiento de millis() (~49 días).
  static bool vencio(uint32_t ahora, uint32_t t) { return (int32_t)(ahora - t) >= 0; }

  void empezarResolver(uint32_t ahora) {
    fase = RESOLVIENDO;
    inicioResolver = ahora;
    quietoDesde = 0;
    hayQuieto = false;
  }

  void iniciarTurno(uint32_t ahora) {
    fase = TURNO;
    limite = ahora + cfg.turnoMs;
  }

  void resolver(uint32_t ahora, uint8_t casilla) {
    ultimoJugador = turno;
    ultimaCasilla = casilla;
    if (casilla < 9) {
      if (tablero[casilla] == VACIA) {
        tablero[casilla] = turno;
        ultimo = PUESTA;
      } else {
        ultimo = OCUPADA;
      }
    } else {
      ultimo = FALLO;
    }

    ganadorPartida = ganador(tablero);
    if (ganadorPartida != VACIA) {
      fase = TERMINADO;
      return;
    }
    turno = turno == JUGADOR_X ? JUGADOR_O : JUGADOR_X;
    iniciarTurno(ahora);
  }
};

}  // namespace gato
