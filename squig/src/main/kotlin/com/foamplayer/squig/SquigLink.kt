package com.foamplayer.squig

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * Minimal squig.link client for the Auto EQ flow.
 *
 * The platform's data layout, verified against kazi.squig.link and the
 * squig.link super*review instance:
 *  - [SQUIG_INDEX_URL]/squigsites.json: registry of every community instance
 *    (username, urlType, per-instance form-factor databases).
 *  - {db}/config.js: DIR (data directory, "data/"), data_format, and the
 *    `targets = [{ type, files }]` preference targets. Optional: some
 *    databases (e.g. Earphones Archive) ship no config.js, in which case the
 *    data directory defaults to "data/" and the preference targets to empty.
 *  - {db}{DIR}phone_book.json: brand → model → measurement file catalog.
 *  - Measurement files: {DIR}{file} L.txt / {file} R.txt (REW text format,
 *    comment lines start with '*'); targets are a single {file} Target.txt;
 *    multi-sample rigs number their channels, e.g. "{file} L1.txt".
 */
/** Shared JSON instance for the module's parse functions and the client. */
private val json = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

object SquigLink {

    private const val MAX_CACHED_DATABASES = 8
    private const val MAX_CACHED_CURVES = 24

    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
            defaultRequest {
                url(SQUIG_INDEX_URL)
                headers { append("User-Agent", "FoamPlayer AutoEQ (squig.link client)") }
            }
            expectSuccess = true
        }
    }

    private val cacheMutex = Mutex()
    private var cachedSites: List<SquigSite>? = null
    private val cachedDatabases = LinkedHashMap<String, SquigDatabase>()
    private val cachedCurves = LinkedHashMap<String, FrequencyResponseCurve>()

    /** All squig.link community databases, from the shared instance index. */
    suspend fun sites(): List<SquigSite> {
        cacheMutex.withLock { cachedSites }?.let { return it }
        val text = client.get("$SQUIG_INDEX_URL/squigsites.json").bodyAsText()
        val parsed = parseSites(text)
        if (parsed.isNotEmpty()) {
            cacheMutex.withLock { cachedSites = parsed }
        }
        return parsed
    }

    /**
     * Loads a database: the device catalog from phone_book.json, the
     * preference targets from config.js, and the resolved measurement
     * directory URL. config.js is optional — databases without one default
     * the data directory to "data/" and the preference targets to empty.
     */
    suspend fun database(site: SquigSite, db: SquigDb): SquigDatabase {
        val key = "${site.baseUrl}|${db.folder}"
        cacheMutex.withLock { cachedDatabases[key] }?.let { return it }

        val dbUrl = site.baseUrl +
                "/" + db.folder.trim('/') + "/"
        val configJs = fetchText("$dbUrl/config.js")
        val dataUrl = dbUrl + toDirPath(configJs?.let(::parseDataDir) ?: "data/")
        val phoneBook = fetchText("$dataUrl/phone_book.json")
            ?.let { parsePhoneBook(it) }
            .orEmpty()

        val database = SquigDatabase(
            site = site,
            db = db,
            dataUrl = dataUrl,
            numSamples = configJs?.let(::parseNumSamples) ?: 1,
            devices = phoneBook,
            targets = configJs?.let { parseConfigTargets(stripLineComments(it)) }.orEmpty(),
        )
        cacheMutex.withLock {
            cachedDatabases[key] = database
            if (cachedDatabases.size > MAX_CACHED_DATABASES) {
                cachedDatabases.remove(cachedDatabases.keys.first())
            }
        }
        return database
    }

    /** Fetches the measurement of [device] and averages all present channels. */
    suspend fun measurement(database: SquigDatabase, device: SquigDevice): FrequencyResponseCurve {
        val key = "${database.dataUrl}|${device.file}"
        cacheMutex.withLock { cachedCurves[key] }?.let { return it }

        val curves = measurementUrls(database, device)
            .mapNotNull { url -> fetchText(url)?.let { parseFrequencyResponse(it) } }
            .filterNot { it.isEmpty }
        require(curves.isNotEmpty()) {
            "No measurement found for \"${device.displayName}\" in ${database.site.name} (${database.db.type})"
        }

        val averaged = FrequencyResponseCurve.average(curves)
        cacheMutex.withLock {
            cachedCurves[key] = averaged
            if (cachedCurves.size > MAX_CACHED_CURVES) {
                cachedCurves.remove(cachedCurves.keys.first())
            }
        }
        return averaged
    }

    /**
     * Target curves are single files without a channel suffix; devices carry
     * L/R channel files (numbered when the rig stores multiple samples).
     */
    private fun measurementUrls(database: SquigDatabase, device: SquigDevice): List<String> {
        val dir = database.dataUrl
        if (device.isTarget) return listOf("$dir${encodePath(device.file)}.txt")
        val samples: IntRange? =
            if (database.numSamples > 1) 1..database.numSamples else null
        return buildList {
            fun addChannel(channel: Char) {
                val sampleSuffixes = samples?.map { "$channel$it" } ?: listOf("$channel")
                for (suffix in sampleSuffixes) {
                    add("$dir${encodePath("${device.file} $suffix")}.txt")
                }
            }
            addChannel('L')
            addChannel('R')
        }
    }

    /** Missing files (a missing R channel, a dead entry) are null, not errors. */
    private suspend fun fetchText(url: String): String? = try {
        client.get(url).bodyAsText()
    } catch (_: ClientRequestException) {
        null
    }

    private fun encodePath(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

    private fun toDirPath(dir: String): String =
        when {
            dir.isEmpty() -> ""
            dir.startsWith("/") -> if (dir.endsWith("/")) dir else "$dir/"
            dir.endsWith("/") -> dir
            else -> "$dir/"
        }
}

