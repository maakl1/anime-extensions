package eu.kanade.tachiyomi.animeextension.en.anikage

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.isNotEmpty

@Source
abstract class Anikage :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest: Boolean = true

    private val browseApiUrl: HttpUrl get() = "$baseUrl/api/media/anime/browse".toHttpUrl()

    override val disableRelatedAnimesBySearch = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    override val client = network.client.newBuilder()
        .rateLimit(3) { it.host == baseUrl.toHttpUrl().host }
        .build()

    private val preferences by getPreferencesLazy()

    private val context: Application by injectLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val requestUrl = browseApiUrl
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("sort", "popularity")
        requestUrl.addQueryParameter("limit", "25")
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", "true")
        }

        return buildGet(requestUrl.build())
    }

    override fun popularAnimeParse(response: Response) = parseAnime(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val requestUrl = browseApiUrl
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("sort", "updated")
        requestUrl.addQueryParameter("limit", "25")
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", true.toString())
        }

        return buildGet(requestUrl.build())
    }

    override fun latestUpdatesParse(response: Response) = parseAnime(response)

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val searchParams = Filters.getSearchParameters(filters)
        val requestUrl = browseApiUrl
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("limit", "25")
        if (query != "") requestUrl.addQueryParameter("q", query)
        if (searchParams.sortBy.isNotEmpty()) {
            requestUrl.addQueryParameter("sort", searchParams.sortBy)
        }
        if (searchParams.status != "ALL") {
            requestUrl.addQueryParameter("status", searchParams.status)
        }
        if (searchParams.season != "ALL") {
            requestUrl.addQueryParameter("season", searchParams.season)
        }
        if (searchParams.origin != "ALL") {
            requestUrl.addQueryParameter("country", searchParams.origin)
        }
        if (searchParams.types != "ALL") {
            requestUrl.addQueryParameter("format", searchParams.types)
        }
        if (searchParams.releaseYear != "ALL") {
            requestUrl.addQueryParameter("yearMin", searchParams.releaseYear)
            requestUrl.addQueryParameter("yearMax", searchParams.releaseYear)
        }
        if (searchParams.genres.isNotEmpty()) {
            requestUrl.addQueryParameter("genres", searchParams.genres.joinToString(","))
        }
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", true.toString())
        }

        return buildGet(requestUrl.build())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnime(response)

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")
        return buildGet("$baseUrl/api/media/anime/$animeId".toHttpUrl())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val info = response.parseAs<AnimeInfoResponse>().anime

        val titleName = if (preferences.titleStyle == "english") {
            info.title.english ?: info.title.romaji!!
        } else {
            info.title.romaji!!
        }
        val studioNames = info.studios
            .filter { it.isAnimationStudio }
            .ifEmpty { info.studios }
            .map { it.name }

        return SAnime.create().apply {
            title = titleName
            description = info.description?.cleanDescription()
            thumbnail_url = info.coverImage?.let { it.extraLarge ?: it.large ?: it.medium }
            author = studioNames.joinToString()
            update_strategy = if (info.status == "FINISHED") {
                AnimeUpdateStrategy.ONLY_FETCH_ONCE
            } else {
                AnimeUpdateStrategy.ALWAYS_UPDATE
            }
            status = when (info.status) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                "CANCELLED" -> SAnime.CANCELLED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ========================== Related Anime =============================

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")
        return buildGet("$baseUrl/api/media/anime/$animeId".toHttpUrl())
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val info = response.parseAs<AnimeInfoResponse>().anime
        val currentSlug = info.slug

        fun RelatedAnimeTitle.preferred() = if (preferences.titleStyle == "english") {
            english ?: romaji
        } else {
            romaji ?: english
        }

        fun relatedStatus(status: String?) = when (status) {
            "FINISHED" -> SAnime.COMPLETED
            "RELEASING" -> SAnime.ONGOING
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

        return buildList {
            info.relations.mapNotNull { rel ->
                if (rel.slug == currentSlug) return@mapNotNull null
                val title = rel.title.preferred() ?: return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain("/anime/info/${rel.slug}")
                    this.title = title
                    thumbnail_url = rel.coverImage
                    status = relatedStatus(rel.status)
                    genre = listOfNotNull(rel.format, rel.relationType).joinToString()
                }
            }.let(::addAll)

            info.recommendations.mapNotNull { rec ->
                if (rec.slug == currentSlug) return@mapNotNull null
                val title = rec.title.preferred() ?: return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain("/anime/info/${rec.slug}")
                    this.title = title
                    thumbnail_url = rec.coverImage
                    status = relatedStatus(rec.status)
                    genre = rec.format
                }
            }.let(::addAll)
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")
        val getHeaders = headersBuilder()
            .add("Referer", "$baseUrl${anime.url}")
            .add("Origin", baseUrl)
            .add("Accept", "*/*")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .build()

        return GET(animeId.animeEpisodeBuilder(), headers = getHeaders)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")

        val episodesData = client.newCall(episodeListRequest(anime))
            .awaitSuccess()
            .parseAs<List<EpisodeResult>>()

        val episode = episodesData.reversed().map {
            SEpisode.create().apply {
                episode_number = it.number.toFloat()
                name = if (!it.title.isNullOrBlank()) {
                    "Episode ${it.number} - ${it.title}"
                } else {
                    "Episode ${it.number}"
                }
                date_upload = DATE_FORMAT.tryParse(it.airDate)
                setUrlWithoutDomain(
                    animeEpisodeUrlFormat(
                        animeId,
                        it.number,
                    ),
                )
            }
        }
        return episode
    }

    // ============================== Hosters ===============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    /** Reset per episode so an API change toasts once, not once per hoster. */
    private val apiChangeToasted = AtomicBoolean(false)

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        apiChangeToasted.set(false)
        // The site reports which servers exist for this episode. The static list is
        // only used when that request fails; a successful answer is authoritative.
        val servers = getEpisodeServers(episode)
        val offered: Map<String, List<String>> = if (servers == null) {
            PROVIDERS.associateWith { AUDIO_TYPES }
        } else {
            servers.groupBy({ it.providerId }, { it.subTypes })
                .mapValues { (_, types) -> types.flatten().distinct() }
        }

        val candidates = AUDIO_TYPES.flatMap { type ->
            offered.filterValues { type in it }.keys.map { provider -> provider to type }
        }
        if (candidates.isEmpty()) {
            // The site answered but has nothing for this episode; the app reports that.
            return emptyList()
        }

        val excludedServers = preferences.excludedServers
        val excludedTypes = preferences.excludedTypes
        val active = candidates.filter { (provider, type) -> provider !in excludedServers && type !in excludedTypes }
        if (active.isEmpty()) {
            val offeredNames = candidates.map { it.first }.distinct().joinToString()
            throw Exception("All servers offering this episode ($offeredNames) are excluded in the extension settings")
        }

        return active.map { (provider, type) ->
            Hoster(
                hosterUrl = "$baseUrl${episode.url}?lang=$type&provider=$provider",
                hosterName = "$provider (${type.replaceFirstChar { it.uppercase() }})",
                internalData = "$provider|$type",
                lazy = provider in ON_DEMAND_PROVIDERS,
            )
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferredType = preferences.subOrDub
        return sortedWith(
            compareByDescending<Hoster> { it.audioType() == preferredType }
                .thenByDescending { it.provider() == preferredServerFor(it.audioType()) },
        )
    }

    private fun Hoster.provider(): String = internalData.substringBefore("|")

    private fun Hoster.audioType(): String = internalData.substringAfter("|")

    private fun preferredServerFor(type: String): String = if (type == "dub") preferences.dubSource else preferences.subSource

    // =========================== Video Links ==============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> = try {
        val data = client.get(hoster.hosterUrl, headers).parseAs<EpisodeSource>()
        if (data.sources.isEmpty()) {
            emptyList()
        } else {
            val tracks = data.subtitles.mapIndexed { index, subtitle ->
                val name = subtitle.label ?: "${subtitle.kind ?: "Subtitle"} ${index + 1}"
                Track("https://og.bakayaro.live/stream/${subtitle.file}", name)
            }
            val playlistUtils = PlaylistUtils(client, headers)

            // One bad source must not sink the hoster, but a hoster whose every source
            // failed is an error, so the app marks it as such instead of showing nothing.
            val failures = Collections.synchronizedList(mutableListOf<Exception>())
            val videos = data.sources.parallelCatchingFlatMap { source ->
                try {
                    val url = source.episodeSourceUrl()
                    if (source.isM3U8 == true && isHlsPlaylist(url)) {
                        playlistUtils.extractFromHls(
                            playlistUrl = url,
                            masterHeaders = headers,
                            videoHeaders = headers,
                            videoNameGen = { "${source.quality} - $it" },
                            subtitleList = tracks,
                        )
                    } else {
                        listOf(
                            Video(
                                videoUrl = url,
                                videoTitle = source.quality,
                                headers = headers,
                                subtitleTracks = tracks,
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "${hoster.hosterName} ${source.quality} failed", e)
                    failures += e
                    emptyList()
                }
            }.sortedByQuality()
            if (videos.isEmpty() && failures.isNotEmpty()) throw failures.first()

            val timestamps = buildList {
                data.intro?.toTimeStamp("Intro", ChapterType.Opening)?.let(::add)
                data.outro?.toTimeStamp("Outro", ChapterType.Ending)?.let(::add)
            }
            val withChapters = if (timestamps.isEmpty()) videos else videos.map { it.copy(timestamps = timestamps) }

            if (isPreferredHoster(hoster)) {
                withChapters.mapIndexed { index, video -> if (index == 0) video.copy(preferred = true) else video }
            } else {
                withChapters
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(name, "${hoster.hosterName} failed", e)
        if (e is SerializationException && apiChangeToasted.compareAndSet(false, true)) {
            showToast("Anikage: the site sent an unexpected response")
        }
        throw e
    }

    private fun TimeRange.toTimeStamp(label: String, type: ChapterType): TimeStamp? {
        val s = start ?: return null
        val e = end ?: return null
        return if (e > s) TimeStamp(s, e, label, type) else null
    }

    private fun isPreferredHoster(hoster: Hoster): Boolean = hoster.audioType() == preferences.subOrDub && hoster.provider() == preferredServerFor(hoster.audioType())

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun List<Video>.sortVideos(): List<Video> = sortedByQuality()

    private fun List<Video>.sortedByQuality(): List<Video> {
        val quality = preferences.quality
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.videoTitle.contains(it) } },
        )
    }

    private fun serversListUrl(episode: SEpisode): String = "$baseUrl${episode.url}".substringBefore("/sources") + "/servers"

    /**
     * True when [url] serves an HLS playlist. Reads only the first bytes: the site marks
     * some direct MP4 files as m3u8, and PlaylistUtils would otherwise download the whole
     * file to find out. A non-2xx answer is an error for this source.
     */
    private suspend fun isHlsPlaylist(url: String): Boolean = client.get(url, headers, ensureSuccess = false).use { response ->
        if (!response.isSuccessful) throw Exception("HTTP error ${response.code}")
        response.body.source().peek().readUtf8(HLS_HEADER.length.toLong()) == HLS_HEADER
    }

    /** Servers the site reports for the episode, or null when the request fails. */
    private suspend fun getEpisodeServers(episode: SEpisode): List<ServerInfo>? = try {
        client.newCall(GET(serversListUrl(episode), headers))
            .awaitSuccess()
            .parseAs<EpisodeServers>()
            .servers
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(name, "Could not load the server list", e)
        null
    }

    // ============================= Utilities ==============================

    private fun String.cleanDescription(): String = Jsoup.parse(replace(DESCRIPTION_BR_REGEX, "\n"))
        .wholeText()
        .trim()

    private fun String.animeEpisodeBuilder(): String = "$baseUrl/api/media/anime/$this/episodes"

    private fun animeEpisodeUrlFormat(id: String, number: Int): String = "$baseUrl/api/media/anime/$id/episodes/$number/sources"

    private fun parseAnime(response: Response): AnimesPage {
        val jsonData = response.parseAs<AnikageResponse>()

        val animes = jsonData.data.map {
            val id = it.slug
            val titleFormat = preferences.titleStyle
            val titleName = if (titleFormat == "english") {
                it.title.english ?: it.title.romaji!!
            } else {
                it.title.romaji!!
            }
            val coverUrl = it.coverImage?.let { c -> c.extraLarge ?: c.large ?: c.medium }

            SAnime.create().apply {
                setUrlWithoutDomain("/anime/info/$id")
                thumbnail_url = coverUrl
                title = titleName
                description = null
                status = when (it.status) {
                    "FINISHED" -> SAnime.COMPLETED
                    "RELEASING" -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
                update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
                genre = it.genres.joinToString()
            }
        }

        return AnimesPage(animes, jsonData.hasNext)
    }

    private fun buildGet(url: HttpUrl): Request {
        val postHeaders = headers.newBuilder().apply {
            set("Accept", "*/*")
            set("Host", browseApiUrl.host)
            set("Origin", baseUrl)
            set("Referer", "$browseApiUrl/")
        }.build()

        return GET(url, headers = postHeaders)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SITE_TITLE_FORMAT,
            title = "Preferred Title Style",
            entries = listOf("English", "Romaji"),
            entryValues = listOf("english", "romaji"),
            default = PREF_SITE_TITLE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SUB_SOURCE,
            title = "Preferred Sub Server",
            entries = SUB_PROVIDER,
            entryValues = SUB_PROVIDER,
            default = PREF_SUB_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_DUB_SOURCE,
            title = "Preferred Dub Server",
            entries = DUB_PROVIDER,
            entryValues = DUB_PROVIDER,
            default = PREF_DUB_DEFAULT,
            summary = "%s",
        )

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_SERVERS_KEY
            title = "Exclude Servers"
            summary = "Choose which servers you want to exclude"
            entries = SUB_PROVIDER.toTypedArray()
            entryValues = SUB_PROVIDER.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)

        screen.addListPreference(
            key = PREF_ISSUBORDUB_SOURCE,
            title = "Sub or Dub?",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("sub", "dub"),
            default = PREF_ISSUBORDUB_DEFAULT,
            summary = "%s",
        )

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_TYPES_KEY
            title = "Exclude Types"
            summary = "Choose which audio types you want to exclude"
            entries = listOf("Sub", "Dub").toTypedArray()
            entryValues = listOf("sub", "dub").toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)

        screen.addSwitchPreference(
            key = PREF_ADULT_KEY,
            title = "Enable NSFW Content",
            summary = "Show adult content in search results and popular anime",
            default = PREF_ADULT_DEFAULT,
        )
    }

    private val SharedPreferences.titleStyle
        get() = getString(PREF_SITE_TITLE_FORMAT, PREF_SITE_TITLE_DEFAULT)!!

    private val SharedPreferences.isAdult
        get() = getBoolean(PREF_ADULT_KEY, PREF_ADULT_DEFAULT)

    private val SharedPreferences.subOrDub
        get() = getString(PREF_ISSUBORDUB_SOURCE, PREF_ISSUBORDUB_DEFAULT)!!

    private val SharedPreferences.subSource
        get() = getString(PREF_SUB_SOURCE, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.dubSource
        get() = getString(PREF_DUB_SOURCE, PREF_DUB_DEFAULT)!!

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.excludedServers
        get() = getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()).orEmpty()

    private val SharedPreferences.excludedTypes
        get() = getStringSet(PREF_EXCLUDE_TYPES_KEY, emptySet()).orEmpty()

    companion object {
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
        private val DESCRIPTION_BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

        private const val HLS_HEADER = "#EXTM3U"

        private const val PREF_ADULT_KEY = "nsfw"
        private const val PREF_ADULT_DEFAULT = false

        private val AUDIO_TYPES = listOf("sub", "dub")

        // Servers the site currently reports. Used for the settings lists and as the
        // fallback when the server list cannot be fetched.
        private val PROVIDERS = listOf(
            "koto",
            "ken",
            "wave",
            "megg",
            "kage",
            "zen",
        )

        // Loaded only when the user picks them. The stream host answers 403 for kite
        // tokens today, and that 403 makes the app spend ~15 s on a Cloudflare bypass;
        // as a lazy hoster it costs nothing per episode and works again without an
        // update if the site fixes it. Other dead servers are left to fail visibly.
        private val ON_DEMAND_PROVIDERS = setOf("kite")
        private val SUB_PROVIDER = PROVIDERS
        private val DUB_PROVIDER = PROVIDERS

        private const val PREF_SUB_SOURCE = "preferred_sub_source"
        private const val PREF_SUB_DEFAULT = "koto"

        private const val PREF_DUB_SOURCE = "preferred_dub_source"
        private const val PREF_DUB_DEFAULT = "koto"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_EXCLUDE_SERVERS_KEY = "excluded_servers"
        private const val PREF_EXCLUDE_TYPES_KEY = "excluded_types"

        private const val PREF_ISSUBORDUB_SOURCE = "is_sub_or_dub"
        private const val PREF_ISSUBORDUB_DEFAULT = "sub"
        private const val PREF_SITE_TITLE_FORMAT = "title_format"
        private const val PREF_SITE_TITLE_DEFAULT = "english"
    }
}
