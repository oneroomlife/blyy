package com.example.blyy.di

import android.content.Context
import com.example.blyy.data.local.PlayerSettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun providePlayerSettingsDataStore(
        @ApplicationContext context: Context
    ): PlayerSettingsDataStore {
        return PlayerSettingsDataStore(context)
    }
}
