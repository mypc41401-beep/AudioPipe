#!/usr/bin/env python3
"""Optional offline check of GENERATED CI tones, not a hardware/audio-quality test.

Requires ffmpeg and NumPy on the analysis host; neither is an APK dependency.
Usage: python analyze_sbc.py received.sbc > result.json
The virtual peer generates 660 Hz; the other original app generates 440 Hz.
The recording also includes initial/default-output and intentional Stop/Repair phases.
Find a continuous non-silent interval dominated by the selected tone, then require
at least 120 seconds. Raw SBC has no arrival timestamps: no latency/stutter claim.
"""
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

import numpy as np


def analyze(path):
    with tempfile.TemporaryDirectory(prefix="audiopipe-sbc-") as work:
        pcm = pathlib.Path(work) / "decoded.pcm"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "sbc", "-i", str(path),
                        "-f", "s16le", "-ac", "1", "-ar", "48000", str(pcm)], check=True)
        samples = np.fromfile(pcm, dtype="<i2").astype(np.float64)
    window = np.hanning(48000)
    rows = []
    for second in range(len(samples) // 48000):
        block = samples[second * 48000:(second + 1) * 48000]
        spectrum = np.abs(np.fft.rfft(block * window))
        other = np.linalg.norm(spectrum[438:443])
        selected = np.linalg.norm(spectrum[658:663])
        rows.append((float(np.sqrt(np.mean(block * block))),
                     float(20 * np.log10((other + 1e-9) / (selected + 1e-9)))))
    best, start = (0, 0), None
    for index, (rms, ratio_db) in enumerate(rows + [(0, 0)]):
        if rms >= 5 and ratio_db < -35:
            if start is None:
                start = index
        elif start is not None:
            if index - start > best[1] - best[0]:
                best = (start, index)
            start = None
    segment = rows[best[0]:best[1]]
    return {
        "sbcSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "decodedSeconds": len(samples) / 48000,
        "method": "1-second Hann FFT; L2 amplitude of bins 438..442 versus 658..662 Hz; RMS >=5 PCM units and other/selected <-35 dB; longest consecutive interval",
        "selectedFrequencyHz": 660, "otherFrequencyHz": 440,
        "intervalSeconds": list(best), "matchingSeconds": len(segment),
        "rmsRange": [min(r[0] for r in segment), max(r[0] for r in segment)] if segment else [],
        "worstOtherVsSelectedDb": max((r[1] for r in segment), default=None),
        "atLeast120Seconds": len(segment) >= 120,
        "limits": "Generated tones at a software A2DP sink. No speaker waveform or physical Redmi/X-906/BitChord test. Raw SBC lacks arrival timing; this cannot establish absence of stutter, latency, absolute silence or acoustic isolation."
    }


if __name__ == "__main__":
    result = analyze(pathlib.Path(sys.argv[1]))
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["atLeast120Seconds"] else 1)
