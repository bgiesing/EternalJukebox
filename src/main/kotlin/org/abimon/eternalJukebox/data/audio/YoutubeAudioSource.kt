package org.abimon.eternalJukebox.data.audio

import com.fasterxml.jackson.databind.DatabindException
import com.github.kittinunf.fuel.Fuel
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.*
import org.abimon.eternalJukebox.*
import org.abimon.eternalJukebox.objects.*
import org.abimon.visi.io.FileDataSource
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

object YoutubeAudioSource : IAudioSource, CoroutineScope {
    private val logger: Logger = LoggerFactory.getLogger("YoutubeAudioSource")

    override val coroutineContext: CoroutineContext =
        SupervisorJob(EternalJukebox.coroutineContext[Job]) +
                CoroutineName("YoutubeAudioSource") +
                LogCoroutineExceptionHandler(logger)

    @Suppress("JoinDeclarationAndAssignment")
    private val apiKey: String?
    private val uuid: String
        get() = UUID.randomUUID().toString()
    val format: String
    val command: List<String>

    private val newPipeService = ServiceList.YouTube
    val mimes = mapOf(
        "m4a" to "audio/m4a", "aac" to "audio/aac", "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "wav" to "audio/wav"
    )

    @Suppress("HttpUrlsUsage") // We are not going to pay for the last fallback endpoint
    private val regionCodeEndpoints = listOf(
        "https://ipapi.co/country_code",
        "https://ipwho.is/?fields=country_code&output=csv",
        "http://ip-api.com/line?fields=countryCode"
    )
    private const val VIDEO_LINK_PREFIX = "https://youtu.be/"
    private const val MAX_API_RESULTS = 10
    private const val MAX_NEWPIPE_RESULTS = 10
    private var regionCode: String = "US"

    private val hitQuota = AtomicLong(-1)
    private val QUOTA_TIMEOUT = TimeUnit.MILLISECONDS.convert(10, TimeUnit.MINUTES)

