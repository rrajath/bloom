package com.rrajath.grove.ui.vault

import android.content.Context
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.vault.storage.ProviderProfile
import com.rrajath.grove.vault.storage.ProviderProfiles
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageOp

/** What a name collision is about, so the message names a notebook or a folder. */
enum class StorageSubject { Notebook, Folder }

/**
 * The words for a failed storage operation: one message per [StorageError]
 * kind, so a refused rename never reads as "already exists". Exhaustive on
 * purpose: a new error kind must be given a message here before it compiles.
 *
 * [provider] names the provider in an [StorageError.Unsupported] message; it
 * defaults to the current vault's.
 */
fun storageErrorMessage(
    context: Context,
    error: StorageError,
    subject: StorageSubject = StorageSubject.Notebook,
    provider: ProviderProfile = currentProviderProfile(context),
): String = when (error) {
    StorageError.NameTaken -> context.getString(
        if (subject == StorageSubject.Folder) {
            R.string.storage_error_name_taken_folder
        } else {
            R.string.storage_error_name_taken_notebook
        },
    )
    StorageError.NotFound -> context.getString(R.string.storage_error_not_found)
    is StorageError.Unsupported -> unsupportedReason(context, error.op, provider)
    is StorageError.ProviderError ->
        if (error.message.isNullOrBlank()) {
            context.getString(R.string.storage_error_provider)
        } else {
            context.getString(R.string.storage_error_provider_detail, error.message)
        }
    StorageError.Offline -> context.getString(R.string.storage_error_offline)
    is StorageError.Conflict -> context.getString(R.string.storage_error_conflict)
    StorageError.AuthFailed -> context.getString(R.string.storage_error_auth_failed)
}

/** "Seafile doesn't let other apps rename files.": why [op] is refused or disabled. */
fun unsupportedReason(context: Context, op: StorageOp, provider: ProviderProfile): String = context.getString(
    when (op) {
        StorageOp.Create -> R.string.storage_error_unsupported_create
        StorageOp.CreateFolder -> R.string.storage_error_unsupported_create_folder
        StorageOp.Rename -> R.string.storage_error_unsupported_rename
        StorageOp.Move -> R.string.storage_error_unsupported_move
        StorageOp.Delete -> R.string.storage_error_unsupported_delete
        StorageOp.Write -> R.string.storage_error_unsupported_write
        StorageOp.Read -> R.string.storage_error_unsupported_read
    },
    context.getString(provider.displayName),
)

/** The current vault's provider profile; [ProviderProfiles.DEFAULT] outside the app (tests) or with no vault. */
fun currentProviderProfile(context: Context): ProviderProfile =
    (context.applicationContext as? GroveApplication)?.fileStore?.value?.capabilities?.value?.profile
        ?: ProviderProfiles.DEFAULT
