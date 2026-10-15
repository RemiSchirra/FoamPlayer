package com.foamplayer.app.audio.engine

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.foamplayer.app.data.distant.DistantStream

/**
 * Rewrites the synthetic `foam-distant://` track URIs into the real, authorized
 * stream URL (plus any required headers) right before the data source opens
 * them. Local files pass through untouched.
 */
@UnstableApi
class DistantDataSourceFactory(
    private val context: Context,
    private val streams: (String) -> DistantStream?
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val upstream = DefaultDataSource.Factory(context).createDataSource()
        return ResolvingDataSource(upstream) { dataSpec ->
            val stream = streams(dataSpec.uri.toString())
            if (stream == null) {
                dataSpec
            } else {
                dataSpec.buildUpon()
                    .setUri(stream.url.toUri())
                    .setHttpRequestHeaders(stream.headers)
                    .build()
            }
        }
    }
}
