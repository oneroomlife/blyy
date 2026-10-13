package com.azurlane.blyy.domain

import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.data.repository.VoicesData
import javax.inject.Inject

class GetVoicesUseCase @Inject constructor(
    private val repository: ShipRepository
) {
    suspend operator fun invoke(shipName: String): VoicesData {
        return repository.fetchVoices(shipName)
    }
}
