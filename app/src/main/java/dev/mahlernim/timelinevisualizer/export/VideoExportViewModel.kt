package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

internal interface VideoExportStateGateway {
    val state: StateFlow<VideoExportSnapshot>
    fun restore()
    fun publish(snapshot: VideoExportSnapshot)
    fun clear()
}

private class CoordinatorVideoExportStateGateway(context: Context) : VideoExportStateGateway {
    private val applicationContext = context.applicationContext

    override val state: StateFlow<VideoExportSnapshot> = VideoExportCoordinator.state

    override fun restore() = VideoExportCoordinator.restore(applicationContext)

    override fun publish(snapshot: VideoExportSnapshot) =
        VideoExportCoordinator.publish(applicationContext, snapshot)

    override fun clear() = VideoExportCoordinator.clear(applicationContext)
}

class VideoExportViewModel internal constructor(
    private val gateway: VideoExportStateGateway,
) : ViewModel() {
    constructor(context: Context) : this(CoordinatorVideoExportStateGateway(context))

    val state: StateFlow<VideoExportSnapshot> = gateway.state
    val current: VideoExportSnapshot get() = state.value

    init {
        gateway.restore()
    }

    fun publish(snapshot: VideoExportSnapshot) = gateway.publish(snapshot)

    fun clear() = gateway.clear()
}
