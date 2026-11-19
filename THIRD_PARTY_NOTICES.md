# Üçüncü taraf bileşenler

Nota Uyumu, **GNU Affero General Public License v3.0** ile lisanslıdır (bkz. `LICENSE`), çünkü AGPL-3.0 lisanslı homr'u kullanır.

## homr — Christian Liebhardt ve katkıda bulunanlar (AGPL-3.0)

PDF ve fotoğraftan nota okuma, açık kaynak **homr** projesinin porte okuma modelini kullanır.

- Kaynak: https://github.com/liebharc/homr
- Model dosyaları (depoda değildir; derleme sırasında `scripts/homr_modelleri.sh` homr'un GitHub sürümünden indirir ve SHA-256 ile doğrular):
  - `encoder_pytorch_model_566-ccd1cb31….onnx` → `app/src/main/assets/homr/encoder.onnx`
  - `decoder_pytorch_model_566-ccd1cb31….onnx` → `app/src/main/assets/homr/decoder.onnx`
- `core/src/main/kotlin/com/notauyumu/core/omr/HomrModel.kt`, `homr/transformer/staff2score.py` ve `decoder_inference.py` dosyalarının Kotlin aktarımıdır; `core/src/main/resources/homr/vocab.json`, `homr/transformer/vocabulary.py`'den üretilmiştir.
- `core/src/test/resources/omr/tabi.png`, homr deposundaki `figures/tabi.svg` dosyasından üretilmiştir.
- homr'un lisans SSS'sine göre model ağırlıklarının eğitim verileri: DeepScoresV2 (CC BY 4.0), OpenScore Lieder ve StringQuartets (CC0), GrandStaff, PDMX (CC BY 4.0, `no_license_conflict` alt kümesi).
- Porte bulma ve fotoğraf düzeltme (`StaffFinder.kt`) bu projeye özgüdür; homr'un segmentasyon modelini kullanmaz.

## Basic Pitch — Spotify AB (Apache License 2.0)

Ses kaydından nota çıkarma, Spotify'ın açık kaynak **Basic Pitch** modelini kullanır.

- Kaynak: https://github.com/spotify/basic-pitch
- Model dosyası: `app/src/main/assets/basic-pitch/nmp.onnx` (özgün `basic_pitch/saved_models/icassp_2022/nmp.onnx`, değiştirilmeden; SHA-256 `2c3c1d144bfa61ad236e92e169c13535c880469a12a047d4e73451f2c059a0ec`)
- Lisans ve NOTICE: `app/src/main/assets/basic-pitch/LICENSE.txt`, `NOTICE.txt`
- `core/src/main/kotlin/com/notauyumu/core/transcribe/BasicPitch.kt`, `basic_pitch/inference.py` ve `basic_pitch/note_creation.py` dosyalarının Kotlin'e aktarımıdır.

Bittner, R. M., Bosch, J. J., Rubinstein, D., Meseguer-Brocal, G., & Ewert, S. (2022). *A Lightweight Instrument-Agnostic Model for Polyphonic Note Transcription and Multipitch Estimation.* ICASSP 2022.

## ONNX Runtime — Microsoft (MIT License)

Modeli telefonda çalıştırmak için kullanılır. https://github.com/microsoft/onnxruntime
