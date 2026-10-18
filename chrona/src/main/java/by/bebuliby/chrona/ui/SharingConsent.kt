package by.bebuliby.chrona.ui

import by.bebuliby.chrona.domain.*
import java.time.LocalDate
import java.time.ZoneId

/** Captures who/where the user is granting access. Never infer the current server grant. */
internal data class SharingConsentTarget(val origin: String, val localProfileId: String, val accountId: String, val friendId: String)

internal fun sharingConsentTarget(state: ChronaUiState, friendId: String): SharingConsentTarget? {
    if (state.mode != DataMode.CONNECTED || !state.profile.telegramLinked || state.profile.accountId.isNullOrBlank() ||
        state.connection.loginPending || state.busy || state.friends.status != FeatureStatus.READY ||
        state.friends.friends.none { it.accountId == friendId && it.relation == FriendRelation.ACCEPTED }) return null
    val origin = normalizedPublicServerBaseUrl(state.connection.baseUrl) ?: return null
    return SharingConsentTarget(origin,state.profile.localProfileId,state.profile.accountId,friendId)
}

internal fun explicitSharingWindow(from: String, to: String, zone: String): SharingHistoryScope.TimeWindow? = try {
    if (!from.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || !to.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || zone !in ZoneId.getAvailableZoneIds()) null
    else {
        val z = ZoneId.of(zone); val a = LocalDate.parse(from).atStartOfDay(z).toInstant(); val b = LocalDate.parse(to).atStartOfDay(z).toInstant()
        if (b <= a) null else SharingHistoryScope.TimeWindow(a,b)
    }
} catch (_: Exception) { null }

internal fun confirmedSharingAction(state: ChronaUiState, target: SharingConsentTarget, ids: Set<String>,
                                    from: String, to: String, zone: String, consent: Boolean): ChronaAction.SaveSharing? {
    if (!consent || sharingConsentTarget(state,target.friendId) != target || ids.any { id -> state.habits.none { it.id == id } }) return null
    val window = explicitSharingWindow(from,to,zone) ?: return null
    return ChronaAction.SaveSharing(SharingGrantDraft(target.friendId,ids.toSet(),window,null))
}
