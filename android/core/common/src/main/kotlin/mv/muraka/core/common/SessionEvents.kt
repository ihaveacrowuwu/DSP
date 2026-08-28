package mv.muraka.core.common

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Something that happened to the session, which the UI has to react to. */
sealed interface SessionEvent {
    /**
     * The refresh token was rejected. The session is over.
     *
     * The contributor is returned to sign-in and **the queue is kept** - scenario 6 of
     * `mobile-shared/sync-protocol.md`. Clearing it here would throw away reef data
     * because a token expired, which is not a reason to lose anything.
     */
    data object RefreshFailed : SessionEvent

    /** An admin suspended the account. Sign out with an explanation, not a silent bounce. */
    data object AccountDisabled : SessionEvent
}

/**
 * A bus for the two events that can end a session from underneath the UI.
 *
 * It exists because the place that discovers them - an OkHttp `Authenticator`, deep
 * inside a background upload - has no way to reach a navigation controller, and passing
 * one down there would be far worse than a flow.
 *
 * `extraBufferCapacity` with `DROP_OLDEST`: emission must never suspend, because the
 * authenticator is on a network thread that cannot afford to block on a UI that may not
 * be collecting.
 */
@Singleton
class SessionEvents @Inject constructor() {
    private val _events = MutableSharedFlow<SessionEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    fun emit(event: SessionEvent) {
        _events.tryEmit(event)
    }
}
