package com.pantrypilot.app.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pantrypilot.data.PantryRepository
import com.pantrypilot.data.db.Disposition
import com.pantrypilot.data.db.PantryItemEntity
import com.pantrypilot.domain.decay.StorageZone
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PantryViewModel @Inject constructor(
    private val repository: PantryRepository,
) : ViewModel() {
    val items: StateFlow<List<PantryItemEntity>> = repository.observeActiveItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markOpened(itemId: Long) = launch { repository.markOpened(itemId) }

    fun moveTo(itemId: Long, zone: StorageZone) = launch { repository.moveTo(itemId, zone) }

    fun markUsed(itemId: Long) = launch { repository.setDisposition(itemId, Disposition.CONSUMED) }

    fun markTossed(itemId: Long) = launch { repository.setDisposition(itemId, Disposition.TOSSED) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
