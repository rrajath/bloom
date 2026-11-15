package com.rrajath.grove.vault.storage

/**
 * What a storage operation produced: [Ok] with a value, or one of the
 * [StorageError]s saying why not. Every failure a caller can act on has its own
 * kind, so the UI can say what went wrong instead of guessing from a `false`.
 *
 * Exhaustive `when`s over this type must cover [StorageError.AuthFailed] and
 * [StorageError.Conflict] even though no SAF backend produces them yet: a
 * network backend will, and adding a kind later would touch every `when`.
 */
sealed interface StorageResult<out T> {
    data class Ok<out T>(val value: T) : StorageResult<T>
}

/** The ways a storage operation can fail. */
sealed interface StorageError : StorageResult<Nothing> {
    /** Something already has the target name. */
    data object NameTaken : StorageError

    /** The source (or its parent folder) isn't there. */
    data object NotFound : StorageError

    /** The provider doesn't let other apps do [op] (Seafile has no rename, for one). */
    data class Unsupported(val op: StorageOp) : StorageError

    /** The provider refused or failed; [message] is its own words, when it gave any. */
    data class ProviderError(val message: String?) : StorageError

    /** The provider couldn't be reached. */
    data object Offline : StorageError

    /** A conditional write found the file changed since the caller read it. */
    data class Conflict(val currentRevision: String?) : StorageError

    /** The provider wants the user to sign in again. */
    data object AuthFailed : StorageError
}

/** The value of a [StorageResult.Ok], or null for any [StorageError]. */
fun <T> StorageResult<T>.getOrNull(): T? = (this as? StorageResult.Ok)?.value

/** True for [StorageResult.Ok]. */
val StorageResult<*>.isOk: Boolean get() = this is StorageResult.Ok

/** [transform] applied to a [StorageResult.Ok] value; errors pass through. */
inline fun <T, R> StorageResult<T>.map(transform: (T) -> R): StorageResult<R> = when (this) {
    is StorageResult.Ok -> StorageResult.Ok(transform(value))
    is StorageError -> this
}

/** The error, or null for [StorageResult.Ok]. */
fun StorageResult<*>.errorOrNull(): StorageError? = this as? StorageError
