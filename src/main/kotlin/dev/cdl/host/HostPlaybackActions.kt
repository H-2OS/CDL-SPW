// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.cdl.window.PlaybackActions

internal object HostPlaybackActions : PlaybackActions {

    override fun previous() {
        WorkshopApi.playback.previous()
    }

    override fun play() {
        WorkshopApi.playback.play()
    }

    override fun pause() {
        WorkshopApi.playback.pause()
    }

    override fun next() {
        WorkshopApi.playback.next()
    }

    override fun seekTo(positionMs: Long) {
        WorkshopApi.playback.seekTo(positionMs)
    }
}
