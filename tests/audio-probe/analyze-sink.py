#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Show whether a 1 kHz tone really reached the sink.

Reads a raw s16le/stereo capture of the output sink monitor and reports, per
0.25 s block, the RMS and the Goertzel power at 1 kHz; then finds the dominant
frequency of the loudest block by a coarse DFT scan. This proves the PCM
reached the device and carries the expected signal, which "PLAYING" or "no EIO"
cannot.

Usage: analyze-sink.py CAPTURE.raw OUTPUT.txt [RATE] [CHANNELS]
"""
import math
import sys

RMS_FLOOR = 200.0          # ~ -44 dBFS; silence is ~0-5
# A full-scale sine puts ~half of its mean-square energy in the Goertzel bin
# (A^2/4 vs A^2/2), so an absolute fraction is a poor discriminator. Compare the
# 1 kHz bin against off-tone reference bins instead: a real tone is orders of
# magnitude above the leakage floor.
DOMINANCE = 10.0


def goertzel(samples, rate, freq):
    n = len(samples)
    if n == 0:
        return 0.0
    k = int(0.5 + n * freq / rate)
    w = 2.0 * math.pi * k / n
    coeff = 2.0 * math.cos(w)
    s0 = s1 = s2 = 0.0
    for x in samples:
        s0 = x + coeff * s1 - s2
        s2 = s1
        s1 = s0
    return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / (n * n)


def rms(samples):
    if not samples:
        return 0.0
    return math.sqrt(sum(x * x for x in samples) / len(samples))


def main():
    path, out_path = sys.argv[1], sys.argv[2]
    rate = int(sys.argv[3]) if len(sys.argv) > 3 else 48000
    channels = int(sys.argv[4]) if len(sys.argv) > 4 else 2
    raw = open(path, 'rb').read()
    total = len(raw) // 2
    data = [int.from_bytes(raw[2 * i:2 * i + 2], 'little', signed=True) for i in range(total)]
    frames = total // channels

    block = rate // 4
    lines = []
    loud_blocks = 0
    tone_blocks = 0
    best = (-1.0, -1, [])
    for bi in range(frames // block):
        left = data[bi * block * channels:(bi + 1) * block * channels:channels]
        r = rms(left)
        p = goertzel(left, rate, 1000.0)
        off = (goertzel(left, rate, 700.0) + goertzel(left, rate, 1500.0)) / 2.0
        frac = (p / off) if off > 0 else 0.0
        if r > RMS_FLOOR:
            loud_blocks += 1
            if frac >= DOMINANCE:
                tone_blocks += 1
        if r > best[0]:
            best = (r, bi, left)
        lines.append("block %3d  t=%6.2fs  rms=%8.1f  p1000=%9.1f  p_off=%9.1f  ratio=%7.1f"
                     % (bi, bi * block / rate, r, p, off, frac))

    peak = "n/a"
    if best[1] >= 0:
        scan = []
        for f in range(100, 4001, 25):
            scan.append((goertzel(best[2], rate, float(f)), f))
        scan.sort()
        peak = "%d Hz (power %.0f)" % (scan[-1][1], scan[-1][0])

    lines.append("")
    lines.append("blocks=%d loud(rms>%.0f)=%d tone1000=%.1f%% peak_unknown if silent"
                 % (frames // block, RMS_FLOOR, loud_blocks,
                    100.0 * tone_blocks / loud_blocks if loud_blocks else 0.0))
    lines.append("peak_frequency_of_loudest_block: %s" % peak)
    peak_hz = int(peak.split()[0]) if peak != "n/a" else -1
    verdict = loud_blocks >= 8 and tone_blocks >= 0.8 * loud_blocks and 950 <= peak_hz <= 1050
    lines.append("SINK_TONE_VERDICT: %s" % ("PASS" if verdict else "FAIL"))

    text = "\n".join(lines) + "\n"
    open(out_path, 'w').write(text)
    sys.stdout.write(text)
    return 0 if verdict else 1


if __name__ == '__main__':
    sys.exit(main())
