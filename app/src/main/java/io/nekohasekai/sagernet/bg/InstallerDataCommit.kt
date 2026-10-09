// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Durable checkpoints prevent a failed acknowledgement from replaying a data reset. @author 雾晚 */
internal object InstallerDataCommit {
    enum class Phase { BACKED_UP, MODULE_PREPARED, APP_COMMITTED, MODULE_FINISHED }
    enum class Stage { QUIESCE, BACKUP, JOURNAL, MODULE_PREPARE, APP_RESTORE, MODULE_FINISH, ACKNOWLEDGE, REFRESH }

    class Failure(val stage: Stage, cause: Exception) : IOException(
        cause.message?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "data_update_failed", cause)

    suspend fun <T> at(stage: Stage, action: suspend () -> T): T = try { action() }
        catch (error: CancellationException) { throw error }
        catch (error: Failure) { throw error }
        catch (error: Exception) { throw Failure(stage, error) }

    suspend fun run(
        phase: Phase,
        checkpoint: (Phase) -> Unit,
        prepare: suspend () -> Unit,
        restore: suspend () -> Unit,
        finish: suspend () -> Unit,
        acknowledge: suspend () -> Unit,
    ) {
        suspend fun step(next: Phase, stage: Stage, action: suspend () -> Unit) {
            if (phase < next) {
                at(stage, action)
                at(Stage.JOURNAL) { checkpoint(next) }
            }
        }
        step(Phase.MODULE_PREPARED, Stage.MODULE_PREPARE, prepare)
        step(Phase.APP_COMMITTED, Stage.APP_RESTORE, restore)
        step(Phase.MODULE_FINISHED, Stage.MODULE_FINISH, finish)
        at(Stage.ACKNOWLEDGE, acknowledge)
    }
}
