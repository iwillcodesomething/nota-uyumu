# Nota Uyumu 2

Koristler için parti çalışma uygulaması (Android). MusicXML notasını aç, partini seç, söyle; uygulama perdeni anında gösterir ve sonunda hangi ölçülerde zorlandığını söyler.

Bu sürüm sıfırdan yazıldı. Önceki sürümün fikri korundu; kod, arayüz ve ses motoru yeniden tasarlandı.

## Ne yapar?

- **Nota açma:** `.musicxml`, `.xml` ve sıkıştırılmış `.mxl`. Dosya yöneticisinden veya WhatsApp'tan "Birlikte aç" ile de açılabilir.
- **Parti ayrımı:** Ayrı partiler (S/A/T/B) ve tek portede iki ses (ör. "Kadınlar · üst ses / alt ses") ayrı ayrı seçilebilir. Sekizlik tenor anahtarı, bağlar, akorlar, şarkı sözleri, anakruz ve tempo değişimleri doğru okunur.
- **Dinle:** Kendi partin öne çıkar, diğer partiler kısık çalar; ses düzeyleri ayarlanabilir.
- **Söyle:** Kayan nota şeridinde hedef notalar ve senin perde çizgin yan yana görünür (yeşil: doğru, sarı: yakın, kırmızı: uzak). Alttaki gösterge kaç cent pes/tiz olduğunu söyler.
- **Değerlendirme:** Genel yüzde, ölçü ölçü sonuç, genel pes/tiz eğilimi; zorlandığın ölçüleri tek dokunuşla döngüye alma; parti başına en iyi skor.
- **Çalışma ayarları:** Tempo (%40–130), ölçü aralığı, tolerans (Rahat ±50¢ / Normal ±35¢ / Titiz ±20¢), metronom, oktav farkını yoksayma, gecikme telafisi.
- **Örnek eser:** Uygulamayla gelen 4 sesli "Isınma Korali".

PDF notalar bilerek desteklenmiyor: resimden nota okumak güvenilir sonuç vermiyor. MuseScore'da `Dosya → Dışa aktar → MusicXML` ile dosya üretilebilir.

## Yapı

| Modül | İçerik |
| --- | --- |
| `core/` | Saf Kotlin, Android'e bağımlı değil: MusicXML okuyucu, YIN perde algılama, puanlama, sentezleyici. Birim testleri burada. |
| `app/` | Android: ses motoru (`AudioTrack` + `AudioRecord`), Jetpack Compose arayüzü, eser kitaplığı. |

Ses ve görüntü aynı saatten beslenir (AudioTrack oynatma kafası); mikrofon ölçümleri bu saate göre zaman damgası alır.

## Derleme

```sh
./gradlew :core:test          # çekirdek testleri
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions her gönderimde testleri çalıştırır ve APK'yı **Actions → ilgili çalışma → Artifacts → NotaUyumu-apk** altına koyar. `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` gizli anahtarları tanımlıysa imzalı release APK da üretilir; `main` dalında GitHub sürümü olarak yayımlanır.