    override suspend fun provide(info: JukeboxInfo, context: RoutingContext): Boolean {
        logger.trace("[{}] Attempting to provide audio for {}", context.clientInfo.userUID, info.id)

        var youTubeUrl: String? = null
        val queryText = "${info.artist} - ${info.title}"

        if (hitQuota.get() != -1L && (Instant.now().toEpochMilli() - hitQuota.get()) >= QUOTA_TIMEOUT) {
            hitQuota.set(-1)
        }

        if (apiKey != null && hitQuota.get() == -1L) {
            val foundVideoIds = getSearchItemsFromApiSearch(queryText, context.clientInfo.userUID).mapNotNull { it.id.videoId }
            if (foundVideoIds.isNotEmpty()) {
                val videoDetails = getMultiContentDetailsWithKey(foundVideoIds, context.clientInfo.userUID)

                videoDetails.minByOrNull { abs(info.duration - it.contentDetails.duration.toMillis()) }
                    ?.let { youTubeUrl = "${VIDEO_LINK_PREFIX}${it.id}" }
            }

            if (youTubeUrl == null) {
                logger.warn("[${context.clientInfo.userUID}] Searches for \"$queryText\" using YouTube Data API v3 turned up nothing")
            }
        }
        if (youTubeUrl == null) {
            val infoItems = getInfoItemsFromNewPipeSearch(queryText)

            infoItems.minByOrNull { abs(info.duration - TimeUnit.SECONDS.toMillis(it.duration)) }
                ?.let { youTubeUrl = it.url } ?: run {
                logger.error("[${context.clientInfo.userUID}] Searches for \"$queryText\" using NewPipeExtractor turned up nothing")
            }
        }

        if (youTubeUrl == null) return false
        logger.trace(
            "[{}] Settled on {}", context.clientInfo.userUID, youTubeUrl
        )

        val tmpFile = File("$uuid.tmp")
        val tmpLog = File("${info.id}-$uuid.log")
        val ffmpegLog = File("${info.id}-$uuid.log")
        val endGoalTmp = File(tmpFile.absolutePath.replace(".tmp", ".tmp.$format"))

        try {
            withContext(Dispatchers.IO) {
                val cmd = ArrayList(command).apply {
                    add(youTubeUrl)
                    add(tmpFile.absolutePath)
                    add(format)
                }
                logger.debug(cmd.joinToString(" "))
                val downloadProcess =
                    ProcessBuilder().command(cmd).redirectErrorStream(true).redirectOutput(tmpLog).start()

                if (!downloadProcess.waitFor(90, TimeUnit.SECONDS)) {
                    downloadProcess.destroyForcibly().waitFor()
                    logger.error(
                        "[{}] Forcibly destroyed the download process for {}", context.clientInfo.userUID, youTubeUrl
                    )
                }
            }

            if (!endGoalTmp.exists()) {
                logger.warn(
                    "[{}] {} does not exist, attempting to convert with ffmpeg", context.clientInfo.userUID, endGoalTmp
                )

                if (!tmpFile.exists()) {
                    logger.error("[{}] {} does not exist, what happened?", context.clientInfo.userUID, tmpFile)
                    return false
                }

                if (MediaWrapper.ffmpeg.installed) {
                    if (withContext(Dispatchers.IO) { !MediaWrapper.ffmpeg.convert(tmpFile, endGoalTmp, ffmpegLog) }) {
                        logger.error(
                            "[{}] Failed to convert {} to {}. Check {}",
                            context.clientInfo.userUID,
                            tmpFile,
                            endGoalTmp,
                            ffmpegLog.name
                        )
                        return false
                    }

                    if (!endGoalTmp.exists()) {
                        logger.error(
                            "[{}] {} does not exist, check {}",
                            context.clientInfo.userUID,
                            endGoalTmp,
                            ffmpegLog.name
                        )
                        return false
                    }
                } else {
                    logger.debug("[{}] ffmpeg not installed, nothing we can do", context.clientInfo.userUID)
                }
            }

            withContext(Dispatchers.IO) {
                val videoIDRegex = Regex("Video ID: ([\\w-]{11})")
                var videoId: String? = null
                tmpLog.forEachLine { line: String ->
                    val match = videoIDRegex.find(line)
                    if (match != null) {
                        videoId = match.groupValues[1]
                    }
                }
                if (videoId != null) {
                    logger.debug("Storing Location from yt-dlp")
                    EternalJukebox.database.storeAudioLocation(info.id, VIDEO_LINK_PREFIX + videoId, context.clientInfo)
                }
                endGoalTmp.useThenDelete {
                    EternalJukebox.storage.store(
                        "${info.id}.$format",
                        EnumStorageType.AUDIO,
                        FileDataSource(it),
                        mimes[format] ?: "audio/mpeg",
                        context.clientInfo
                    )
                }
            }

            return EternalJukebox.storage.safeProvide("${info.id}.$format", EnumStorageType.AUDIO, context)
        } finally {
            tmpFile.guaranteeDelete()
            File(tmpFile.absolutePath + ".part").guaranteeDelete()
            withContext(Dispatchers.IO) {
                tmpLog.useThenDelete {
                    EternalJukebox.storage.store(
                        it.name, EnumStorageType.LOG, FileDataSource(it), "text/plain", context.clientInfo
                    )
                }
                ffmpegLog.useThenDelete {
                    EternalJukebox.storage.store(
                        it.name, EnumStorageType.LOG, FileDataSource(it), "text/plain", context.clientInfo
                    )
                }
                endGoalTmp.useThenDelete {
                    EternalJukebox.storage.store(
                        "${info.id}.$format",
                        EnumStorageType.AUDIO,
                        FileDataSource(it),
                        mimes[format] ?: "audio/mpeg",
                        context.clientInfo
                    )
                }
            }
        }
    }

    override suspend fun provideLocation(info: JukeboxInfo, clientInfo: ClientInfo?): URL? {
        val dbLocation =
            withContext(Dispatchers.IO) { EternalJukebox.database.provideAudioLocation(info.id, clientInfo) }

        if (dbLocation != null) {
            logger.trace("[{}] Using cached location for {}", clientInfo?.userUID, info.id)
            return withContext(Dispatchers.IO) { URL(dbLocation) }
        }
        return null
    }

    private fun getMultiContentDetailsWithKey(ids: List<String>, userUID: String? = null): List<YoutubeContentItem> {
        val (result, error) = Fuel.get(
            "https://www.googleapis.com/youtube/v3/videos", listOf(
                "part" to "contentDetails,snippet",
                "id" to ids.joinToString(","),
                "key" to (apiKey ?: return emptyList())
            )
        ).header("User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.11; rv:44.0) Gecko/20100101 Firefox/44.0")
            .responseString()
            .third

        if (error != null) {
            if (error.response.statusCode == 403) {
                println("Hit quota!")
                hitQuota.set(Instant.now().toEpochMilli())
            }
            return emptyList()
        }

        try {
            return EternalJukebox.jsonMapper.readValue(result, YoutubeContentResults::class.java).items
        } catch (de: DatabindException) {
            logger.error("[{}] Failed to parse multi-content details from YouTube Data API v3", userUID, de)
            return emptyList()
        }
    }

