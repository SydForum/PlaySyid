package com.darkxvenom.airbeats.recognition

interface MusicRecognitionEngine {
    val providerName: String
    suspend fun recognize(audio: AudioSource): RecognitionResult
}
