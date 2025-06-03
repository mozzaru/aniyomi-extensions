package eu.kanade.tachiyomi.lib.dailymotionextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy

class DailymotionExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val DAILYMOTION_URL = "https://www.dailymotion.com"
        private const val GRAPHQL_URL = "https://graphql.api.dailymotion.com"
    }

    private fun headersBuilder(block: Headers.Builder.() -> Unit = {}) = headers.newBuilder()
        .add("Accept", "*/*")
        .set("Referer", "$DAILYMOTION_URL/")
        .set("Origin", DAILYMOTION_URL)
        .apply { block() }
        .build()

    private val json: Json by injectLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = "Dailymotion - ", baseUrl: String = "", password: String? = null): List<Video> {
        try {
            println("🔍 Fetching HTML from Dailymotion URL: $url")
            val htmlString = client.newCall(GET(url)).execute().body.string()

            val internalData = htmlString.substringAfter("\"dmInternalData\":").substringBefore("</script>")
            val ts = internalData.substringAfter("\"ts\":").substringBefore(",")
            val v1st = internalData.substringAfter("\"v1st\":\"").substringBefore("\",")

            val videoQuery = url.toHttpUrl().run {
                queryParameter("video") ?: pathSegments.last()
            }

            println("🎯 Video ID extracted: $videoQuery")

            val jsonUrl = "$DAILYMOTION_URL/player/metadata/video/$videoQuery?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
            println("🌐 Fetching metadata JSON: $jsonUrl")

            val parsed = client.newCall(GET(jsonUrl)).execute().parseAs<DailyQuality>()
            println("📦 Parsed response: ${parsed.id}, error=${parsed.error?.type}")

            return when {
                parsed.qualities != null && parsed.error == null -> videosFromDailyResponse(parsed, prefix)
                parsed.error?.type == "password_protected" && parsed.id != null -> {
                    println("🔐 Video is password protected. Trying alternate flow...")
                    videosFromProtectedUrl(url, prefix, parsed.id, htmlString, ts, v1st, baseUrl, password)
                }
                else -> {
                    println("❌ No playable video found or unknown error.")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            println("❗ Exception while fetching video: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun videosFromProtectedUrl(
        url: String,
        prefix: String,
        videoId: String,
        htmlString: String,
        ts: String,
        v1st: String,
        baseUrl: String,
        password: String?,
    ): List<Video> {
        try {
            println("🔐 Performing password-protected video flow...")

            val postUrl = "$GRAPHQL_URL/oauth/token"
            val clientId = htmlString.substringAfter("client_id\":\"").substringBefore('"')
            val clientSecret = htmlString.substringAfter("client_secret\":\"").substringBefore('"')
            val scope = htmlString.substringAfter("client_scope\":\"").substringBefore('"')

            println("🔑 Getting access token with client_id=$clientId")

            val tokenBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("traffic_segment", ts)
                .add("visitor_id", v1st)
                .add("grant_type", "client_credentials")
                .add("scope", scope)
                .build()

            val tokenParsed = client.newCall(POST(postUrl, headersBuilder(), tokenBody)).execute()
                .parseAs<TokenResponse>()

            println("✅ Token received: ${tokenParsed.token_type} ${tokenParsed.access_token}")

            val idHeaders = headersBuilder {
                set("Accept", "application/json, text/plain, */*")
                add("Authorization", "${tokenParsed.token_type} ${tokenParsed.access_token}")
            }

            val idData = """
                {
                   "query":"query playerPasswordQuery(${'$'}videoId:String!,${'$'}password:String!){video(xid:${'$'}videoId,password:${'$'}password){id xid}}",
                   "variables":{
                      "videoId":"$videoId",
                      "password":"$password"
                   }
                }
            """.trimIndent().toRequestBody("application/json".toMediaType())

            println("🔎 Fetching protected video metadata...")

            val idParsed = client.newCall(POST(GRAPHQL_URL, idHeaders, idData)).execute()
                .parseAs<ProtectedResponse>().data.video

            println("🎬 Real video ID: ${idParsed.xid}")

            val dmvk = htmlString.substringAfter("\"dmvk\":\"").substringBefore('"')

            val getVideoIdUrl = "$DAILYMOTION_URL/player/metadata/video/${idParsed.xid}?embedder=${"$baseUrl/"}&locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"

            val getVideoIdHeaders = headersBuilder {
                add("Cookie", "dmvk=$dmvk; ts=$ts; v1st=$v1st; usprivacy=1---; client_token=${tokenParsed.access_token}")
                set("Referer", url)
            }

            val parsed = client.newCall(GET(getVideoIdUrl, getVideoIdHeaders)).execute()
                .parseAs<DailyQuality>()

            return videosFromDailyResponse(parsed, prefix, getVideoIdHeaders)
        } catch (e: Exception) {
            println("❗ Error fetching protected video: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun videosFromDailyResponse(parsed: DailyQuality, prefix: String, playlistHeaders: Headers? = null): List<Video> {
        val masterUrl = parsed.qualities?.auto?.firstOrNull()?.url
        if (masterUrl == null) {
            println("⚠️ Master URL missing in qualities.")
            return emptyList()
        }

        val subtitleList = parsed.subtitles?.data?.map {
            println("📝 Subtitle track: ${it.label} -> ${it.urls.firstOrNull()}")
            Track(it.urls.first(), it.label)
        } ?: emptyList()

        val masterHeaders = playlistHeaders ?: headersBuilder()

        println("📡 Extracting HLS playlist from: $masterUrl")

        return playlistUtils.extractFromHls(
            masterUrl,
            masterHeadersGen = { _, _ -> masterHeaders },
            subtitleList = subtitleList,
            videoNameGen = { "$prefix$it" },
        )
    }
}
