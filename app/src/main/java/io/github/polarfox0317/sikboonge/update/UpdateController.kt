package io.github.polarfox0317.sikboonge.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data object NoPublishedRelease : UpdateStatus
    data class Available(
        val release: AppRelease,
        val promptOnLaunch: Boolean,
        val installedVersion: String = "",
    ) : UpdateStatus
    data class Downloading(
        val release: AppRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateStatus
    data class PreparingInstall(
        val release: AppRelease,
        val waitingForUnknownSourcesPermission: Boolean = false,
    ) : UpdateStatus
    data class Failed(
        val message: String,
        val release: AppRelease? = null,
    ) : UpdateStatus
}

internal enum class ActivityLaunchKind { UnknownSourcesSettings, Installer }

internal data class UpdateActivityLaunchRequest(val id: Long, val kind: ActivityLaunchKind)

internal data class AppChangelog(val versionCode: Long, val versionName: String, val notes: List<ReleaseNoteBlock>) {
    val releaseUrl: String get() = releasePageUrl("v$versionName")
}

/** Process-scoped update state, download work, and update preferences. */
internal object UpdateController {
    private const val PREFERENCES_NAME = "sikboonge_update_preferences"
    private const val KEY_AUTO_CHECK = "auto_check_on_startup"
    private const val KEY_SKIPPED_VERSION = "skipped_auto_prompt_version"
    private const val KEY_LAST_SEEN_CHANGELOG_VERSION_CODE = "last_seen_changelog_version_code"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val UPDATE_NOTIFICATION_INTERVAL_NANOS = 400_000_000L

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    private val _autoCheckEnabled = MutableStateFlow(true)
    private val _pendingChangelog = MutableStateFlow<AppChangelog?>(null)
    private val _activityLaunch = MutableStateFlow<UpdateActivityLaunchRequest?>(null)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()
    val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()
    internal val pendingChangelog: StateFlow<AppChangelog?> = _pendingChangelog.asStateFlow()
    val activityLaunch: StateFlow<UpdateActivityLaunchRequest?> = _activityLaunch.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    private var startupCheckStarted = false
    private var requestSequence = 0L
    private var awaitingActivityResult: ActivityLaunchKind? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private val downloadLock = Any()
    private var downloadGeneration = 0L
    private var lastUpdateNotificationAtNanos = 0L
    private var pendingRelease: AppRelease? = null
    private var verifiedApk: File? = null
    private var expectedInstalledVersionCode = 0L

