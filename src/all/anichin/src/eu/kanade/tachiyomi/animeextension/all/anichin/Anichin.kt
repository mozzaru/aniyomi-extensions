package eu.kanade.tachiyomi.animeextension.all.anichin

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.anichin.extractors.VidstreamingExtractor
import eu.kanade.tachiyomi.animeextension.all.anichin.extractors.YouTubeExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.asJsoup
import eu.kanade.tachiyomi.source.model.SEpisode
import okhttp3.Response

class Anichin : AnimeStream(
    "all",
    "Anichin",
    "https://anichin.club",
) {
    override val id = 4620219025406449665

    // ============================ Video Links =============================
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val vidstreamingExtractor by lazy { VidstreamingExtractor(client) }
    private val youTubeExtractor by lazy { YouTubeExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("ok.ru") -> okruExtractor.videosFromUrl(url, prefix)
            url.contains("dailymotion") -> dailymotionExtractor.videosFromUrl(url, prefix)
            url.contains("https://dood") -> doodExtractor.videosFromUrl(url, name)
            url.contains("gdriveplayer") -> {
                val gdriveHeaders = headersBuilder()
                    .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .add("Referer", "$baseUrl/")
                    .build()
                gdrivePlayerExtractor.videosFromUrl(url, name, gdriveHeaders)
            }
            url.contains("youtube.com") -> youTubeExtractor.videosFromUrl(url, prefix)
            url.contains("vidstreaming") -> vidstreamingExtractor.videosFromUrl(url, prefix)
            else -> emptyList()
        }
    }

    // ========================== Episode List ==========================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        document.select("div.tab-pane, div#content, div#movie").forEach { section ->
            section.select("li.wp-manga-chapter > a").forEach { element ->
                episodes.add(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(element.attr("href"))
                        name = element.text().trim()
                    },
                )
            }
        }

        // Fallback jika tidak ada tab-pane
        if (episodes.isEmpty()) {
            document.select("li.wp-manga-chapter > a").forEach { element ->
                episodes.add(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(element.attr("href"))
                        name = element.text().trim()
                    },
                )
            }
        }

        return episodes.reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_VALUES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val language = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(language, true) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_TITLE = "Preferred Video Language"
        private const val PREF_LANG_DEFAULT = "All Sub"
        private val PREF_LANG_VALUES = arrayOf(
            "All Sub", "Arabic", "English", "German", "Indonesia", "Italian",
            "Polish", "Portuguese", "Spanish", "Thai", "Turkish",
        )
    }
}