// ---- JSON DTOs ----------------------------------------------------------------

@Serializable
private data class SiteDto(
    val username: String? = null,
    val name: String? = null,
    val urlType: String? = null,
    val altDomain: String? = null,
    val dbs: List<DbDto> = emptyList(),
)

@Serializable
private data class DbDto(
    val type: String? = null,
    val folder: String? = null,
)

@Serializable
private data class PhoneBookDto(
    val name: String? = null,
    val phones: List<PhoneDto> = emptyList(),
)

@Serializable
private data class PhoneDto(
    val name: String? = null,
    val file: JsonElement? = null,
    val reviewScore: String? = null,
)

// ---- Parsing (pure, unit-tested) ----------------------------------------------

/** Parses squigsites.json into [SquigSite]s, dropping entries without dbs. */
internal fun parseSites(text: String): List<SquigSite> {
    val dtos = runCatching {
        json.decodeFromString(ListSerializer(SiteDto.serializer()), text)
    }.getOrDefault(emptyList())
    return dtos.mapNotNull { dto ->
        val username = dto.username?.trim().takeUnless { it.isNullOrEmpty() } ?: return@mapNotNull null
        val dbs = dto.dbs.mapNotNull { db ->
            val type = db.type?.trim().takeUnless { it.isNullOrEmpty() } ?: return@mapNotNull null
            SquigDb(type = type, folder = db.folder?.takeIf { it.isNotBlank() } ?: "/")
        }
        if (dbs.isEmpty()) return@mapNotNull null
        SquigSite(
            username = username,
            name = dto.name?.takeIf { it.isNotBlank() } ?: username,
            urlType = dto.urlType.orEmpty(),
            altDomain = dto.altDomain,
            dbs = dbs,
        )
    }
}

/**
 * Parses phone_book.json (a brand → phones array). A phone's `file` field may
 * be a single name or an array of alternate unit measurements; the first entry
 * is used.
 */
internal fun parsePhoneBook(text: String): List<SquigDevice> {
    val dtos = runCatching {
        json.decodeFromString(ListSerializer(PhoneBookDto.serializer()), text)
    }.getOrDefault(emptyList())
    return dtos.flatMap { brand ->
        val brandName = brand.name?.trim().orEmpty()
        brand.phones.mapNotNull { phone ->
            val model = phone.name?.trim().takeUnless { it.isNullOrEmpty() } ?: return@mapNotNull null
            val file = firstFileName(phone.file) ?: return@mapNotNull null
            SquigDevice(
                brand = brandName,
                model = model,
                file = file,
                reviewScore = phone.reviewScore,
            )
        }
    }
}

private fun firstFileName(file: JsonElement?): String? = when (file) {
    null, is JsonNull -> null
    is JsonPrimitive -> file.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonArray -> file
        .filterIsInstance<JsonPrimitive>()
        .firstNotNullOfOrNull { it.contentOrNull?.takeIf(String::isNotBlank) }
    else -> null
}

/** Strips whole-line `//` comments so dead config entries aren't parsed. */
internal fun stripLineComments(text: String): String =
    text.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

/**
 * Parses the `targets = [{ type, files }]` array from config.js. The graphtool
 * loads each entry as `{file} Target.txt`, so the channel-less file name gets
 * the same suffix here.
 */
internal fun parseConfigTargets(configJs: String): List<SquigDevice> {
    val targets = ArrayList<SquigDevice>()
    val groupRegex = Regex("""type\s*:\s*"([^"]*)"\s*,\s*files\s*:\s*\[([^\]]*)\]""")
    val fileRegex = Regex("\"([^\"]+)\"")
    for (group in groupRegex.findAll(configJs)) {
        val typeName = group.groupValues[1].trim().ifBlank { "Targets" }
        for (fileMatch in fileRegex.findAll(group.groupValues[2])) {
            val file = fileMatch.groupValues[1].trim()
            if (file.isEmpty()) continue
            targets += SquigDevice(
                brand = typeName,
                model = file,
                file = "$file Target",
                isTarget = true,
            )
        }
    }
    return targets
}

/** Reads `DIR = "data/"` from config.js, defaulting to "data/". */
internal fun parseDataDir(configJs: String): String =
    Regex("""DIR\s*=\s*"([^"]*)"""").find(configJs)
        ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        ?: "data/"

/** Reads `num_samples = N` from config.js, defaulting to 1. */
internal fun parseNumSamples(configJs: String): Int =
    Regex("""num_samples\s*=\s*(\d+)""").find(configJs)
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        ?: 1

/**
 * Parses a REW-format measurement file: '*' comment header lines followed by
 * `freq, dB` rows. Separators can be comma, tab or whitespace, which keeps
 * other graph-tool export formats working too.
 */
internal fun parseFrequencyResponse(text: String): FrequencyResponseCurve {
    val points = ArrayList<FrequencyResponseCurve.Point>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line[0] !in '0'..'9' && line[0] != '-' && line[0] != '+') continue
        val parts = line.split(',', '\t', ' ').map { it.trim() }.filter(String::isNotEmpty)
        if (parts.size < 2) continue
        val frequency = parts[0].toFloatOrNull() ?: continue
        val db = parts[1].toFloatOrNull() ?: continue
        if (frequency <= 0f || frequency.isNaN() || db.isNaN()) continue
        points += FrequencyResponseCurve.Point(frequency, db)
    }
    points.sortBy { it.frequencyHz }
    return if (points.isEmpty()) FrequencyResponseCurve.EMPTY else FrequencyResponseCurve(points)
}
