package com.example.systemaudiorecorder

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.example.systemaudiorecorder.START"
        const val ACTION_STOP = "com.example.systemaudiorecorder.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_BIT_RATE = "bit_rate"
        private const val CHANNEL_ID = "audio_capture"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 2
    }

    private val recording = AtomicBoolean(false)
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { recording.set(false); RecorderState.status.value = "Завершение записи…" }
            ACTION_START -> if (!recording.get()) {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                           else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val bitRate = intent.getIntExtra(EXTRA_BIT_RATE, 192_000).coerceIn(96_000, 320_000)
                if (code == Activity.RESULT_OK && data != null) {
                    startProjectionForeground()
                    recording.set(true); RecorderState.started()
                    Thread({ capture(code, data, bitRate) }, "AudioCapture").start()
                } else { RecorderState.error("Нет разрешения MediaProjection"); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjectionForeground() {
        val stopPi = PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openPi = PendingIntent.getActivity(this, 1, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("System Audio Recorder")
            .setContentText("Идёт запись системного аудио")
            .setContentIntent(openPi).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stopPi).build()).build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    @Suppress("MissingPermission")
    private fun capture(resultCode: Int, resultData: Intent, bitRate: Int) {
        var recorder: AudioRecord? = null; var codec: MediaCodec? = null; var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null; var outputUri: Uri? = null; var started = false; var savedName: String? = null
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
             val mediaProjection = manager.getMediaProjection(resultCode, resultData)
    ?: error("Не удалось получить MediaProjection")

projection = mediaProjection

mediaProjection.registerCallback(
    object : MediaProjection.Callback() {
        override fun onStop() {
            recording.set(false)
        }
    },
    Handler(Looper.getMainLooper())
)

val cfg = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = max(minBuf * 2, SAMPLE_RATE * CHANNEL_COUNT * 2 / 4)
            recorder = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bufSize).setAudioPlaybackCaptureConfig(cfg).build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord не инициализирован" }

            val outFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }

            val created = createOutput(); outputUri=created.first; pfd=created.second; savedName=created.third
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            recorder.startRecording()
            val info = MediaCodec.BufferInfo(); var track=-1; var frames=0L; var eos=false

            fun drain(wait:Boolean) {
                while (true) {
                    val i = codec.dequeueOutputBuffer(info, if(wait) 10_000 else 0)
                    when {
                        i >= 0 -> {
                            val b=codec.getOutputBuffer(i) ?: error("Нет output buffer")
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size=0
                            if(info.size>0) { check(started); b.position(info.offset); b.limit(info.offset+info.size); muxer.writeSampleData(track,b,info) }
                            eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(i,false); if(eos) return
                        }
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track=muxer.addTrack(codec.outputFormat); muxer.start(); started=true }
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> if(!wait) return
                    }
                }
            }

            while(recording.get()) {
                val i=codec.dequeueInputBuffer(10_000)
                if(i>=0) {
                    val b=codec.getInputBuffer(i) ?: error("Нет input buffer"); b.clear()
                    val n=recorder.read(b,min(b.capacity(),bufSize),AudioRecord.READ_BLOCKING)
                    if(n>0) { codec.queueInputBuffer(i,0,n,frames*1_000_000L/SAMPLE_RATE,0); frames += n/(CHANNEL_COUNT*2) }
                }
                drain(false)
            }
            try { recorder.stop() } catch(_:Exception) {}
            var queued=false
            while(!queued) { val i=codec.dequeueInputBuffer(10_000); if(i>=0) { codec.queueInputBuffer(i,0,0,frames*1_000_000L/SAMPLE_RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM); queued=true } }
            while(!eos) drain(true)
            if(started) { muxer.stop(); started=false }
            finalizeOutput(outputUri); RecorderState.stopped(savedName)
        } catch(t:Throwable) {
            outputUri?.let { try { contentResolver.delete(it,null,null) } catch(_:Exception){} }
            RecorderState.error(t.message ?: t.javaClass.simpleName)
        } finally {
            recording.set(false)
            try { recorder?.release() } catch(_:Exception){}
            try { codec?.stop() } catch(_:Exception){}; try { codec?.release() } catch(_:Exception){}
            try { if(started) muxer?.stop() } catch(_:Exception){}; try { muxer?.release() } catch(_:Exception){}
            try { pfd?.close() } catch(_:Exception){}; try { projection?.stop() } catch(_:Exception){}; projection=null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun createOutput(): Triple<Uri, ParcelFileDescriptor, String> {
        val stamp=SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(Date())
        val name="SystemAudio_$stamp.m4a"
        val values=ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME,name); put(MediaStore.Audio.Media.MIME_TYPE,"audio/mp4"); put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/SystemAudioRecorder"); put(MediaStore.Audio.Media.IS_PENDING,1) }
        val uri=contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values) ?: error("Не удалось создать файл")
        val fd=contentResolver.openFileDescriptor(uri,"rw") ?: error("Не удалось открыть файл")
        return Triple(uri,fd,name)
    }
    private fun finalizeOutput(uri: Uri?) { if(uri!=null) contentResolver.update(uri,ContentValues().apply{put(MediaStore.Audio.Media.IS_PENDING,0)},null,null) }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"Запись системного аудио",NotificationManager.IMPORTANCE_LOW)) }
    override fun onDestroy() { recording.set(false); super.onDestroy() }
}
