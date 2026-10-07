package com.soundbite

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.soundbite.audio.AudioPlaybackManager
import com.soundbite.data.repository.AudioVectorRepository
import com.soundbite.data.repository.AudioVectorRepositoryImpl
import com.soundbite.ml.EmbeddingGemmaManager
import com.soundbite.worker.AudioIndexingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main application class initializing the on-device ML runtime, vector store,
 * and playback systems.
 */
class SoundBiteApplication : Application() {

    companion object {
        private const val TAG = "SoundBiteApplication"
        lateinit var instance: SoundBiteApplication
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var embeddingGemmaManager: EmbeddingGemmaManager
        private set

    lateinit var audioVectorRepository: AudioVectorRepository
        private set

    lateinit var audioPlaybackManager: AudioPlaybackManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        initNotificationChannels()

        // Initialize core on-device components
        embeddingGemmaManager = EmbeddingGemmaManager(
            context = this,
            modelAssetPath = "embedding_gemma_2.tflite",
            targetDimensions = 256
        )

        audioVectorRepository = AudioVectorRepositoryImpl(
            context = this,
            boxStore = null // Automatically operates in resilient hybrid vector mode
        )

        audioPlaybackManager = AudioPlaybackManager(this)

        // Asynchronously warm up the on-device embedding engine in background
        applicationScope.launch {
            try {
                embeddingGemmaManager.initialize()
                Log.i(TAG, "EmbeddingGemma 2 engine primed and ready.")
            } catch (e: Exception) {
                Log.w(TAG, "Warmup initialized with projection fallback: ${e.message}")
            }
        }
    }

    private fun initNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val indexingChannel = NotificationChannel(
                AudioIndexingWorker.NOTIFICATION_CHANNEL_ID,
                getString(R.string.channel_indexing_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_indexing_desc)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(indexingChannel)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        embeddingGemmaManager.close()
        audioPlaybackManager.release()
    }
}

