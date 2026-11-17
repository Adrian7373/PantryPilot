package com.pantrypilot.app.insights

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pantrypilot.app.digest.DigestPrefs
import com.pantrypilot.app.digest.DigestSettings
import com.pantrypilot.data.UsageLog
import com.pantrypilot.domain.insights.MonthSummary
import com.pantrypilot.domain.insights.WasteLedger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** [history] is the last six months up to now, oldest first; [summary] is the month being viewed. */
data class InsightsState(
    val summary: MonthSummary,
    val history: List<MonthSummary>,
    val isCurrentMonth: Boolean,
    val hasAnyData: Boolean,
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    usage: UsageLog,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())

    /** Null until the log has loaded. */
    val state: StateFlow<InsightsState?> = combine(usage.observeEntries(), month) { entries, viewed ->
        val zone = ZoneId.systemDefault()
        val now = YearMonth.now()
        InsightsState(
            summary = WasteLedger.summarize(entries, viewed, zone),
            history = WasteLedger.history(entries, now, zone),
            isCurrentMonth = viewed >= now,
            hasAnyData = entries.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _digest = MutableStateFlow(DigestSettings.read(context))
    val digest: StateFlow<DigestPrefs> = _digest.asStateFlow()

    fun show(viewed: YearMonth) {
        month.value = minOf(viewed, YearMonth.now())
    }

    fun previousMonth() = show(month.value.minusMonths(1))

    fun nextMonth() = show(month.value.plusMonths(1))

    fun updateDigest(value: DigestPrefs) {
        DigestSettings.write(context, value)
        _digest.value = value
    }
}
