// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint

class CdlExtension : PlaybackExtensionPoint {

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        CdlPlugin.playback.trackChanged(mediaItem)

        return null
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {

        return null
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        CdlPlugin.playback.stateChanged(state)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        CdlPlugin.playback.playingChanged(isPlaying)
    }

    override fun onPositionUpdated(position: Long) {
        CdlPlugin.playback.positionChanged(position)
    }

    override fun onSeekTo(position: Long) {
        CdlPlugin.playback.seek(position)
    }

    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        CdlPlugin.playback.lineChanged(lyricsLine)
    }

    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        CdlPlugin.playback.linesChanged(lyricsLines)
    }
}
