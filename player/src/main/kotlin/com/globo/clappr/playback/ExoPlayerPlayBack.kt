package com.globo.clappr.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.widget.FrameLayout
import com.globo.clappr.base.Event
import com.globo.clappr.base.Options
import com.globo.clappr.components.Playback
import com.globo.clappr.components.PlaybackSupportInterface
import com.globo.clappr.timer.TimerManager
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveVideoTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.SimpleExoPlayerView
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import java.io.IOException

open class ExoPlayerPlayback(source: String, mimeType: String? = null, options: Options = Options()) : Playback(source, mimeType, options) {
    companion object : PlaybackSupportInterface {
        override fun supportsSource(source: String, mimeType: String?): Boolean {
            val uri = Uri.parse(source)
            val type = Util.inferContentType(uri.lastPathSegment)
            return type == C.TYPE_SS || type == C.TYPE_HLS || type == C.TYPE_DASH || type == C.TYPE_OTHER
        }

        override val name: String = "exoplayerplayback"
    }

    private val mainHandler = Handler()
    private val bandwidthMeter = DefaultBandwidthMeter()
    private var playerView = SimpleExoPlayerView(context)
    private val eventsListener = ExoplayerEventsListener()
    private var player: SimpleExoPlayer? = null
    private var currentState = State.NONE
    private var trackSelector: DefaultTrackSelector? = null
    private var timer: TimerManager? = null
    private val ONE_SECOND_IN_MILLIS: Int = 1000

    private val frameLayout: FrameLayout
        get() = view as FrameLayout

    override val viewClass: Class<*>
        get() = FrameLayout::class.java

    override val duration: Double
        get() =(player?.duration ?: 0L).toDouble() * ONE_SECOND_IN_MILLIS

    override val state: State
        get() = currentState

    override val canPlay: Boolean
        get() = currentState == State.PAUSED ||
                currentState == State.IDLE ||
                (currentState == State.STALLED && player?.playWhenReady == false)

    override val canPause: Boolean
        get() = currentState == State.PLAYING || currentState == State.STALLED

    override val canSeek: Boolean
        get() = duration != 0.0 && currentState != State.IDLE && currentState != State.ERROR

    override fun play(): Boolean {
        if (player == null) {
            setupPlayer()
            load(source, mimeType)
            play()
        } else {
            trigger(Event.WILL_PLAY)
            player?.playWhenReady = true
        }
        return true
    }

    override fun pause(): Boolean {
        trigger(Event.WILL_PAUSE)
        player?.playWhenReady = false
        return true
    }

    override fun stop(): Boolean {
        trigger(Event.WILL_STOP)
        player?.stop()
        return true
    }

    override fun seek(seconds: Int): Boolean {
        trigger(Event.WILL_SEEK)
        player?.seekTo((seconds * 1000).toLong())
        trigger(Event.DID_SEEK)
        return true
    }

    override fun load(source: String, mimeType: String?): Boolean {
        player?.prepare(mediaSource(Uri.parse(source)))
        return true
    }

    private fun mediaSource(uri: Uri): MediaSource {
        val type = Util.inferContentType(uri.lastPathSegment)
        val dataSourceFactory = DefaultDataSourceFactory(context, "agent", bandwidthMeter)

        when (type) {
            C.TYPE_DASH -> return DashMediaSource(uri, dataSourceFactory, DefaultDashChunkSource.Factory(dataSourceFactory), mainHandler, eventsListener)
            C.TYPE_SS -> return SsMediaSource(uri, dataSourceFactory, DefaultSsChunkSource.Factory(dataSourceFactory), mainHandler, eventsListener)
            C.TYPE_HLS -> return HlsMediaSource(uri, dataSourceFactory, mainHandler, eventsListener)
            C.TYPE_OTHER -> return ExtractorMediaSource(uri, dataSourceFactory, DefaultExtractorsFactory(), mainHandler, eventsListener)
            else -> throw IllegalStateException("Unsupported type: " + type)
        }
    }

    private fun setupPlayer() {
        val videoTrackSelectionFactory = AdaptiveVideoTrackSelection.Factory(bandwidthMeter)
        trackSelector = DefaultTrackSelector(mainHandler, videoTrackSelectionFactory)
        player = ExoPlayerFactory.newSimpleInstance(context, trackSelector, DefaultLoadControl())
        player?.playWhenReady = false
        player?.addListener(eventsListener)
        setupPlayerView()
        setupTimeElapsedCallBacks()
        load(source, mimeType)
    }

