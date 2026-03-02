package com.example.blyy.domain

import com.example.blyy.data.model.Ship
import com.example.blyy.data.repository.ShipRepository
import com.example.blyy.viewmodel.ArchiveType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetShipsUseCase @Inject constructor(
    private val repository: ShipRepository,
    private val shipDao: com.example.blyy.data.local.ShipDao
) {
    operator fun invoke(archiveType: ArchiveType = ArchiveType.DOCK): Flow<List<Ship>> {
        return shipDao.getShipsByArchiveType(archiveType.name)
    }
}