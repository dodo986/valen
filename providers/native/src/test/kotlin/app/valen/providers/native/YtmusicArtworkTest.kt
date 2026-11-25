/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.myspoti.api.Image
import app.myspoti.ytmusic.InnerTube
import app.myspoti.ytmusic.YtmParse
import app.valen.core.ProviderId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YtmusicArtworkTest {
    @Test
    fun `search rows without an album link keep their own thumbnail and upgrade small google urls`() = runBlocking {
        val searchJson = buildJsonObject {
            putJsonObject("contents") {
                putJsonObject("tabbedSearchResultsRenderer") {
                    putJsonArray("tabs") {
                        addJsonObject {
                            putJsonObject("tabRenderer") {
                                putJsonObject("content") {
                                    putJsonObject("sectionListRenderer") {
                                        putJsonArray("contents") {
                                            addJsonObject {
                                                putJsonObject("musicCardShelfRenderer") {
                                                    putJsonObject("title") {
                                                        putJsonArray("runs") {
                                                            addJsonObject {
                                                                put("text", "Top Single")
                                                                putJsonObject("navigationEndpoint") {
                                                                    putJsonObject("watchEndpoint") {
                                                                        put("videoId", "top00000001")
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    putJsonObject("subtitle") {
                                                        putJsonArray("runs") {
                                                            addJsonObject { put("text", "Song") }
                                                            addJsonObject { put("text", " • ") }
                                                            addJsonObject { put("text", "Indie Artist") }
                                                        }
                                                    }
                                                    putJsonObject("thumbnail") {
                                                        putJsonObject("musicThumbnailRenderer") {
                                                            putJsonObject("thumbnail") {
                                                                putJsonArray("thumbnails") {
                                                                    addJsonObject {
                                                                        put("url", "https://lh3.googleusercontent.com/top=w226-h226-l90-rj")
                                                                        put("width", 226)
                                                                        put("height", 226)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            addJsonObject {
                                                putJsonObject("musicShelfRenderer") {
                                                    putJsonArray("contents") {
                                                        addJsonObject {
                                                            putJsonObject("musicResponsiveListItemRenderer") {
                                                                putJsonObject("playlistItemData") { put("videoId", "row00000002") }
                                                                putJsonObject("thumbnail") {
                                                                    putJsonObject("musicThumbnailRenderer") {
                                                                        putJsonObject("thumbnail") {
                                                                            putJsonArray("thumbnails") {
                                                                                addJsonObject {
                                                                                    put("url", "https://lh3.googleusercontent.com/row=w60-h60-l90-rj")
                                                                                    put("width", 60)
                                                                                    put("height", 60)
                                                                                }
                                                                                addJsonObject {
                                                                                    put("url", "https://lh3.googleusercontent.com/row=w120-h120-l90-rj")
                                                                                    put("width", 120)
                                                                                    put("height", 120)
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                                putJsonArray("flexColumns") {
                                                                    addJsonObject {
                                                                        putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
                                                                            putJsonObject("text") {
                                                                                putJsonArray("runs") {
                                                                                    addJsonObject { put("text", "Linked Album Song") }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                    addJsonObject {
                                                                        putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
                                                                            putJsonObject("text") {
                                                                                putJsonArray("runs") {
                                                                                    addJsonObject {
                                                                                        put("text", "Real Album")
                                                                                        putJsonObject("navigationEndpoint") {
                                                                                            putJsonObject("browseEndpoint") {
                                                                                                put("browseId", "MPREb_123")
                                                                                                putJsonObject("browseEndpointContextSupportedConfigs") {
                                                                                                    putJsonObject("browseEndpointContextMusicConfig") {
                                                                                                        put("pageType", "MUSIC_PAGE_TYPE_ALBUM")
                                                                                                    }
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val tracks = YtmParse.search(searchJson)
            .filterIsInstance<YtmParse.Item.Song>()
            .mapNotNull { it.track.toValenTrack() }
        assertEquals(2, tracks.size)
        assertEquals("https://lh3.googleusercontent.com/top=w1200-h1200-l90-rj", tracks[0].artworkUrl)
        assertEquals("https://lh3.googleusercontent.com/row=w1200-h1200-l90-rj", tracks[1].artworkUrl)
    }

    @Test
    fun `largestYoutubeArtwork handles ytimg thumbnails and google size tokens`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123def45/sddefault.jpg?sqp=crop&rs=1",
            listOf(
                Image("https://i.ytimg.com/vi/abc123def45/hqdefault.jpg?sqp=small&rs=0", 168, 94),
                Image("https://i.ytimg.com/vi/abc123def45/sddefault.jpg?sqp=crop&rs=1", 400, 225),
            ).largestYoutubeArtwork(),
        )
        assertEquals(
            "https://i.ytimg.com/vi/abc123def45/hqdefault.jpg",
            listOf(
                Image("https://i.ytimg.com/vi/abc123def45/hqdefault.jpg?sqp=tiny&rs=0", 168, 94),
            ).largestYoutubeArtwork(),
        )
        assertEquals(
            "https://yt3.googleusercontent.com/artist=s1200-c-k-c0x00ffffff-no-rj",
            listOf(
                Image("https://yt3.googleusercontent.com/artist=s176-c-k-c0x00ffffff-no-rj", 176, 176),
            ).largestYoutubeArtwork(),
        )
    }

    @Test
    fun `playlist header and album cards upgrade small google covers`() = runBlocking {
        val playlistPage = buildJsonObject {
            putJsonObject("header") {
                putJsonObject("musicResponsiveHeaderRenderer") {
                    putJsonObject("title") { putJsonArray("runs") { addJsonObject { put("text", "My YTM List") } } }
                    putJsonObject("thumbnail") {
                        putJsonObject("musicThumbnailRenderer") {
                            putJsonObject("thumbnail") {
                                putJsonArray("thumbnails") {
                                    addJsonObject {
                                        put("url", "https://lh3.googleusercontent.com/pl=w226-h226-l90-rj")
                                        put("width", 226)
                                        put("height", 226)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("contents") {
                putJsonObject("musicPlaylistShelfRenderer") {
                    putJsonArray("contents") {}
                }
            }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(playlistPage.toString().toResponseBody()).build()
        }.build()
        val api = InnerTube(http, cookies = { null })
        val catalog = YtmusicPlaylistCatalog(api)
        val read = catalog.read(app.valen.core.PlaylistLink(ProviderId.YOUTUBE_MUSIC, "PL1", "https://music.youtube.com/playlist?list=PL1"))
        assertEquals("https://lh3.googleusercontent.com/pl=w1200-h1200-l90-rj", read.artworkUrl)
    }
}
