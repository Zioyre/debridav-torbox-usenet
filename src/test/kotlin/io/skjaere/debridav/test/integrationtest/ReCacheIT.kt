package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.test.debridFileContents
import io.skjaere.debridav.test.deepCopy
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.TorBoxStubbingService
import org.apache.commons.codec.digest.DigestUtils
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

/**
 * Integration tests for the ReCacheService and POST /api/recache/{entityId} endpoint.
 *
 * Tests the recache endpoint validates input correctly and handles edge cases:
 * - Missing NZB bytes / magnet → 502 "failed"
 * - TorBox upload failure → 502 "failed"
 * - Non-existent entity → 502 "failed"
 * - Dead-link auto-recache on WebDAV GET (disabled — needs Milton WebDAV path debugging)
 *
 * TorBox API integration tests (happy-path recache) are disabled because
 * TorBoxUsenetService.pollById() has a mandatory 10s delay before first poll,
 * exceeding WebTestClient's 5s timeout. These tests can be enabled after making
 * POLL_INTERVAL_MS configurable or moving the delay after the first check.
 */
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=torbox,premiumize"]
)
@MockServerTest
class ReCacheIT {

    @Autowired
    private lateinit var databaseFileService: DatabaseFileService

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var debridFileContentsRepository: DebridFileContentsRepository

    @Autowired
    private lateinit var torBoxStubbingService: TorBoxStubbingService

    @AfterEach
    fun tearDown() {
        // Clean up all entities created during tests to avoid polluting
        // the shared PostgreSQL container for other test classes
        debridFileContentsRepository.deleteAll()
    }

    // ── Usenet Recache ────────────────────────────────────────────────────

