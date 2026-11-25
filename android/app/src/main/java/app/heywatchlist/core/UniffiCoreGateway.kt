package app.heywatchlist.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.heywatchlist_core.CoreException
import uniffi.heywatchlist_core.InternalException
import uniffi.heywatchlist_core.coreVersion
import uniffi.heywatchlist_core.pingAsync

/** Appelle le cœur par les liaisons UniFFI, sur un thread d'arrière-plan. */
class UniffiCoreGateway(
    // Le premier appel charge la bibliothèque native : disque, pas de calcul.
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CoreGateway {
    override suspend fun load(): CoreInfo = withContext(dispatcher) {
        try {
            CoreInfo(version = coreVersion(), pong = pingAsync())
        } catch (error: CoreException) {
            throw CoreFailureException(error.toFailure(), error)
        } catch (error: InternalException) {
            // Panique du cœur, rattrapée à la frontière par UniFFI.
            throw CoreFailureException(CoreFailure.Internal, error)
        } catch (error: LinkageError) {
            // UnsatisfiedLinkError, ou NoClassDefFoundError après un premier échec.
            throw CoreFailureException(CoreFailure.Unavailable, error)
        }
    }

    private fun CoreException.toFailure(): CoreFailure =
        if (this is CoreException.Internal) CoreFailure.Internal else CoreFailure.Unknown
}
