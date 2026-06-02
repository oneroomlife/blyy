package com.azurlane.blyy.domain

import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.viewmodel.ArchiveType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetShipsUseCase @Inject constructor(
    private val repository: ShipRepository,
    private val shipDao: com.azurlane.blyy.data.local.ShipDao
) {
    operator fun invoke(archiveType: ArchiveType = ArchiveType.DOCK): Flow<List<Ship>> {
        return shipDao.getShipsByArchiveType(archiveType.name)
    }
}