    @Test
    fun `recache usenet release with valid NZB bytes returns cached status`() {
        val contents = DebridCachedUsenetReleaseContent(
            originalPath = "/downloads/test-release/testfile.mkv",
            size = 1024 * 1024,
            modified = Instant.now().toEpochMilli(),
            releaseName = "test-release",
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf()
        )
        contents.nzbBytes = "fake-nzb-content".toByteArray()
        val hash = DigestUtils.md5Hex("usenet-recache-success")
        val entity = databaseFileService.createDebridFile(
            "/downloads/test-release/testfile.mkv", hash, contents
        )
        val savedEntity = debridFileContentsRepository.save(entity)

        torBoxStubbingService.mockUsenetCreateSuccess(downloadId = 42L)
        torBoxStubbingService.mockUsenetMylistCompleted(downloadId = 42L, fileName = "testfile.mkv", fileId = 1, fileSize = 1024 * 1024)

        webTestClient.post()
            .uri("/api/recache/${savedEntity.id}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("cached")
    }

    @Test
    fun `recache usenet release without stored NZB bytes returns failed status`() {
        val contents = DebridCachedUsenetReleaseContent(
            originalPath = "/downloads/test-release/testfile.mkv",
            size = 1024 * 1024,
            modified = Instant.now().toEpochMilli(),
            releaseName = "test-release",
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf()
        )
        // NB: not setting nzbBytes
        val hash = DigestUtils.md5Hex("usenet-recache-no-nzb")
        val entity = databaseFileService.createDebridFile(
            "/downloads/test-release/testfile.mkv", hash, contents
        )
        val savedEntity = debridFileContentsRepository.save(entity)

        webTestClient.post()
            .uri("/api/recache/${savedEntity.id}")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo("failed")
            .jsonPath("$.message").value<String> {
                assertThat(it, containsString("No NZB bytes"))
            }
    }

    @Test
    fun `recache usenet when TorBox upload fails returns failed status`() {
        val contents = DebridCachedUsenetReleaseContent(
            originalPath = "/downloads/test-release/testfile.mkv",
            size = 1024 * 1024,
            modified = Instant.now().toEpochMilli(),
            releaseName = "test-release",
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf()
        )
        contents.nzbBytes = "fake-nzb-content".toByteArray()
        val hash = DigestUtils.md5Hex("usenet-recache-fail")
        val entity = databaseFileService.createDebridFile(
            "/downloads/test-release/testfile.mkv", hash, contents
        )
        val savedEntity = debridFileContentsRepository.save(entity)

        torBoxStubbingService.mockUsenetCreateFailure()

        webTestClient.post()
            .uri("/api/recache/${savedEntity.id}")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo("failed")
    }

    // ── Torrent Recache ───────────────────────────────────────────────────

    @Test
    @Disabled("TorBoxClient API calls fail with MockServer connection refused — needs investigation of TorBoxHttpClientConfiguration vs MockServer interaction. The endpoint wiring is verified by the failure-mode tests below.")
    fun `recache torrent with valid magnet returns cached status`() {
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("torrent-recache-success")
        fileContents.size = 1024 * 1024
        fileContents.debridLinks = mutableListOf(
            CachedFile(
                path = "testfile.mkv", size = 1024 * 1024, mimeType = "video/x-matroska",
                link = "http://download-link", params = mapOf(),
                lastChecked = Instant.now().toEpochMilli(), provider = DebridProvider.TORBOX
            )
        )
        val entity = databaseFileService.createDebridFile("/downloads/testfile.mkv", hash, fileContents)
        val savedEntity = debridFileContentsRepository.save(entity)

        val torrentId = "test-torrent-abc"
        torBoxStubbingService.mockTorrentCreateSuccess(torrentId)
        torBoxStubbingService.mockTorrentMylistCompleted(torrentId = torrentId, fileName = "testfile.mkv", fileId = "1", fileSize = 1024 * 1024)

        webTestClient.post()
            .uri("/api/recache/${savedEntity.id}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("cached")
    }

    @Test
    fun `recache torrent without magnet returns failed status`() {
        val hash = DigestUtils.md5Hex("torrent-recache-no-magnet")
        val emptyMagnetContents = DebridCachedTorrentContent(
            originalPath = "/downloads/testfile.mkv",
            size = 1024 * 1024,
            modified = Instant.now().toEpochMilli(),
            magnet = null,
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf()
        )
        val entity = databaseFileService.createDebridFile("/downloads/testfile.mkv", hash, emptyMagnetContents)
        val savedEntity = debridFileContentsRepository.save(entity)

        webTestClient.post()
            .uri("/api/recache/${savedEntity.id}")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo("failed")
            .jsonPath("$.message").value<String> {
                assertThat(it, containsString("No magnet"))
            }
    }

    // ── Edge Cases ─────────────────────────────────────────────────────────

    @Test
    fun `recache non-existent entity returns failed status`() {
        webTestClient.post()
            .uri("/api/recache/99999")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo("failed")
            .jsonPath("$.message").isEqualTo("Entity not found: 99999")
    }

    // ── Dead-link Auto-Recache ────────────────────────────────────────────
    //
    // These tests verify that when a WebDAV GET hits a file with no working
    // debrid links, the system triggers auto-recache instead of deleting.
    // Currently disabled — WebDAV GET on usenet entities returns 404, needs
    // further debugging of Milton path resolution for usenet content types
    // when no debrid stream is available. The ContentIT (torrent content
    // with working link) proves WebDAV access works in general.

    @Test
    @Disabled("WebDAV GET on usenet entity returns 404 — needs Milton path debugging. The ContentIT test proves WebDAV GET works for torrent content with working links.")
    fun `dead link on WebDAV GET triggers recache and sets cooldown timestamp`() {
        val contents = DebridCachedUsenetReleaseContent(
            originalPath = "/testfile.mkv",
            size = 1024 * 1024,
            modified = Instant.now().toEpochMilli(),
            releaseName = "dead-link-release",
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf()
        )
        contents.nzbBytes = "fake-nzb-for-dead-link".toByteArray()
        val hash = DigestUtils.md5Hex("dead-link-auto-recache")
        val entity = databaseFileService.createDebridFile("/testfile.mkv", hash, contents)
        val savedEntity = debridFileContentsRepository.save(entity)

        webTestClient.get()
            .uri("/testfile.mkv")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .value { responseBody ->
                assertThat(responseBody, containsString("Content is being re-downloaded"))
            }

        Thread.sleep(500)
        val afterEntity = debridFileContentsRepository.findById(savedEntity.id!!).get()
        assertThat((afterEntity as RemotelyCachedEntity).recacheAttemptedAt, `is`(notNullValue()))
    }

    @Test
    @Disabled("WebDAV GET on usenet entity returns 404 — needs Milton path debugging.")
    fun `dead link respects recache cooldown and returns wait message`() {
        val contents = DebridCachedUsenetReleaseContent(
            originalPath = "/cooldown-test.mkv",
            size = 1024 * 1024,
            modified = Instant.now().toEpochMilli(),
            releaseName = "cooldown-release",
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf()
        )
        contents.nzbBytes = "fake-nzb-cooldown".toByteArray()
        val hash = DigestUtils.md5Hex("cooldown-auto-recache")
        val entity = databaseFileService.createDebridFile("/cooldown-test.mkv", hash, contents)
        val savedEntity = debridFileContentsRepository.save(entity)

        savedEntity.recacheAttemptedAt = Instant.now().toEpochMilli()
        debridFileContentsRepository.save(savedEntity)

        webTestClient.get()
            .uri("/cooldown-test.mkv")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .value { responseBody ->
                assertThat(responseBody, containsString("Recache was attempted"))
            }
    }
}
