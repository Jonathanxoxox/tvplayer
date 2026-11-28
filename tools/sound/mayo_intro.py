#!/usr/bin/env python3
"""Synthesises Mayo TV's sound logo (app/src/main/res/raw/mayo_intro.ogg) from scratch.

Everything is generated here from oscillators and seeded noise: no samples, no third-party sounds.
The result is deterministic, so running the script again reproduces the file.

    python3 tools/sound/mayo_intro.py            # writes app/src/main/res/raw/mayo_intro.ogg
    python3 tools/sound/mayo_intro.py --wav x.wav  # also keeps the uncompressed mix

Needs numpy and ffmpeg (with libvorbis). Ogg Vorbis plays on every Android version the app supports.

The timeline matches the intro animation (features/onboarding/intro/IntroTimeline.kt); both count
from the moment playback starts:

    0.00 s   a low suspended chord starts to swell
    0.40 s   floodlight 1 (near left) switches on: relay clunk, then ballast hum
    0.70 s   floodlight 2 (near right)
    1.00 s   floodlight 3 (far left)
    1.20 s   floodlight 4 (far right)
    1.35 s   an airy rise while the beams sweep towards the centre
    2.40 s   the beams meet: the chord resolves (sus4 -> major), soft bloom
    2.45 s   motif note 1 (A4), 2.75 s motif note 2 (D5), a rising fourth
    ~4.0 s   the tail has died away
"""

import argparse
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SR = 44100
LENGTH_S = 4.0
SEED = 20261125

# Keep in step with IntroTimeline.kt.
LAMPS = [(0.40, -0.70), (0.70, 0.70), (1.00, -0.40), (1.20, 0.40)]  # (switch-on time, pan)
SWEEP_START = 1.35
REVEAL = 2.40
NOTE_1 = 2.45
NOTE_2 = 2.75

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "res", "raw", "mayo_intro.ogg")

n = int(SR * LENGTH_S)
t = np.arange(n) / SR
rng = np.random.default_rng(SEED)


def smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3 - 2 * x)


def pan_gains(pan):
    """Equal-power pan, -1 = left, +1 = right."""
    a = (pan + 1) * np.pi / 4
    return np.cos(a), np.sin(a)


def additive(freq, brightness, harmonics=12, detune_cents=0.0, phase=0.0):
    """A band-limited saw-like tone; [brightness] (array or scalar) sets how far the harmonics reach."""
    f = freq * 2 ** (detune_cents / 1200)
    out = np.zeros(n)
    for k in range(1, harmonics + 1):
        if f * k > SR / 2 - 500:
            break
        weight = (1.0 / k) * np.exp(-(k - 1) / np.maximum(brightness, 1e-3))
        out += weight * np.sin(2 * np.pi * f * k * t + phase * k)
    return out


def pad():
    """D sus4 swelling to D major at the reveal; brightness opens up as it rises."""
    rise = smoothstep((t - 0.0) / REVEAL) ** 1.6
    after = np.clip((t - REVEAL) / (LENGTH_S - REVEAL), 0, 1)
    env = np.where(t < REVEAL, 0.20 + 0.80 * rise, np.exp(-after * 3.2))
    env *= smoothstep(t / 0.25)  # no click at the very start
    env *= 1 - smoothstep((t - (LENGTH_S - 0.35)) / 0.35)
    bright = np.where(t < REVEAL, 0.8 + 4.2 * rise, 5.0 - 2.5 * after)
    resolve = smoothstep((t - REVEAL) / 0.06)

    def voice(freq, gain):
        v = 0.0
        for cents, ph in ((-6, 0.0), (0, 1.3), (7, 2.1)):
            v = v + additive(freq, bright, detune_cents=cents, phase=ph)
        return gain * v / 3

    body = voice(73.42, 0.9) + voice(110.0, 0.7) + voice(146.83, 0.6) + voice(220.0, 0.45)
    sus = voice(196.0, 0.40)    # G3, the suspended fourth
    third = voice(185.0, 0.40)  # F#3, where it resolves
    return env * (body + (1 - resolve) * sus + resolve * third)


def clunk(at, pitch=1.0):
    """A floodlight relay closing: a low thump, a short click and a little metallic ring."""
    s = np.clip(t - at, 0, None)
    on = (t >= at).astype(float)
    f = 45 * pitch + 50 * pitch * np.exp(-s / 0.025)
    phase = 2 * np.pi * np.cumsum(f) / SR
    thump = np.sin(phase) * np.exp(-s / 0.075) * 0.32
    noise = rng.standard_normal(n)
    click = np.diff(noise, prepend=0.0) * np.exp(-s / 0.004) * 0.06
    ring = sum(a * np.sin(2 * np.pi * fr * pitch * s) for fr, a in ((820, 0.05), (1310, 0.035), (2150, 0.02)))
    ring = ring * np.exp(-s / 0.055)
    attack = smoothstep(s / 0.002)
    return on * attack * (thump + click + ring)


