#pragma once

// Enlace UART con la ESP32-S3, que hace BLE y lleva el juego. Reemplaza a
// ComunicacionPagina (Wi-Fi/MQTT) con la misma interfaz para el control.
//
// Líneas de texto con checksum, estilo NMEA (XOR de los caracteres entre
// '$' y '*', en hexadecimal):
//   S3 -> esta placa, cada 50 ms:  $R,<referencia mm>,<habilitado 0/1>*HH
//   esta placa -> S3,  cada 50 ms:  $T,<posicion mm>,<referencia mm>,<pwm>*HH
//
// Hacia el control todo sigue en grados del motor (ver gradosDeMm en Config.h).
namespace ComunicacionS3 {

struct Comando {
  float referencia;
  bool habilitado;
};

void iniciar();
Comando leerComando();
void actualizarTelemetria(float referencia, float posicion, float error, float salida);

}  // namespace ComunicacionS3
