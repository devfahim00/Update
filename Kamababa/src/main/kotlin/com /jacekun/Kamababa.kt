package com.jacekun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Kamababa : MainAPI() {
    override var mainUrl = "https://www.kamababa1.com"
    override var name = "Kamababa"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW,
        TvType.Movie,
        TvType.TvSeries
    )
    
    override val isNSFW = true
    override val hasAdultContent = true
    
    override val mainPage = mainPageOf(
        "$mainUrl/category/movie/" to "Adult Movies",
        "$mainUrl/category/tv-series/" to "Adult TV Series",
        "$mainUrl/category/web-series/" to "Adult Web Series",
        "$mainUrl/category/drama/" to "Adult Drama",
        "$mainUrl/category/desi/" to "Desi Content",
        "$mainUrl/category/xxx/" to "XXX Content",
        "$mainUrl/category/hot/" to "Hot Videos"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.url else "${request.url}page/$page/"
        
        return try {
            val response = app.get(
                url,
                headers = getHeaders()
            )
            
            val document = response.document
            val items = document.select("article, .item, .movie-item, .post, .video-item, .grid-item")
                .mapNotNull { element -> element.toSearchResult() }
            
            val hasNextPage = document.select(".next, .pagination .next, .nav-next, .page-numbers.next").isNotEmpty()
            
            HomePageResponse(items, hasNextPage)
        } catch (e: Exception) {
            HomePageResponse(emptyList(), false)
        }
    }

    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to mainUrl,
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin"
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2, h3, .title, .entry-title, .post-title, .video-title")
        val linkElement = this.selectFirst("a[href]")
        val imgElement = this.selectFirst("img")
        
        val title = titleElement?.text()?.trim() ?: return null
        val href = linkElement?.attr("href") ?: return null
        val poster = imgElement?.attr("src") 
            ?: imgElement?.attr("data-src") 
            ?: imgElement?.attr("data-lazy-src") 
            ?: imgElement?.attr("data-original")
            ?: ""
        
        val type = when {
            href.contains("series") || href.contains("episode") -> TvType.TvSeries
            href.contains("xxx") || href.contains("adult") || href.contains("hot") -> TvType.NSFW
            else -> TvType.Movie
        }
        
        return MovieSearchResponse(
            title = title,
            url = href,
            apiName = this@Kamababa.name,
            type = type,
            posterUrl = poster,
            quality = null,
            year = null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        return try {
            val document = app.get(
                searchUrl,
                headers = getHeaders()
            ).document
            
            document.select("article, .item, .movie-item, .post, .video-item, .grid-item")
                .mapNotNull { element -> element.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = getHeaders()
        ).document
        
        val title = document.selectFirst("h1, .entry-title, .movie-title, .post-title, .video-title")
            ?.text()?.trim() ?: "Unknown Title"
            
        val poster = document.selectFirst(".poster img, .thumbnail img, .movie-poster img, .featured-image img, .video-thumbnail img")
            ?.attr("src") 
            ?: document.selectFirst(".poster img, .thumbnail img, .movie-poster img, .featured-image img, .video-thumbnail img")
            ?.attr("data-src") 
            ?: ""
            
        val description = document.selectFirst(".description, .summary, .entry-content p, .synopsis, .video-description")
            ?.text()?.trim()
            
        val videoUrls = extractVideoUrls(document)
        val episodeElements = document.select(".episodes-list a, .episode-list a, .episode a, .server-list a, .download-links a")
        
        return if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epTitle = ep.text().trim()
                val epUrl = ep.attr("href")
                
                if (epTitle.isNotEmpty() && epUrl.isNotEmpty()) {
                    Episode(
                        name = epTitle,
                        url = epUrl,
                        posterUrl = poster
                    )
                } else null
            }
            
            TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = episodes,
                posterUrl = poster,
                plot = description
            )
        } else {
            val videoUrl = videoUrls.firstOrNull() ?: ""
            
            MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.NSFW,
                dataUrl = if (videoUrl.isNotEmpty()) videoUrl else url,
                posterUrl = poster,
                plot = description
            )
        }
    }

    private suspend fun extractVideoUrls(document: Document): List<String> {
        val videoUrls = mutableListOf<String>()
        
        // Direct video tags
        document.select("video").forEach { video ->
            listOf("src", "data-src", "data-url", "data-video").forEach { attr ->
                video.attr(attr)?.takeIf { it.isNotBlank() }?.let { 
                    if (it.contains(".mp4") || it.contains("cdn")) {
                        videoUrls.add(it)
                    }
                }
            }
            video.select("source").forEach { source ->
                source.attr("src")?.takeIf { it.isNotBlank() }?.let { videoUrls.add(it) }
            }
        }
        
        // Iframe extraction
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src") ?: iframe.attr("data-src")
            if (!src.isNullOrBlank()) {
                if (src.contains("cdn.kamababa1.com") || src.contains(".mp4")) {
                    videoUrls.add(src)
                } else {
                    try {
                        val iframeDoc = app.get(
                            src,
                            headers = getHeaders(),
                            referer = mainUrl
                        ).document
                        
                        iframeDoc.select("video, iframe, source").forEach { element ->
                            val videoSrc = element.attr("src") ?: element.attr("data-src")
                            if (!videoSrc.isNullOrBlank() && (videoSrc.contains(".mp4") || videoSrc.contains("cdn"))) {
                                videoUrls.add(videoSrc)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore iframe loading errors
                    }
                }
            }
        }
        
        // JavaScript variables
        val scriptContent = document.select("script").map { it.html() }.joinToString("\n")
        
        val patterns = listOf(
            Regex("""(?:file|video|src|url)\s*[:=]\s*["']([^"']*\.mp4[^"']*)["']"""),
            Regex("""(?:https?://cdn\.kamababa1\.com/[^"'\s]+\.mp4)"""),
            Regex("""(?:source|src)\s*[:=]\s*["']([^"']*cdn[^"']*)["']"""),
            Regex("""(?:video_url|videoUrl|vid_url|stream_url)\s*[:=]\s*["']([^"']*)["']""")
        )
        
        patterns.forEach { pattern ->
            pattern.findAll(scriptContent).forEach { match ->
                val url = match.groupValues.getOrNull(1) ?: match.value
                if (url.contains("http") || url.contains("cdn") || url.contains(".mp4")) {
                    videoUrls.add(url)
                }
            }
        }
        
        // Data attributes
        document.select("[data-video], [data-url], [data-src], [data-stream]").forEach { element ->
            listOf("data-video", "data-url", "data-src", "data-stream").forEach { attr ->
                val value = element.attr(attr)
                if (value.isNotBlank() && (value.contains(".mp4") || value.contains("cdn") || value.contains("stream"))) {
                    videoUrls.add(value)
                }
            }
        }
        
        // Download links
        document.select("a[href*='.mp4'], a[href*='download'], a[href*='cdn']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && (href.contains(".mp4") || href.contains("cdn"))) {
                videoUrls.add(href)
            }
        }
        
        return videoUrls.distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".mp4") || data.contains("cdn.kamababa1.com") || data.contains("stream")) {
            val videoUrl = if (data.startsWith("http")) data else "$mainUrl$data"
            
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name - HD Stream",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.HD1080.value,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to mainUrl,
                        "Origin" to mainUrl,
                        "Accept" to "*/*",
                        "Connection" to "keep-alive"
                    )
                )
            )
            
            return true
        }
        
        return try {
            val document = app.get(
                data,
                headers = getHeaders(),
                referer = mainUrl
            ).document
            
            val videoUrls = extractVideoUrls(document)
            
            videoUrls.forEachIndexed { index, videoUrl ->
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name - Stream ${index + 1}",
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.HD1080.value,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to mainUrl,
                            "Origin" to mainUrl,
                            "Accept" to "*/*"
                        )
                    )
                )
            }
            
            videoUrls.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
    
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
