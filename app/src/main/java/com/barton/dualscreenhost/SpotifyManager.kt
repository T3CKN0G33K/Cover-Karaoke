package com.barton.dualscreenhost

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val uri: String,
    val imageUrl: String
)

class SpotifyManager(private val context: Context) {

    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    var onPlaylistsLoaded: ((List<SpotifyPlaylist>) -> Unit)? = null

    fun fetchPlaylists(accessToken: String? = null) {
        if (!accessToken.isNullOrEmpty()) {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=15")
                .header("Authorization", "Bearer $accessToken")
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    mainHandler.post { loadFallbackPlaylists() }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful && body.isNotEmpty()) {
                        try {
                            val json = JSONObject(body)
                            val items = json.optJSONArray("items")
                            val list = mutableListOf<SpotifyPlaylist>()

                            if (items != null) {
                                for (i in 0 until items.length()) {
                                    val item = items.getJSONObject(i)
                                    val id = item.optString("id", "")
                                    val name = item.optString("name", "Playlist")
                                    val uri = item.optString("uri", "spotify:playlist:$id")
                                    val images = item.optJSONArray("images")
                                    val imgUrl = if (images != null && images.length() > 0) {
                                        images.getJSONObject(0).optString("url", "")
                                    } else ""

                                    list.add(SpotifyPlaylist(id, name, uri, imgUrl))
                                }
                            }

                            if (list.isNotEmpty()) {
                                mainHandler.post { onPlaylistsLoaded?.invoke(list) }
                                return
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    mainHandler.post { loadFallbackPlaylists() }
                }
            })
        } else {
            loadFallbackPlaylists()
        }
    }

    private fun loadFallbackPlaylists() {
        val curated = listOf(
            SpotifyPlaylist(
                "37i9dQZF1DXcBWIGoYBM5M",
                "Today's Top Hits",
                "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
                "https://i.scdn.co/image/ab67706f000000032c253e921d7e2e34346765ff"
            ),
            SpotifyPlaylist(
                "37i9dQZF1DX139K6K4S3Y3",
                "Karaoke Classics",
                "spotify:playlist:37i9dQZF1DX139K6K4S3Y3",
                "https://i.scdn.co/image/ab67706f00000003b123f85890e0394747ee2d74"
            ),
            SpotifyPlaylist(
                "37i9dQZF1DWUaScT33y2O0",
                "Pop Rising",
                "spotify:playlist:37i9dQZF1DWUaScT33y2O0",
                "https://i.scdn.co/image/ab67706f00000003e85e921b0e014798a1c97a89"
            ),
            SpotifyPlaylist(
                "37i9dQZF1DX4WYpd932R2C",
                "Chill Hits",
                "spotify:playlist:37i9dQZF1DX4WYpd932R2C",
                "https://i.scdn.co/image/ab67706f000000034638706240212a4f475a894a"
            ),
            SpotifyPlaylist(
                "37i9dQZF1DX1r33O41N4m3",
                "Rock Classics",
                "spotify:playlist:37i9dQZF1DX1r33O41N4m3",
                "https://i.scdn.co/image/ab67706f0000000366657e2a9b31d9990807b03b"
            )
        )
        onPlaylistsLoaded?.invoke(curated)
    }
}
