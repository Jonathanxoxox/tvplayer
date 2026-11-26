package tv.own.owntv.features.setup

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import tv.own.owntv.R
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.setup.displayText
import tv.own.owntv.core.util.FriendlySyncFailure

/** Words a semantic onboarding failure at the Compose boundary; the sentences are core's. */
@Composable
fun SourceImporter.SetupFailure.displayText(): String = displayText(LocalContext.current.resources)

/** The kinds of failed import the wizard explains differently. */
internal enum class FailureKind { LOGIN, ADDRESS, PROVIDER, OFFLINE, MAC, FILE, OTHER }

internal val SourceImporter.SetupFailure.kind: FailureKind
    get() = when (this) {
        SourceImporter.SetupFailure.InvalidMac -> FailureKind.MAC
        SourceImporter.SetupFailure.BackupRead, SourceImporter.SetupFailure.Restore -> FailureKind.OTHER
        is SourceImporter.SetupFailure.Sync -> when (failure) {
            FriendlySyncFailure.AuthenticationRejected -> FailureKind.LOGIN
            FriendlySyncFailure.Unreachable,
            FriendlySyncFailure.NotFound,
            FriendlySyncFailure.ConnectionFailed,
            FriendlySyncFailure.SecureConnectionFailed,
            FriendlySyncFailure.PortalHandshakeFailed,
            -> FailureKind.ADDRESS
            FriendlySyncFailure.Timeout,
            FriendlySyncFailure.ServerError,
            FriendlySyncFailure.StreamInterrupted,
            FriendlySyncFailure.PortalSessionExpired,
            -> FailureKind.PROVIDER
            FriendlySyncFailure.Offline -> FailureKind.OFFLINE
            FriendlySyncFailure.MacNotAuthorised, FriendlySyncFailure.InvalidMac -> FailureKind.MAC
            FriendlySyncFailure.PlaylistFileUnavailable, FriendlySyncFailure.PlaylistPathUnsupported -> FailureKind.FILE
            FriendlySyncFailure.Generic,
            FriendlySyncFailure.MalformedGuide,
            FriendlySyncFailure.GuideEmpty,
            is FriendlySyncFailure.Unknown,
            -> FailureKind.OTHER
        }
    }

/** The failure page's headline for this kind; null keeps the page's general "Import failed". */
@StringRes
internal fun FailureKind.headlineRes(): Int? = when (this) {
    FailureKind.LOGIN -> R.string.onboarding_wizard_fail_login
    FailureKind.ADDRESS -> R.string.onboarding_wizard_fail_address
    FailureKind.PROVIDER -> R.string.onboarding_wizard_fail_provider
    FailureKind.OFFLINE -> R.string.onboarding_wizard_fail_offline
    FailureKind.MAC -> R.string.onboarding_wizard_fail_mac
    FailureKind.FILE -> R.string.onboarding_wizard_fail_file
    FailureKind.OTHER -> null
}

/** What the user can do about it, shown under core's sentence; null where that sentence says it already. */
@StringRes
internal fun FailureKind.hintRes(): Int? = when (this) {
    FailureKind.LOGIN -> R.string.onboarding_wizard_hint_login
    FailureKind.ADDRESS -> R.string.onboarding_wizard_hint_address
    FailureKind.PROVIDER -> R.string.onboarding_wizard_hint_provider
    FailureKind.OFFLINE -> R.string.onboarding_wizard_hint_offline
    FailureKind.MAC, FailureKind.FILE -> null
    FailureKind.OTHER -> R.string.onboarding_wizard_hint_generic
}

/**
 * True where sending the same details again can work (the provider or the network had a moment),
 * so Try again is offered first; otherwise the details need changing and that is offered first.
 */
internal val FailureKind.passing: Boolean
    get() = this == FailureKind.PROVIDER || this == FailureKind.OFFLINE || this == FailureKind.OTHER
