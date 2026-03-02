
package com.example.blyy.service

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@UnstableApi
class PlaybackServiceConnection @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val mediaController: ListenableFuture<MediaController> =
        MediaController.Builder(context, sessionToken).buildAsync()
}