    fun initialize(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (appContext != null) return
            appContext = app
            _autoCheckEnabled.value = preferences(app).getBoolean(KEY_AUTO_CHECK, true)
            _pendingChangelog.value = pendingChangelogFor(app)
        }
    }

    /** Mark the bundled release notes seen only after the user acknowledges the dialog. */
    fun acknowledgeChangelog() {
        val context = contextOrNull() ?: return
        val changelog = _pendingChangelog.value ?: return
        preferences(context).edit()
            .putLong(KEY_LAST_SEEN_CHANGELOG_VERSION_CODE, changelog.versionCode)
            .apply()
        _pendingChangelog.value = null
    }

    private fun pendingChangelogFor(context: Context): AppChangelog? = runCatching {
        val info = getAppPackageInfo(context, context.packageName)
        val currentCode = packageVersionCode(info)
        val lastSeenCode = preferences(context).getLong(KEY_LAST_SEEN_CHANGELOG_VERSION_CODE, 0L)
        val isUpdatedInstall = info.lastUpdateTime > info.firstInstallTime
        val notes = pendingBundledChangelog(currentCode, lastSeenCode, isUpdatedInstall)
            ?: return@runCatching null
        AppChangelog(
            versionCode = currentCode,
            versionName = info.versionName.orEmpty(),
            notes = notes,
        )
    }.getOrNull()

    val currentVersionName: String
        get() = contextOrNull()?.let { context ->
            runCatching { getAppPackageInfo(context, context.packageName).versionName.orEmpty() }
                .getOrDefault("알 수 없음")
        } ?: "알 수 없음"

    fun setAutoCheckEnabled(enabled: Boolean) {
        val context = contextOrNull() ?: return
        _autoCheckEnabled.value = enabled
        preferences(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun checkAtStartupOnce() {
        val context = contextOrNull() ?: return
        synchronized(this) {
            if (startupCheckStarted) return
            startupCheckStarted = true
        }
        if (!_autoCheckEnabled.value) return
        launchLookup(context, manual = false)
    }

    fun checkManually() {
        val context = contextOrNull() ?: return
        launchLookup(context, manual = true)
    }

    private fun launchLookup(context: Context, manual: Boolean) {
        checkJob?.cancel()
        checkJob = processScope.launch {
            _status.value = UpdateStatus.Checking
            val result = try {
                withContext(Dispatchers.IO) { GithubReleaseClient().fetchLatestRelease() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (result == null) {
                if (manual) {
                    _status.value = UpdateStatus.Failed(
                        "버전을 확인하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.",
                    )
                } else {
                    _status.value = UpdateStatus.Idle
                }
                return@launch
            }

            when (result) {
                ReleaseLookupResult.NoPublishedRelease -> {
                    _status.value = if (manual) UpdateStatus.NoPublishedRelease else UpdateStatus.Idle
                }
                ReleaseLookupResult.NoApkAsset -> {
                    _status.value = if (manual) {
                        UpdateStatus.Failed(
                            "최신 릴리즈에 APK 설치 파일이 없어요.",
                        )
                    } else UpdateStatus.Idle
                }
                is ReleaseLookupResult.Found -> showReleaseIfNewer(
                    context = context,
                    release = result.release,
                    manual = manual,
                )
            }
        }
    }

    private fun showReleaseIfNewer(context: Context, release: AppRelease, manual: Boolean) {
        val installedName = runCatching {
            getAppPackageInfo(context, context.packageName).versionName.orEmpty()
        }.getOrDefault("")
        val comparison = compareVersions(release.version, installedName)
        if (comparison == null) {
            _status.value = if (manual) {
                UpdateStatus.Failed("설치된 앱 버전을 확인하지 못했어요.")
            } else UpdateStatus.Idle
            return
        }
        if (comparison <= 0) {
            _status.value = if (manual) UpdateStatus.UpToDate else UpdateStatus.Idle
            return
        }

        val skippedVersion = preferences(context).getString(KEY_SKIPPED_VERSION, null)
        val promptOnLaunch = !manual && skippedVersion != release.version
        _status.value = UpdateStatus.Available(
            release = release,
            promptOnLaunch = promptOnLaunch,
            installedVersion = normalizedVersion(installedName) ?: installedName,
        )
    }

    fun dismissLaunchPrompt() {
        val current = _status.value as? UpdateStatus.Available ?: return
        _status.value = current.copy(promptOnLaunch = false)
    }

    fun skipLaunchPromptForVersion() {
        val context = contextOrNull() ?: return
        val current = _status.value as? UpdateStatus.Available ?: return
        preferences(context).edit().putString(KEY_SKIPPED_VERSION, current.release.version).apply()
        _status.value = current.copy(promptOnLaunch = false)
    }

    fun downloadAndInstall() {
        val release = when (val current = _status.value) {
            is UpdateStatus.Available -> current.release
            is UpdateStatus.Failed -> current.release
            else -> null
        } ?: return
        startDownload(release)
    }

    fun cancelDownload() {
        val current = _status.value as? UpdateStatus.Downloading ?: return
        synchronized(downloadLock) {
            downloadGeneration += 1L
            downloadJob?.cancel()
            downloadJob = null
            contextOrNull()?.let { context -> runCatching { UpdateDownloadService.clear(context) } }
        }
        verifiedApk = null
        val installedName = contextOrNull()?.let { context ->
            runCatching { getAppPackageInfo(context, context.packageName).versionName.orEmpty() }
                .getOrDefault("")
        }.orEmpty()
        _status.value = UpdateStatus.Available(
            current.release,
            promptOnLaunch = false,
            installedVersion = normalizedVersion(installedName) ?: installedName,
        )
    }

    fun retry() {
        val current = _status.value as? UpdateStatus.Failed ?: return
        if (verifiedApk?.isFile == true && pendingRelease != null) {
            requestSystemInstall()
        } else if (current.release != null) {
            startDownload(current.release)
        } else {
            checkManually()
        }
    }

    private fun startDownload(release: AppRelease) {
        val context = contextOrNull() ?: return
        val asset = release.apk
        if (asset == null) {
            _status.value = UpdateStatus.Failed(
                "최신 릴리즈에 APK 설치 파일이 없어요.",
                release,
            )
            return
        }
        val generation = synchronized(downloadLock) {
            downloadGeneration += 1L
            downloadJob?.cancel()
            downloadJob = null
            lastUpdateNotificationAtNanos = 0L
            downloadGeneration
        }
        verifiedApk = null
        pendingRelease = release
        _status.value = UpdateStatus.Downloading(release, 0L, asset.sizeBytes)
        try {
            UpdateDownloadService.start(context)
        } catch (_: Exception) {
            runCatching { UpdateDownloadService.clear(context) }
            _status.value = UpdateStatus.Failed("업데이트 다운로드를 시작하지 못했어요.", release)
            return
        }
        val job = processScope.launch {
            var apkFile: File? = null
            try {
                apkFile = withContext(Dispatchers.IO) {
                    downloadApk(context, asset) { downloaded, total ->
                        synchronized(downloadLock) {
                            if (generation != downloadGeneration) return@synchronized
                            _status.value = UpdateStatus.Downloading(release, downloaded, total)
                            val now = System.nanoTime()
                            if (lastUpdateNotificationAtNanos == 0L || now - lastUpdateNotificationAtNanos >= UPDATE_NOTIFICATION_INTERVAL_NANOS) {
                                UpdateDownloadService.showProgress(context, downloaded, total)
                                lastUpdateNotificationAtNanos = now
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val verificationError = withContext(Dispatchers.IO) {
                    verifyApk(context, requireNotNull(apkFile))
                }
                if (verificationError != null) {
                    requireNotNull(apkFile).delete()
                    _status.value = UpdateStatus.Failed(verificationError, release)
                    return@launch
                }

                val verified = requireNotNull(apkFile)
                val versionCode = withContext(Dispatchers.IO) {
                    readArchiveVersionCode(context, verified)
                } ?: 0L
                currentCoroutineContext().ensureActive()
                synchronized(downloadLock) {
                    if (generation != downloadGeneration) return@synchronized
                    verifiedApk = verified
                    expectedInstalledVersionCode = versionCode
                    runCatching { UpdateDownloadService.showCompleted(context, release.version) }
                    _status.value = UpdateStatus.PreparingInstall(release)
                }
                if (generation == synchronized(downloadLock) { downloadGeneration }) requestSystemInstall()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                apkFile?.delete()
                if (generation == synchronized(downloadLock) { downloadGeneration }) {
                    _status.value = UpdateStatus.Failed(
                        "APK를 다운로드하지 못했어요. 연결을 확인하고 다시 시도해 주세요.",
                        release,
                    )
                }
            } finally {
                synchronized(downloadLock) {
                    if (generation == downloadGeneration) {
                        downloadJob = null
                        runCatching { UpdateDownloadService.clear(context) }
                    }
                }
            }
        }
        synchronized(downloadLock) {
            if (generation == downloadGeneration) downloadJob = job else job.cancel()
        }
    }

    fun requestSystemInstall() {
        val context = contextOrNull() ?: return
        val release = pendingRelease ?: return
        if (verifiedApk?.isFile != true) {
            _status.value = UpdateStatus.Failed(
                "APK 파일을 찾지 못했어요. 다시 다운로드해 주세요.",
                release,
            )
            return
        }
        val canInstall = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        if (!canInstall) {
            _status.value = UpdateStatus.PreparingInstall(release, waitingForUnknownSourcesPermission = true)
            queueActivityLaunch(ActivityLaunchKind.UnknownSourcesSettings)
            return
        }
        _status.value = UpdateStatus.PreparingInstall(release)
        queueActivityLaunch(ActivityLaunchKind.Installer)
    }

    fun activityIntent(request: UpdateActivityLaunchRequest): Intent {
        val context = requireNotNull(contextOrNull()) { "UpdateController is not initialized" }
        return when (request.kind) {
            ActivityLaunchKind.UnknownSourcesSettings -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            ActivityLaunchKind.Installer -> {
                val apk = requireNotNull(verifiedApk?.takeIf(File::isFile))
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    apk,
                )
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                }
            }
        }
    }

    fun markActivityLaunchDispatched(request: UpdateActivityLaunchRequest) {
        if (_activityLaunch.value?.id != request.id) return
        awaitingActivityResult = request.kind
        _activityLaunch.value = null
    }

    fun onActivityLaunchFailed(request: UpdateActivityLaunchRequest) {
        awaitingActivityResult = null
        _activityLaunch.value = null
        _status.value = UpdateStatus.Failed(
            if (request.kind == ActivityLaunchKind.UnknownSourcesSettings) {
                "앱 설치 권한 설정을 열지 못했어요. 다시 시도해 주세요."
            } else {
                "시스템 설치 화면을 열지 못했어요."
            },
            pendingRelease,
        )
    }

    fun onSystemActivityResult() {
        val context = contextOrNull() ?: return
        when (awaitingActivityResult) {
            ActivityLaunchKind.UnknownSourcesSettings -> {
                awaitingActivityResult = null
                if (runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)) {
                    requestSystemInstall()
                } else {
                    _status.value = UpdateStatus.Failed(
                        "설정에서 이 앱의 APK 설치를 허용한 뒤 다시 시도해 주세요.",
                        pendingRelease,
                    )
                }
            }
            ActivityLaunchKind.Installer -> {
                awaitingActivityResult = null
                val installedVersion = runCatching {
                    packageVersionCode(getAppPackageInfo(context, context.packageName))
                }.getOrDefault(0L)
                if (expectedInstalledVersionCode > 0L && installedVersion >= expectedInstalledVersionCode) {
                    _status.value = UpdateStatus.UpToDate
                } else {
                    _status.value = UpdateStatus.Failed(
                        "설치가 취소되었거나 완료되지 않았어요. 설치 화면에서 확인해 주세요.",
                        pendingRelease,
                    )
                }
            }
            null -> Unit
        }
    }

    private fun queueActivityLaunch(kind: ActivityLaunchKind) {
        requestSequence += 1L
        _activityLaunch.value = UpdateActivityLaunchRequest(requestSequence, kind)
    }

    private fun verifyApk(context: Context, file: File): String? {
        val archive = runCatching { getArchivePackageInfo(context, file) }.getOrNull()
            ?: return "APK 파일을 열 수 없어요. 다시 다운로드해 주세요."
        if (archive.packageName != context.packageName) {
            return "이 APK는 ${archive.packageName}용입니다. 현재 앱(${context.packageName})과 패키지가 달라 업데이트할 수 없어요."
        }
        val installed = runCatching { getAppPackageInfo(context, context.packageName) }.getOrNull()
            ?: return "설치된 앱 버전을 확인하지 못했어요."
        if (packageVersionCode(archive) <= packageVersionCode(installed)) {
            return "다운로드한 APK가 현재 설치된 버전보다 새로운 버전이 아니에요."
        }
        if (!signingCertificateMatches(context, archive, installed)) {
            return "설치된 앱과 APK의 서명 인증서가 달라 업데이트할 수 없어요. 같은 서명 키로 빌드한 APK가 필요해요."
        }
        return null
    }

    private fun signingCertificateMatches(
        context: Context,
        archive: PackageInfo,
        installed: PackageInfo,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val archiveSigners = archive.signingInfo?.apkContentsSigners.orEmpty()
            return archiveSigners.isNotEmpty() && archiveSigners.all { signature ->
                context.packageManager.hasSigningCertificate(
                    context.packageName,
                    signature.toByteArray(),
                    PackageManager.CERT_INPUT_RAW_X509,
                )
            }
        }
        @Suppress("DEPRECATION")
        val archiveSigners = archive.signatures.orEmpty()
        @Suppress("DEPRECATION")
        val installedSigners = installed.signatures.orEmpty()
        return archiveSigners.isNotEmpty() && installedSigners.isNotEmpty() &&
            archiveSigners.size == installedSigners.size && archiveSigners.all { candidate ->
                installedSigners.any { current -> candidate.toByteArray().contentEquals(current.toByteArray()) }
            }
    }

    private fun getArchivePackageInfo(context: Context, file: File): PackageInfo? {
        val flags = signingInfoFlag()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    private fun getAppPackageInfo(context: Context, packageName: String): PackageInfo {
        val flags = signingInfoFlag()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signingInfoFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    private fun readArchiveVersionCode(context: Context, file: File): Long? =
        runCatching { getArchivePackageInfo(context, file)?.let(::packageVersionCode) }.getOrNull()

    private fun packageVersionCode(info: PackageInfo): Long = PackageInfoCompat.getLongVersionCode(info)

    private suspend fun downloadApk(
        context: Context,
        asset: GithubReleaseAsset,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File {
        if (!asset.downloadUrl.startsWith("https://", ignoreCase = true)) {
            throw IOException("APK download URL must use HTTPS")
        }
        val directory = File(context.cacheDir, "apk_updates").apply { mkdirs() }
        val destination = File(directory, "sikboonge-update.apk")
        val temporary = File(directory, "sikboonge-update.apk.part")
        val connection = (URL(asset.downloadUrl).openConnection() as HttpsURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "sikboonge-android")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299 || connection.url.protocol != "https") {
                throw IOException("APK download returned HTTP $responseCode")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes
            var downloaded = 0L
            var lastReportedAt = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.nanoTime()
                        if (now - lastReportedAt >= 180_000_000L) {
                            onProgress(downloaded, totalBytes)
                            lastReportedAt = now
                        }
                    }
                    output.fd.sync()
                }
            }
            if (downloaded == 0L || (totalBytes > 0L && downloaded != totalBytes)) {
                throw IOException("Downloaded APK length did not match the expected length")
            }
            onProgress(downloaded, totalBytes)
            if (destination.exists() && !destination.delete()) {
                throw IOException("Unable to replace cached APK")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Unable to finalize cached APK")
            }
            return destination
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun contextOrNull(): Context? = appContext
}
