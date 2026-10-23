import 'package:flutter_test/flutter_test.dart';
import 'package:gato_cartesiano/ble/game_protocol.dart';
import 'package:gato_cartesiano/ble/game_sim.dart';

/// Lleva la cabeza a [cell] en el turno actual y deja correr el reloj.
void _play(GameSim sim, int cell) {
  sim.move(
    sim.turn,
    GameSim.geometry.cellX(cell),
    GameSim.geometry.cellY(cell),
  );
  for (var t = 0; t < GameSim.turnMs + GameSim.resolveMaxMs + 200; t += 100) {
    if (sim.phase != Phase.turn && sim.phase != Phase.resolving) break;
    final before = sim.turn;
    sim.tick(100);
    if (sim.turn != before) break;
  }
}

void main() {
  test('la simulación aplica las reglas del gato', () {
    final sim = GameSim()..newGame();
    expect(sim.phase, Phase.turn);
    expect(sim.turn, Player.x);

    _play(sim, 0); // X
    expect(sim.board[0], Player.x);
    expect(sim.lastResult, ShotResult.placed);

    _play(sim, 0); // O a casilla ocupada
    expect(sim.lastResult, ShotResult.occupied);
    expect(sim.turn, Player.x);

    // Entre casillas no ve nada: turno perdido.
    sim.move(Player.x, 60, 60);
    for (var i = 0; i < 300 && sim.turn == Player.x; i++) {
      sim.tick(100);
    }
    expect(sim.lastResult, ShotResult.missed);

    _play(sim, 4); // O
    _play(sim, 1); // X
    _play(sim, 8); // O
    _play(sim, 2); // X: 0 1 2
    expect(sim.phase, Phase.over);
    expect(sim.winner, Player.x.code);
  });

  test('tirar termina el turno en vez de esperar el reloj', () {
    final sim = GameSim()..newGame();
    sim.move(Player.x, 30, 30);
    sim.fire(Player.o); // no es su turno
    expect(sim.phase, Phase.turn);
    sim.fire(Player.x);
    expect(sim.phase, Phase.resolving);
    for (var i = 0; i < 40 && sim.turn == Player.x; i++) {
      sim.tick(100);
    }
    expect(sim.board[0], Player.x);
  });

  test('solo mueve quien tiene el turno', () {
    final sim = GameSim()..newGame();
    sim.move(Player.o, 100, 100);
    expect(sim.refX, 0);
  });

  test('calibración: la cabeza va a los dos puntos y termina', () {
    final sim = GameSim();
    sim.calibrate(Player.x);
    expect(sim.state.calibStep, 1);
    expect((sim.refX, sim.refY), (30.0, 30.0));
    sim.touch(Player.x, 2); // no es el punto actual
    expect(sim.calibStep, 1);
    sim.touch(Player.x, 1);
    expect((sim.calibStep, sim.refX, sim.refY), (2, 150.0, 150.0));
    sim.touch(Player.x, 2);
    expect(sim.calibStep, 0);
    // Durante una partida no se puede calibrar.
    sim.newGame();
    sim.calibrate(Player.x);
    expect(sim.calibStep, 0);
  });
}
