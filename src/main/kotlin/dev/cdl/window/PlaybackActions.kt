// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.window

internal interface PlaybackActions {

    fun previous()

    fun play()

    fun pause()

    fun next()

    fun seekTo(positionMs: Long)

    companion object {

        val NONE: PlaybackActions = object : PlaybackActions {
            override fun previous() = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun next() = Unit
            override fun seekTo(positionMs: Long) = Unit
        }
    }
}
