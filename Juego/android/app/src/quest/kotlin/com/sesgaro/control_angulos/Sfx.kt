package com.sesgaro.control_angulos

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/// Efectos de sonido del tablero 3D, sintetizados al arrancar (sin archivos):
/// cada uno es un AudioTrack estático que se vuelve a tocar desde el inicio.
class Sfx {
    enum class Sound { LAUNCH, HOLE, BOUNCE, CLAW, TICK, TURN, WIN, DRAW }

    private val tracks = Sound.entries.associateWith { track(synth(it)) }

    fun play(s: Sound) {
        val t = tracks[s] ?: return
        try {
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: IllegalStateException) {
            // El audio puede no estar listo (p. ej. al cerrar): se omite el sonido.
        }
    }

    fun release() = tracks.values.forEach { it.release() }

    private fun track(pcm: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    private fun synth(s: Sound): ShortArray = when (s) {
        // Zumbido de la liga: ruido que se apaga con un tono que baja.
        Sound.LAUNCH -> render(0.35f) { t, _ -> (noise() * 0.4f + tone(900f - 1500f * t, t) * 0.4f) * decay(t, 6f) }
        // "Plop": tono que cae rápido, como algo que se cuela.
        Sound.HOLE -> render(0.4f) { t, _ -> tone(700f * exp(-5f * t), t) * decay(t, 5f) }
        // Golpe seco: tono grave muy corto con algo de ruido.
        Sound.BOUNCE -> render(0.18f) { t, _ -> (tone(140f, t) + noise() * 0.5f) * decay(t, 25f) }
        // Motor de la garra.
        Sound.CLAW -> render(0.7f) { t, _ -> square(110f + 20f * sin(2 * PI.toFloat() * 6f * t), t) * 0.35f * env(t, 0.7f) }
        Sound.TICK -> render(0.05f) { t, _ -> tone(1800f, t) * decay(t, 60f) }
        // Dos notas: te toca.
        Sound.TURN -> render(0.3f) { t, _ -> tone(if (t < 0.15f) 660f else 880f, t) * env(t, 0.3f) * 0.7f }
        // Arpegio do-mi-sol-do.
        Sound.WIN -> render(0.9f) { t, _ ->
            val f = listOf(523f, 659f, 784f, 1047f)[min((t / 0.18f).toInt(), 3)]
            tone(f, t) * env(t, 0.9f) * 0.7f
        }
        Sound.DRAW -> render(0.5f) { t, _ -> tone(if (t < 0.25f) 440f else 392f, t) * env(t, 0.5f) * 0.6f }
    }

    private fun render(seconds: Float, f: (Float, Int) -> Float): ShortArray {
        val n = (seconds * RATE).toInt()
        return ShortArray(n) { i -> (f(i.toFloat() / RATE, i).coerceIn(-1f, 1f) * 0.6f * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun tone(hz: Float, t: Float) = sin(2 * PI.toFloat() * hz * t)
    private fun square(hz: Float, t: Float) = if (tone(hz, t) >= 0) 1f else -1f
    private fun noise() = Random.nextFloat() * 2 - 1
    private fun decay(t: Float, k: Float) = exp(-k * t)

    /// Sube en 10 ms y baja en los últimos 50 ms: sin chasquidos.
    private fun env(t: Float, len: Float) = min(min(t / 0.01f, 1f), min((len - t) / 0.05f, 1f)).coerceAtLeast(0f)

    private companion object {
        const val RATE = 22050
    }
}
