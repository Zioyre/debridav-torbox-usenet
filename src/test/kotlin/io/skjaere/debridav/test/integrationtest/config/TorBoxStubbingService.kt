package io.skjaere.debridav.test.integrationtest.config

import org.mockserver.client.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.mockserver.model.MediaType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Stubs TorBox API endpoints on MockServer for integration testing.
 *
 * Mocked endpoints:
 *   POST /torbox/v1/api/usenet/createusenetdownload  — creates a usenet download, returns id
 *   GET  /torbox/v1/api/usenet/mylist                 — lists usenet downloads (polling)
 *   POST /torbox/v1/api/torrents/createtorrent        — creates a torrent, returns id
 *   GET  /torbox/v1/api/torrents/mylist               — lists torrents with files
 */
@Service
class TorBoxStubbingService(@Value("\${mockserver.port}") val port: Int) {

    private val client get() = MockServerClient("localhost", port)

    // ── Usenet ────────────────────────────────────────────────────────────

    /**
     * Stub the usenet create endpoint to return a successful download ID.
     */
    fun mockUsenetCreateSuccess(downloadId: Long = 42L) {
        val response = """{"success":true,"data":{"usenetdownload_id":$downloadId,"name":"test-release","hash":"abc123"}}"""
        client.`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath("/torbox/v1/api/usenet/createusenetdownload"),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(response, MediaType.APPLICATION_JSON)
        )
    }

    /**
     * Stub the usenet mylist endpoint to return a completed download with files.
     */
    fun mockUsenetMylistCompleted(
        downloadId: Long = 42L,
        fileName: String = "testfile.mkv",
        fileId: Int = 1,
        fileSize: Long = 1024 * 1024
    ) {
        val response = """
            {
                "success": true,
                "data": [{
                    "id": $downloadId,
                    "hash": "abc123",
                    "name": "test-release",
                    "download_state": "cached",
                    "size": $fileSize,
                    "files": [{
                        "id": $fileId,
                        "name": "$fileName",
                        "size": $fileSize,
                        "mimetype": "video/x-matroska",
                        "short_name": "$fileName",
                        "s3_path": "/data/$fileName"
                    }]
                }]
            }
        """.trimIndent()
        client.`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/torbox/v1/api/usenet/mylist"),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(response, MediaType.APPLICATION_JSON)
        )
    }

    /**
     * Stub the usenet mylist endpoint to return a still-downloading item.
     */
    fun mockUsenetMylistDownloading(downloadId: Long = 42L) {
        val response = """
            {
                "success": true,
                "data": [{
                    "id": $downloadId,
                    "hash": "abc123",
                    "name": "test-release",
                    "download_state": "downloading",
                    "size": 0,
                    "files": []
                }]
            }
        """.trimIndent()
        client.`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/torbox/v1/api/usenet/mylist"),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(response, MediaType.APPLICATION_JSON)
        )
    }

    /**
     * Stub the usenet create endpoint to return a failure.
     */
    fun mockUsenetCreateFailure() {
        val response = """{"success":false,"error":"Invalid NZB file"}"""
        client.`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath("/torbox/v1/api/usenet/createusenetdownload"),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(400)
                .withBody(response, MediaType.APPLICATION_JSON)
        )
    }

    // ── Torrents ──────────────────────────────────────────────────────────

    /**
     * Stub the torrent create endpoint to return a torrent ID.
     */
    fun mockTorrentCreateSuccess(torrentId: String = "test-torrent-123") {
        val response = """{"success":true,"data":{"torrent_id":"$torrentId","name":"test-torrent"}}"""
        client.`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath("/torbox/v1/api/torrents/createtorrent"),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(response, MediaType.APPLICATION_JSON)
        )
    }

    /**
     * Stub the torrent mylist endpoint to return a completed torrent with files.
     */
    fun mockTorrentMylistCompleted(
        torrentId: String = "test-torrent-123",
        fileName: String = "testfile.mkv",
        fileId: String = "1",
        fileSize: Long = 1024 * 1024
    ) {
        val response = """
            {
                "success": true,
                "data": {
                    "id": 99,
                    "hash": "6638e282767b7c710ff561a5cfd4f7e4ceb5d448",
                    "created_at": "2024-01-01T00:00:00Z",
                    "updated_at": "2024-01-01T00:00:00Z",
                    "files": [{
                        "id": "$fileId",
                        "name": "$fileName",
                        "size": $fileSize,
                        "mimetype": "video/x-matroska",
                        "short_name": "$fileName",
                        "s3_path": "/data/$fileName"
                    }]
                }
            }
        """.trimIndent()
        client.`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/torbox/v1/api/torrents/mylist")
                .withQueryStringParameter("id", torrentId),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(response, MediaType.APPLICATION_JSON)
        )
    }

    /**
     * Stub the torrent create endpoint to return a failure.
     */
    fun mockTorrentCreateFailure() {
        val response = """{"success":false,"error":"Invalid magnet"}"""
        client.`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath("/torbox/v1/api/torrents/createtorrent"),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(400)
                .withBody(response, MediaType.APPLICATION_JSON)
        )
    }
}
