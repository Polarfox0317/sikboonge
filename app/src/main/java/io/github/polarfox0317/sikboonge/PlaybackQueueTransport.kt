package io.github.polarfox0317.sikboonge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueTransportAvailability(
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

/** Process bridge between the active queue owner and the media session service. */
object PlaybackQueueTransport {
    private data class Owner(
        val token: Any,
        val priority: Int,
        val order: Long,
        var availability: QueueTransportAvailability,
        val previous: () -> Boolean,
        val next: () -> Boolean,
    )

    private val owners = mutableListOf<Owner>()
    private var nextOrder = 0L
    private val mutableAvailability = MutableStateFlow(QueueTransportAvailability())
    val availability: StateFlow<QueueTransportAvailability> = mutableAvailability.asStateFlow()

    @Synchronized
    fun bind(
        owner: Any,
        priority: Int,
        availability: QueueTransportAvailability,
        previous: () -> Boolean,
        next: () -> Boolean,
    ) {
        owners.removeAll { it.token === owner }
        owners += Owner(owner, priority, ++nextOrder, availability, previous, next)
        publishAvailability()
    }

    @Synchronized
    fun update(owner: Any, availability: QueueTransportAvailability) {
        owners.firstOrNull { it.token === owner }?.availability = availability
        publishAvailability()
    }

    @Synchronized
    fun unbind(owner: Any) {
        owners.removeAll { it.token === owner }
        publishAvailability()
    }

    @Synchronized
    internal fun owns(owner: Any): Boolean = activeOwner()?.token === owner

    fun previous(): Boolean = dispatch({ it.hasPrevious }) { it.previous }

    fun next(): Boolean = dispatch({ it.hasNext }) { it.next }

    @Synchronized
    private fun dispatch(available: (QueueTransportAvailability) -> Boolean, action: (Owner) -> (() -> Boolean)): Boolean {
        val selected = activeOwner() ?: return false
        if (!available(selected.availability)) return false
        return runCatching { action(selected)() }.getOrDefault(false)
    }

    @Synchronized
    private fun activeOwner(): Owner? = owners.maxWithOrNull(compareBy<Owner> { it.priority }.thenBy { it.order })

    private fun publishAvailability() {
        mutableAvailability.value = activeOwner()?.availability ?: QueueTransportAvailability()
    }
}
