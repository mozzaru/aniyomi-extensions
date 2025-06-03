package eu.kanade.tachiyomi.animeextension.all.anichin

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.anichin.extractors.VidstreamingExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class Anichin : AnimeStream(
    "all",
    "Anichin",
    "https://anichin.club",
) {
    override val id = 4620219025406449666

    // ============================ Video Links =============================
    private val dailymotionExtractor: DailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val vidstreamingExtractor: VidstreamingExtractor by lazy { VidstreamingExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("dailymotion") -> dailymotionExtractor.videosFromUrl(url, prefix)
            url.contains("vidstreaming") -> vidstreamingExtractor.videosFromUrl(url, prefix)
            else -> emptyList()
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences

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
