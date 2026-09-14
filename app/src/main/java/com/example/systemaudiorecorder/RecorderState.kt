package com.example.systemaudiorecorder

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

object RecorderState {
    val isRecording = MutableStateFlow(false)
    val status = MutableStateFlow("Готово к записи")
    val startedAtElapsedMs = MutableStateFlow<Long?>(null)
    val libraryVersion = MutableStateFlow(0)

    fun started() {
        isRecording.value = true
        startedAtElapsedMs.value = SystemClock.elapsedRealtime()
        status.value = "Запись системного аудио"
    }

    fun stopped(name: String?) {
        isRecording.value = false
        startedAtElapsedMs.value = null
        status.value = if (name != null) "Сохранено: $name" else "Запись остановлена"
        libraryVersion.value++
    }

    fun error(message: String) {
        isRecording.value = false
        startedAtElapsedMs.value = null
        status.value = "Ошибка: $message"
    }
}
