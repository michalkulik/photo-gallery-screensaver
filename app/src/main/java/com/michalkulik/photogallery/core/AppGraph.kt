package com.michalkulik.photogallery.core

import android.content.Context
import com.michalkulik.photogallery.data.PhotoCache
import com.michalkulik.photogallery.data.PhotoRepository
import com.michalkulik.photogallery.google.GoogleAuth
import com.michalkulik.photogallery.google.GooglePhotosImporter
import com.michalkulik.photogallery.google.PickerClient
import com.michalkulik.photogallery.google.RelayClient
import com.michalkulik.photogallery.syno.SynoClient

/** Simple service locator; the app is small enough not to need a DI framework. */
class AppGraph(context: Context) {

    val settings: Settings = Settings(context)

    val cache: PhotoCache = PhotoCache(PhotoRepository.cacheRoot(context))

    val syno: SynoClient = SynoClient()

    val repository: PhotoRepository = PhotoRepository(context, settings, cache, syno)

    val relay: RelayClient = RelayClient()

    val auth: GoogleAuth = GoogleAuth(settings, relay)

    val picker: PickerClient = PickerClient(auth)

    val importer: GooglePhotosImporter = GooglePhotosImporter(auth, picker, cache)

    val weather: WeatherService = WeatherService(settings)

    val playbackHistory: StoredPlaybackHistory = StoredPlaybackHistory(settings)
}
