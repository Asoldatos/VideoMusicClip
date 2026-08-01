package com.musicvideo.recorder.util

import android.content.Context

/**
 * Simple SharedPreferences wrapper that carries the setup choices
 * (resolution + audio track) from SetupActivity into RecordActivity.
 */
object Prefs {
    private const val FILE = "music_video_recorder_prefs"
    private const val KEY_WIDTH = "res_width"
    private const val KEY_HEIGHT = "res_height"
    private const val KEY_LABEL = "res_label"
    private const val KEY_AUDIO_URI = "audio_uri"

    fun save(context: Context, width: Int, height: Int, label: String, audioUri: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WIDTH, width)
            .putInt(KEY_HEIGHT, height)
            .putString(KEY_LABEL, label)
            .putString(KEY_AUDIO_URI, audioUri)
            .apply()
    }

    fun width(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_WIDTH, 3840)

    fun height(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_HEIGHT, 2160)

    fun resolutionLabel(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LABEL, "4K UHD") ?: "4K UHD"

    fun audioUri(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_AUDIO_URI, null)
}
