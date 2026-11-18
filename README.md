# NotaUyumu Studio (Native Android — Kotlin & Jetpack Compose)

Çok partili PDF veya MusicXML (`.xml` / `.mxl`) notalarından istediğiniz partiyi seçip, gerçek zamanlı mikrofon girişiyle (**YIN Pitch Detection** + `AudioRecord`) entonasyonunuzu kıyaslayan, 60 FPS Piano Roll ve performans sonu doğruluk raporu sunan **%100 native Android** uygulamasıdır.

## Özellikler
- **API'siz Yerel PDF OMR:** Android `PdfRenderer` + piksel tabanlı yatay izdüşüm ve eliptik nota başı tanıma algoritması ile PDF notalarını internet veya harici API olmadan partilere ayırır.
- **MusicXML Desteği:** `.xml` ve `.mxl` çok kanallı nota dosyalarını doğrudan okur.
- **Gerçek Zamanlı Ses Motoru:** `AudioRecord` (44.1 kHz PCM) + saf Kotlin YIN algoritması + ayarlanabilir RMS Noise Gate.
- **Referans Sentezleyici & Metronom:** `AudioTrack` ile ilk nota / tüm parti ön dinleme ve 4 vuruşluk geri sayım.
- **60 FPS Compose Piano Roll:** Yeşil/Sarı/Kırmızı anlık cent geri bildirimi ve canlı frekans HUD'ı.

## GitHub Actions ile Otomatik APK Derleme
Bu repoyu GitHub'a yüklediğinizde `.github/workflows/build-apk.yml` iş akışı otomatik çalışır ve **Actions > Build Android APK > Artifacts** altından `NotaUyumu-debug.apk` dosyasını doğrudan indirebilirsiniz.

## Android Studio ile Yerel Derleme
1. Projeyi Android Studio (Ladybug veya üzeri) ile açın.
2. Gradle senkronizasyonunun tamamlanmasını bekleyin.
3. **Build > Build Bundle(s) / APK(s) > Build APK(s)** menüsünden APK çıktısı alın veya doğrudan Android cihazınızda çalıştırın.