    private fun getSearchItemsFromApiSearch(query: String, userUID: String? = null): List<YoutubeSearchItem> {
        val (result, error) = Fuel.get(
            "https://www.googleapis.com/youtube/v3/search", listOf(
                "part" to "snippet",
                "q" to query,
                "maxResults" to "$MAX_API_RESULTS",
                "key" to (apiKey ?: return emptyList()),
                "type" to "video",
                "regionCode" to regionCode
            )
        ).header("User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.11; rv:44.0) Gecko/20100101 Firefox/44.0")
            .responseString()
            .third

        if (error != null) {
            if (error.response.statusCode == 403) {
                println("Hit quota!")
                hitQuota.set(Instant.now().toEpochMilli())
            }
            return emptyList()
        }

        try {
            return EternalJukebox.jsonMapper.readValue(result, YoutubeSearchResults::class.java).items
        } catch (de: DatabindException) {
            logger.error("[{}] Failed to parse search results from YouTube Data API v3", userUID, de)
            return emptyList()
        }
    }

    private fun getInfoItemsFromNewPipeSearch(query: String): List<StreamInfoItem> {
        val searchQuery = newPipeService
            .searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")

        val searchInfo: SearchInfo
        try {
            searchInfo = SearchInfo.getInfo(newPipeService, searchQuery)
        } catch (e: Exception) {
            logger.error("Failed to acquire search results for $query", e)
            return mutableListOf()
        }
        val infoItems = searchInfo.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM }
            .toMutableList()

        var nextPage = searchInfo.nextPage
        try {
            while (infoItems.size < MAX_NEWPIPE_RESULTS && Page.isValid(nextPage)) {
                val moreItems: ListExtractor.InfoItemsPage<InfoItem> = SearchInfo.getMoreItems(
                    newPipeService,
                    searchQuery, nextPage
                )
                infoItems.addAll(
                    moreItems.items
                        .filterIsInstance<StreamInfoItem>()
                        .filter { it.streamType == StreamType.VIDEO_STREAM })
                nextPage = moreItems.nextPage
            }
        } catch (e: Exception) {
            logger.warn("Failed to acquire additional search pages for $query", e)
        }
        return infoItems.take(MAX_NEWPIPE_RESULTS)
    }

    private fun setRegionCodeForIP() {
        for (endpoint in regionCodeEndpoints) {
            val (result, error) = Fuel.get(endpoint).responseString().third
            if (error == null) {
                result?.trim()?.takeIf { it.matches(Regex("^[A-Za-z]{2}$")) }?.let {
                    regionCode = it
                    return
                }
            }
            logger.info("Failed to acquire region code from $endpoint", error)
        }
        logger.warn("Failed to acquire region code for IP. Falling back to $regionCode")
    }

    init {
        apiKey = (EternalJukebox.config.audioSourceOptions["API_KEY"]
            ?: EternalJukebox.config.audioSourceOptions["apiKey"]) as? String
        format = (EternalJukebox.config.audioSourceOptions["AUDIO_FORMAT"]
            ?: EternalJukebox.config.audioSourceOptions["audioFormat"]) as? String ?: "m4a"
        command = ((EternalJukebox.config.audioSourceOptions["AUDIO_COMMAND"]
            ?: EternalJukebox.config.audioSourceOptions["audioCommand"]) as? List<*>)?.map { "$it" }
            ?: ((EternalJukebox.config.audioSourceOptions["AUDIO_COMMAND"]
                ?: EternalJukebox.config.audioSourceOptions["audioCommand"]) as? String)?.split("\\s+".toRegex())
                    ?: if (System.getProperty("os.name").lowercase().contains("windows")
            ) listOf("yt.bat") else listOf("sh", "yt.sh")

        if (apiKey == null) {
            logger.warn("Warning: No API key provided. Only NewPipeExtractor will be used to find audio sources.")
        } else {
            launch(Dispatchers.IO) { setRegionCodeForIP() }
        }
        NewPipe.init(DownloaderImpl.init())
    }
}
