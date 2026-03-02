package com.example.blyy.domain

import com.example.blyy.data.model.VoiceLine
import com.example.blyy.data.repository.ShipRepository
import javax.inject.Inject

class GetVoicesUseCase @Inject constructor(
    private val repository: ShipRepository
) {
    suspend operator fun invoke(shipName: String): Triple<List<VoiceLine>, String, Map<String, String>> {
        return repository.fetchVoices(shipName)
    }
}
