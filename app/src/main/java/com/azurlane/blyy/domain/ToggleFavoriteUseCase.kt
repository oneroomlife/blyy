package com.azurlane.blyy.domain

import com.azurlane.blyy.data.repository.ShipRepository
import javax.inject.Inject

class ToggleFavoriteUseCase @Inject constructor(
    private val repository: ShipRepository
) {
    suspend operator fun invoke(shipName: String, isFavorite: Boolean) {
        repository.toggleFav(shipName, isFavorite)
    }
}