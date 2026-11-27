package cn.local.mv

import android.app.Application
import android.content.Context
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.ostrichbat2k.usbdac.*
import org.ostrichbat2k.usbdac.streaming.RoutingAudioSink

class MvApp : Application() {
    internal lateinit var usb: UsbAudioManager
    override fun onCreate() {
        super.onCreate()
        Bili.initialize(this)
        val prefs = object : UsbDacPreferences {
            override val enabled = true
            override val bitPerfectMode = true
            override val abxTestMode = false
            override val volumeStepPercent = 2
            override val maxVolumePercent = UsbDacPreferences.DEFAULT_MAX_VOLUME_PERCENT
            override val manualReleaseControl = false
            override var volumeDb: Int? = null
            override fun registerListener(listener: UsbDacPreferences.Listener) {}
            override fun unregisterListener(listener: UsbDacPreferences.Listener) {}
        }
        usb = UsbAudioManagerImpl(this, prefs)
        usb.start()
    }
}

internal class UsbRenderers(context: Context, val manager: UsbAudioManager) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val system = DefaultAudioSink.Builder(context).setEnableFloatOutput(true).build()
        // The routing driver can choose fallback per track or after unplug. Reject that path before opening AudioTrack.
        val unavailable = object : AudioSink by system {
            override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                throw AudioSink.ConfigurationException("USB 独占不可用，请检查 DAC 连接及格式", inputFormat)
            }
        }
        return RoutingAudioSink(manager, unavailable)
    }
}

