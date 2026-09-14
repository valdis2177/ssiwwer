# System Audio Recorder v2

Android 10+ системный аудиорекордер на Kotlin/Jetpack Compose.

## Функции
- AudioPlaybackCapture + MediaProjection
- AAC LC / M4A, stereo 44.1 kHz
- выбор 128 / 192 / 256 kbps
- foreground service
- список записей из Music/SystemAudioRecorder
- Play/Stop, Rename, Delete
- без нативных .so: APK ABI-independent и совместим с 64-bit arm64-v8a

## Важно
Android позволяет приложению-источнику запретить AudioPlaybackCapture. Тогда рекордер не может принудительно получить поток, и запись будет без звука.

## Сборка
Конфигурация: AGP 9.4.0, Gradle 9.6, Kotlin 2.3.21, compile/target SDK 37, JDK 17.
Откройте проект в актуальном Android Studio, выполните Sync и Build APK(s).

В этой копии `gradle-wrapper.jar` не включён, потому что текущая среда генерации не имеет Android/Gradle SDK и сетевого доступа к бинарным дистрибутивам. Android Studio может использовать установленный Gradle/создать wrapper, либо выполните `gradle wrapper --gradle-version 9.6.0`.
