# Üçüncü taraf bileşenler

## Basic Pitch — Spotify AB (Apache License 2.0)

Ses kaydından nota çıkarma, Spotify'ın açık kaynak **Basic Pitch** modelini kullanır.

- Kaynak: https://github.com/spotify/basic-pitch
- Model dosyası: `app/src/main/assets/basic-pitch/nmp.onnx` (özgün `basic_pitch/saved_models/icassp_2022/nmp.onnx`, değiştirilmeden; SHA-256 `2c3c1d144bfa61ad236e92e169c13535c880469a12a047d4e73451f2c059a0ec`)
- Lisans ve NOTICE: `app/src/main/assets/basic-pitch/LICENSE.txt`, `NOTICE.txt`
- `core/src/main/kotlin/com/notauyumu/core/transcribe/BasicPitch.kt`, `basic_pitch/inference.py` ve `basic_pitch/note_creation.py` dosyalarının Kotlin'e aktarımıdır.

Bittner, R. M., Bosch, J. J., Rubinstein, D., Meseguer-Brocal, G., & Ewert, S. (2022). *A Lightweight Instrument-Agnostic Model for Polyphonic Note Transcription and Multipitch Estimation.* ICASSP 2022.

## ONNX Runtime — Microsoft (MIT License)

Modeli telefonda çalıştırmak için kullanılır. https://github.com/microsoft/onnxruntime