    private fun setupPlayerView() {
        frameLayout.addView(playerView)
        playerView.player = player
        playerView.setUseController(false)
    }

    private fun setupTimeElapsedCallBacks() {
        timer = TimerManager(200, { dispatchTimeElapsedEvents() })
    }

    private fun dispatchTimeElapsedEvents() {
        dispatchBufferPercentageEvent()
        dispatchPercentPlayedEvent()
    }

    private fun dispatchBufferPercentageEvent() {
        val bundle = Bundle()
        val bufferPercentage = player?.bufferedPercentage?.toDouble() ?: 0.0

        bundle.putDouble("percentage", bufferPercentage)
        trigger(Event.BUFFER_UPDATE.value, bundle)
    }

    private fun dispatchPercentPlayedEvent() {
        val bundle = Bundle()
        val currentPosition = player?.currentPosition?.toDouble() ?: 0.0
        val percentage = if (duration != 0.0) (currentPosition / duration) * 100 else 0.0

        bundle.putDouble("percentage", percentage)
        bundle.putDouble("time", currentPosition / 1000)
        trigger(Event.POSITION_UPDATE.value, bundle)
    }

    private fun updateState(playWhenReady: Boolean, playbackState: Int) {
        when (playbackState) {
            ExoPlayer.STATE_IDLE -> handleExoplayerIdleState()
            ExoPlayer.STATE_ENDED -> handleExoplayerEndedState()
            ExoPlayer.STATE_BUFFERING -> handleExoplayerBufferingState()
            ExoPlayer.STATE_READY -> handleExoplayerReadyState(playWhenReady)
        }
    }

    private fun handleExoplayerReadyState(playWhenReady: Boolean) {
        if (playWhenReady) {
            currentState = State.PLAYING
            trigger(Event.PLAYING)
            timer?.start()
        } else {
            currentState = State.PAUSED
            trigger(Event.DID_PAUSE)
        }
    }

    private fun handleExoplayerBufferingState() {
        currentState = State.STALLED
        trigger(Event.STALLED)
    }

    private fun handleExoplayerEndedState() {
        currentState = State.IDLE
        trigger(Event.DID_COMPLETE)
        stop()
    }

    private fun handleExoplayerIdleState() {
        if (currentState == State.NONE) {
            trigger(Event.READY)
        } else {
            trigger(Event.DID_STOP)
        }
        currentState = State.IDLE
        timer?.stop()
    }

    private fun trigger(event: Event) {
        trigger(event.value)
    }

    private fun triggerErrorEvent(error: Exception?) {
        val bundle = Bundle()
        bundle.putString(Event.ERROR.value, error?.message)
        trigger(Event.ERROR.value, bundle)
    }

    inner class ExoplayerEventsListener() : AdaptiveMediaSourceEventListener, ExtractorMediaSource.EventListener, ExoPlayer.EventListener {
        override fun onLoadError(error: IOException?) {
            triggerErrorEvent(error)
        }

        override fun onLoadError(dataSpec: DataSpec?, dataType: Int, trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaStartTimeMs: Long, mediaEndTimeMs: Long, elapsedRealtimeMs: Long, loadDurationMs: Long, bytesLoaded: Long, error: IOException?, wasCanceled: Boolean) {
            triggerErrorEvent(error)
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            updateState(playWhenReady, playbackState)
        }

        override fun onPlayerError(error: ExoPlaybackException?) {
            triggerErrorEvent(error)
        }

        override fun onLoadingChanged(isLoading: Boolean) {
        }

        override fun onPositionDiscontinuity() {
        }

        override fun onTimelineChanged(timeline: Timeline?, manifest: Any?) {
        }

        override fun onLoadStarted(dataSpec: DataSpec?, dataType: Int, trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaStartTimeMs: Long, mediaEndTimeMs: Long, elapsedRealtimeMs: Long) {
        }

        override fun onDownstreamFormatChanged(trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaTimeMs: Long) {
        }

        override fun onUpstreamDiscarded(trackType: Int, mediaStartTimeMs: Long, mediaEndTimeMs: Long) {
        }

        override fun onLoadCanceled(dataSpec: DataSpec?, dataType: Int, trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaStartTimeMs: Long, mediaEndTimeMs: Long, elapsedRealtimeMs: Long, loadDurationMs: Long, bytesLoaded: Long) {
        }

        override fun onLoadCompleted(dataSpec: DataSpec?, dataType: Int, trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaStartTimeMs: Long, mediaEndTimeMs: Long, elapsedRealtimeMs: Long, loadDurationMs: Long, bytesLoaded: Long) {
        }
    }
}