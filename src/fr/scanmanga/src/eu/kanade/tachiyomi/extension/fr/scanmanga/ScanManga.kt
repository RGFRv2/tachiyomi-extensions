package eu.kanade.tachiyomi.extension.fr.scanmanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder.encode
import kotlin.random.Random

class ScanManga : ParsedHttpSource() {

    override val name = "Scan-Manga"

    override val baseUrl = "https://www.scan-manga.com"

    override val lang = "fr"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor { chain ->
            val originalCookies = chain.request().header("Cookie") ?: ""
            val newReq = chain
                .request()
                .newBuilder()
                .header("Cookie", "$originalCookies; _ga=GA1.2.${shuffle("123456789")}.${System.currentTimeMillis() / 1000}")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:110.0) Gecko/20100101 Firefox/110.0") // Force WEB User-Agent
                .build()
            chain.proceed(newReq)
        }.build()

    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept-Language", "fr-FR,fr")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/TOP-Manga-Webtoon-24.html", headers)
    }

    override fun popularMangaSelector() = "div.image_manga a[href]"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.select("img").attr("title")
            setUrlWithoutDomain(element.select("a").attr("href"))
            thumbnail_url = element.select("img").attr("data-original")
        }
    }

    override fun popularMangaNextPageSelector(): String? = null

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    override fun latestUpdatesSelector() = "#content_news .listing"

    override fun latestUpdatesFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.select("a.nom_manga").text()
            setUrlWithoutDomain(element.select("a.nom_manga").attr("href"))
            /*thumbnail_url = element.select(".logo_manga img").let {
                if (it.hasAttr("data-original"))
                    it.attr("data-original") else it.attr("src")
            }*/
            // Better not use it, width is too large, which results in terrible image
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // Search
    override fun searchMangaFromElement(element: Element) = throw UnsupportedOperationException("Not used")

    override fun searchMangaNextPageSelector() = throw UnsupportedOperationException("Not used")

    override fun searchMangaParse(response: Response): MangasPage = parseMangaFromJson(response)

    private fun shuffle(s: String?): String {
        val result = StringBuffer(s!!)
        var n = result.length
        while (n > 1) {
            val randomPoint: Int = Random.nextInt(n)
            val randomChar = result[randomPoint]
            result.setCharAt(n - 1, randomChar)
            n--
        }
        return result.toString()
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val query = encode(query, "UTF-8")

        val searchHeaders = headersBuilder()
            .add("Referer", "https://m.scan-manga.com/?po")
            .add("x-requested-with", "XMLHttpRequest")
            .build()

        return GET("https://m.scan-manga.com/qsearchm.json?term=$query", searchHeaders)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response, query)
            }
    }

    private fun searchMangaParse(response: Response, query: String): MangasPage {
        return MangasPage(parseMangaFromJson(response).mangas.filter { it.title.contains(query, ignoreCase = true) }, false)
    }

    private fun parseMangaFromJson(response: Response): MangasPage {
        val jsonRaw = response.body.string().replace("(", "").replace(");", "").replace("\\", "")

        if (jsonRaw.isEmpty()) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val mangaList = json.parseToJsonElement(jsonRaw).jsonArray
            .mapNotNull { jsonElement ->
                jsonElement.jsonArray?.takeIf { it.size > 1 }?.let { innerArray ->
                    SManga.create().apply {
                        title = innerArray[0].jsonPrimitive.content
                        url = innerArray[1].jsonPrimitive.content.replace("https://m.scan-manga.com", "")
                    }
                }
            }

        return MangasPage(mangaList, hasNextPage = false)
    }

    override fun searchMangaSelector() = throw UnsupportedOperationException("Not used")

    // Details
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.select("h2[itemprop=name]").text()
        author = document.select("li[itemprop=author] a").joinToString { it.text() }
        thumbnail_url = document.select("div.cover_volume_manga img.lazy").attr("data-original")
        description = document.select("p[itemprop=description]").text()

        if (!description.isNullOrEmpty() && description!!.last() == ',') {
            description = description!!.dropLast(1)
        }

        if (thumbnail_url!!.isEmpty()) {
            thumbnail_url = document.select("div.image_manga img").attr("src")
        }

        val genres = document.select("a.infoBulle").mapNotNull { aTag ->
            val spanTag = aTag.select("span").firstOrNull()
            val text = aTag.ownText().trim()
            if (text.isNotEmpty() && spanTag != null) {
                val spanText = spanTag.text()
                text.replace(spanText, "").trim()
            } else {
                null
            }
        }

        genre = genres.joinToString()

        status = document.select("div.contenu_texte_fiche_technique ul li").toString().let {
            when {
                it.contains("En cours") -> SManga.ONGOING
                it.contains("Terminé") -> SManga.COMPLETED
                it.contains("En pause") -> SManga.ON_HIATUS
                it.contains("Licencié") -> SManga.LICENSED
                it.contains("Désactivé du site (licenciée)") -> SManga.LICENSED
                it.contains("Abandonné") -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters
    override fun chapterListSelector() = throw Exception("Not used")

    override fun chapterFromElement(element: Element): SChapter = throw Exception("Not used")

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("div.texte_volume_manga ul li.chapitre div.chapitre_nom a").map {
            SChapter.create().apply {
                name = it.text()
                setUrlWithoutDomain(it.attr("href"))
                scanlator = document.select("li[itemprop=\"translator\"] a").joinToString { it.text() }
            }
        }
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        val docString = document.toString()

        var lelUrl = Regex("""['"](http.*?scanmanga.eu.*)['"]""").find(docString)?.groupValues?.get(1)

        if (lelUrl == null) {
            lelUrl = Regex("""['"](http.*?.scan-manga.com.*)['"]""").find(docString)?.groupValues?.get(1)
        }

        return Regex("""["'](.*?zoneID.*?pageID.*?siteID.*?)["']""").findAll(docString).toList().mapIndexed { i, pageParam ->
            Page(i, document.location(), lelUrl + pageParam.groupValues?.get(1))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not Used")

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder()
            .add("Referer", page.url)
            .add("Accept", "image/avif,image/webp,*/*")
            .add("Accept-Language", "fr,fr-FR;q=0.8,en-US;q=0.5,en;q=0.3")
            .build()

        val response = client.newCall(
            Request.Builder()
                .url(page.url)
                .build(),
        ).execute().body.string()

        val regexUrl = Regex("tlo = '(https://[^']+)")
        val regexUrlAlt = Regex("\\('src','(https://[^']+)'")
        var baseUrlImg: String? = null

        val imageUrl = page.imageUrl

        baseUrlImg = regexUrl.find(response)?.groupValues?.get(1)

        if (baseUrlImg == null) {
            baseUrlImg = regexUrlAlt.find(response)?.groupValues?.get(1)
        }

        return if (imageUrl != null && imageUrl.contains("https://cdn.scanmanga.eu/js/iframeResize.js")) {
            val correctedUrl = imageUrl.replace(
                "https://cdn.scanmanga.eu/js/iframeResize.js",
                baseUrlImg
                    ?: "https://cdn.scanmanga.eu/js/iframeResize.js",
            )
            GET(correctedUrl, imgHeaders)
        } else {
            GET(page.imageUrl!!, imgHeaders)
        }
    }
}
