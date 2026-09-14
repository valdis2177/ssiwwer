package com.example.systemaudiorecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RecorderApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecorderApp() {
    val context = LocalContext.current
    val projectionManager = remember { context.getSystemService(MediaProjectionManager::class.java) }
    val isRecording by RecorderState.isRecording.collectAsState()
    val status by RecorderState.status.collectAsState()
    val startedAt by RecorderState.startedAtElapsedMs.collectAsState()
    val libraryVersion by RecorderState.libraryVersion.collectAsState()

    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var bitrate by remember { mutableIntStateOf(prefs.getInt("bitrate", 192_000)) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var recordings by remember { mutableStateOf(emptyList<Recording>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var renameTarget by remember { mutableStateOf<Recording?>(null) }
    var renameText by remember { mutableStateOf("") }
    var playingId by remember { mutableStateOf<Long?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) { onDispose { mediaPlayer?.release() } }

    LaunchedEffect(isRecording, startedAt) {
        while (isRecording && startedAt != null) {
            elapsed = (SystemClock.elapsedRealtime() - startedAt!!) / 1000
            delay(250)
        }
        if (!isRecording) elapsed = 0
    }

    LaunchedEffect(libraryVersion, refresh) {
        recordings = withContext(Dispatchers.IO) { RecordingRepository.load(context) }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    putExtra(CaptureService.EXTRA_BIT_RATE, bitrate)
                }
            )
        } else RecorderState.status.value = "Захват экрана/аудио не разрешён"
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else RecorderState.status.value = "Нужно разрешение на запись аудио"
    }

    fun requestStart() {
        val audioOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioOk) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            permissionLauncher.launch(permissions)
        }
    }

    fun stop() {
        context.startService(Intent(context, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP })
    }

    fun play(r: Recording) {
        if (playingId == r.id) {
            mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null; playingId = null
            return
        }
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, r.uri)
            setOnCompletionListener { it.release(); mediaPlayer = null; playingId = null }
            prepare()
            start()
        }
        playingId = r.id
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("System Audio Recorder") }) }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (isRecording) "● REC" else "SYSTEM AUDIO", fontWeight = FontWeight.Bold,
                            color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(formatTimer(elapsed), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                        Text(status, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { if (isRecording) stop() else requestStart() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (isRecording) "Остановить и сохранить" else "Начать запись")
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Качество AAC", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(128_000, 192_000, 256_000).forEach { value ->
                                if (bitrate == value) Button(
                                    onClick = {}, modifier = Modifier.weight(1f), enabled = !isRecording
                                ) { Text("${value / 1000}k") }
                                else OutlinedButton(
                                    onClick = { bitrate = value; prefs.edit().putInt("bitrate", value).apply() },
                                    modifier = Modifier.weight(1f), enabled = !isRecording
                                ) { Text("${value / 1000}k") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("AAC LC • 44.1 kHz • Stereo • M4A", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Мои записи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { refresh++ }) { Text("Обновить") }
                }
            }

            if (recordings.isEmpty()) {
                item { Text("Записей пока нет. Они появятся здесь после сохранения.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(recordings, key = { it.id }) { r ->
                    RecordingCard(
                        recording = r,
                        isPlaying = playingId == r.id,
                        onPlay = { play(r) },
                        onRename = { renameTarget = r; renameText = r.name.removeSuffix(".m4a") },
                        onDelete = {
                            if (playingId == r.id) { mediaPlayer?.release(); mediaPlayer = null; playingId = null }
                            RecordingRepository.delete(context, r); refresh++
                        }
                    )
                }
            }

            item {
                Text(
                    "Важно: Android записывает только аудио приложений, которые разрешают AudioPlaybackCapture. Если источник запрещает захват, в записи будет тишина.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Переименовать запись") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("Имя файла") }) },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget?.let { RecordingRepository.rename(context, it, renameText) }
                    renameTarget = null; refresh++
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun RecordingCard(recording: Recording, isPlaying: Boolean, onPlay: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(recording.name, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatDuration(recording.durationMs)} • ${formatSize(recording.sizeBytes)} • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.dateAddedSeconds * 1000))}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = onPlay, modifier = Modifier.weight(1f)) { Text(if (isPlaying) "Стоп" else "Play") }
                OutlinedButton(onClick = onRename, modifier = Modifier.weight(1f)) { Text("Имя") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Удалить") }
            }
        }
    }
}

private fun formatTimer(s: Long) = "%02d:%02d".format(s / 60, s % 60)
private fun formatDuration(ms: Long): String { val s=ms/1000; return "%02d:%02d".format(s/60,s%60) }
private fun formatSize(bytes: Long): String = if (bytes < 1024*1024) "${bytes/1024} KB" else "%.1f MB".format(bytes/1024.0/1024.0)