def hum(at):
    """The ballast's mains hum after a lamp strikes, fading out once the logo is revealed."""
    s = np.clip(t - at, 0, None)
    env = smoothstep(s / 0.30) * (1 - smoothstep((t - REVEAL) / 0.5)) * (t >= at)
    tone = 0.6 * np.sin(2 * np.pi * 100 * t) + 0.3 * np.sin(2 * np.pi * 200 * t) + 0.12 * np.sin(2 * np.pi * 300 * t)
    return env * tone * 0.018


def whoosh():
    """Airy noise through a resonant band-pass whose centre rises while the beams sweep in."""
    noise = rng.standard_normal(n)
    start, end = int(SWEEP_START * SR), int((REVEAL + 0.6) * SR)
    out = np.zeros(n)
    low = band = 0.0
    q = 0.55
    for i in range(start, end):
        x = (t[i] - SWEEP_START) / (REVEAL - SWEEP_START)
        fc = 300 + 2700 * min(max(x, 0.0), 1.0) ** 2
        fq = 2 * np.sin(np.pi * fc / SR)
        low += fq * band
        high = noise[i] - low - q * band
        band += fq * high
        out[i] = band
    env = smoothstep((t - SWEEP_START) / (REVEAL - SWEEP_START)) ** 2
    env *= np.where(t < REVEAL, 1.0, np.exp(-(t - REVEAL) / 0.12))
    return out * env * 0.05


def bell(at, freq):
    """A warm bell: slightly inharmonic partials, the higher ones dying away faster."""
    s = np.clip(t - at, 0, None)
    on = (t >= at).astype(float)
    partials = ((1.0, 1.0, 1.4), (2.0, 0.45, 0.8), (3.0, 0.18, 0.5), (4.16, 0.10, 0.3), (5.43, 0.05, 0.2))
    tone = sum(a * np.sin(2 * np.pi * freq * r * s) * np.exp(-s / d) for r, a, d in partials)
    tone += 0.35 * np.sin(2 * np.pi * freq * 0.5 * s) * np.exp(-s / 1.0)
    return on * smoothstep(s / 0.004) * tone


def shimmer():
    """A faint high glitter right at the reveal, the light catching the logo."""
    s = np.clip(t - REVEAL, 0, None)
    env = (t >= REVEAL) * smoothstep(s / 0.08) * np.exp(-s / 0.6)
    trem = 0.75 + 0.25 * np.sin(2 * np.pi * 9 * s)
    return env * trem * (np.sin(2 * np.pi * 1760 * t) + 0.6 * np.sin(2 * np.pi * 2349.3 * t)) * 0.012


def mix():
    left = np.zeros(n)
    right = np.zeros(n)

    def add(sig, pan=0.0):
        nonlocal left, right
        gl, gr = pan_gains(pan)
        left = left + gl * sig
        right = right + gr * sig

    add(pad() * 0.16)
    for i, (at, pan) in enumerate(LAMPS):
        add(clunk(at, pitch=1.0 - 0.06 * i), pan)
        add(hum(at), pan)
    add(whoosh())
    add(bell(NOTE_1, 440.0) * 0.20, -0.15)
    add(bell(NOTE_2, 587.33) * 0.22, 0.15)
    add(shimmer())

    stereo = np.stack([left, right], axis=1)
    # Fade the last 50 ms to true silence and normalise to -1 dBFS peak.
    tail = int(0.05 * SR)
    stereo[-tail:] *= np.linspace(1, 0, tail)[:, None]
    peak = np.max(np.abs(stereo))
    stereo *= 10 ** (-1 / 20) / peak
    return stereo


def write_wav(path, stereo):
    pcm = (np.clip(stereo, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default=DEFAULT_OUT, help="the .ogg to write")
    ap.add_argument("--wav", help="also keep the uncompressed mix here")
    ap.add_argument("--quality", default="4", help="libvorbis -q:a (default 4, about 128 kbit/s stereo)")
    args = ap.parse_args()

    stereo = mix()
    with tempfile.TemporaryDirectory() as tmp:
        wav = args.wav or os.path.join(tmp, "mayo_intro.wav")
        write_wav(wav, stereo)
        subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav,
                "-c:a", "libvorbis", "-q:a", args.quality,
                "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
                args.out,
            ],
            check=True,
        )
    size = os.path.getsize(args.out)
    print(f"{args.out}: {size / 1024:.1f} KB, {LENGTH_S:.1f} s")
    if size > 150 * 1024:
        sys.exit("the sound logo must stay under 150 KB")


if __name__ == "__main__":
    main()
