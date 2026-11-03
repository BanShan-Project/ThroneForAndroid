package io.nekohasekai.sagernet.banshan

import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.group.RequestIdentity
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.group.SubscriptionFetch
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.appUserAgent
import io.nekohasekai.sagernet.ktx.newHttpClient
import io.nekohasekai.sagernet.outbound.`import`.ProfileImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * The BanShan login (the desktop's BanShanSubHandler): the account is checked against [LOGIN_URL], the subscription
 * address it answers with becomes a protected group of its own, and its profiles are downloaded into it.
 *
 * The group is independent of the subscriptions the user manages: its address is stored encrypted ([BanShanCrypto]),
 * it is not listed on the groups screen, and its group and profiles cannot be edited, deleted, moved or shared (the
 * menus check [isManagedGroup]). A completed login is remembered in [DataStore.banshanLoggedIn]; the group row itself
 * is the state that outlives a restore, so the login screen comes back when the group is gone. The account screen signs
 * in again: the same account refreshes its servers, another account replaces them (only after the new subscription is
 * known to have some).
 */
object BanShanSession {

    const val LOGIN_URL = "https://proxy.yinshaoze.asia/banshanlogin.php"

    /** What the account login reports while it runs, for the screen's progress line. */
    enum class Stage { SIGNING_IN, LOADING_NODES }

    /** [success] false: [message] is what the screen shows. */
    class LoginOutcome(
        @JvmField val success: Boolean,
        @JvmField val message: String = "",
        @JvmField val groupId: Long = 0L,
        @JvmField val count: Int = 0,
    )

    private const val CLIENT = "BanShanProxy"
    private const val FIRST_DOWNLOAD_TIMEOUT_MS = 90_000L
    private const val POLL_INTERVAL_MS = 250L

    /** A silent refresh at every start is wasteful; the desktop refreshes the hidden subscription on each start. */
    private const val REFRESH_INTERVAL_SECONDS = 6 * 60 * 60L

    private val SUBSCRIPTION_KEYS = listOf("sub_url", "subUrl", "sub", "url", "link")

    // ------------------------------------------------------------------------------------------------ state

    /** A group whose stored address is the encrypted login subscription. */
    fun isManagedUrl(url: String?): Boolean = BanShanCrypto.isEncrypted(url)

    /** The login-managed group, null before the first login or once it is gone. */
    fun managedGroup(): ProxyGroup? = runCatching {
        GroupRepo.all().firstOrNull { isManagedUrl(it.url) }
    }.getOrNull()

    fun isManagedGroup(groupId: Long): Boolean =
        groupId > 0 && isManagedUrl(GroupRepo.get(groupId)?.url)

    fun isManagedProfile(profile: ProxyEntity?): Boolean = profile != null && isManagedGroup(profile.groupId)

    /** A completed login whose group is still there. */
    fun isLoggedIn(): Boolean = DataStore.banshanLoggedIn && managedGroup() != null

    /** The plain address the fetcher needs: the decrypted login URL, or [storedUrl] as it is. */
    fun subscriptionUrl(storedUrl: String): String {
        val url = storedUrl.trim()
        if (!isManagedUrl(url)) return url
        return BanShanCrypto.decrypt(url).orEmpty()
    }

    /** The group name of a new login; the row keeps the name it was created with. */
    fun groupName(): String = app.getString(R.string.banshan_group_name)

    // ------------------------------------------------------------------------------------------------ login

    /**
     * Signs in and imports the nodes; runs the network and the database on [Dispatchers.IO], so the caller only has to
     * dispatch the stage changes to the main thread. A failed download leaves the group behind, without
     * `banshanLoggedIn`, so the next attempt rewrites its address and starts over.
     */
    suspend fun signIn(
        userName: String,
        password: String,
        onStage: suspend (Stage) -> Unit = {},
    ): LoginOutcome = withContext(Dispatchers.IO) {
        val account = userName.trim()
        if (account.isEmpty() || password.isEmpty()) {
            return@withContext LoginOutcome(false, app.getString(R.string.banshan_login_empty))
        }

        onStage(Stage.SIGNING_IN)
        val answer = postLogin(account, password)
        if (answer.exception != null) {
            Logs.w("BanShan login request failed", answer.exception)
            return@withContext LoginOutcome(false, app.getString(R.string.banshan_login_network))
        }
        val parsed = parseAnswer(answer.body)
        if (parsed == null) {
            val message = if (answer.httpCode in 200..299) {
                app.getString(R.string.banshan_login_bad_response)
            } else {
                app.getString(R.string.banshan_login_http_error, answer.httpCode)
            }
            return@withContext LoginOutcome(false, message)
        }
        val subUrl = parsed.subUrl
        if (parsed.code != 0 || subUrl.isNullOrEmpty()) {
            val message = parsed.message.ifBlank { app.getString(R.string.banshan_login_rejected) }
            return@withContext LoginOutcome(false, message)
        }
        if (!isHttpUrl(subUrl)) {
            return@withContext LoginOutcome(false, app.getString(R.string.banshan_login_bad_url))
        }

        onStage(Stage.LOADING_NODES)
        val encrypted = BanShanCrypto.encrypt(subUrl)
            ?: return@withContext LoginOutcome(false, app.getString(R.string.banshan_login_failed))
        val existing = managedGroup()
        val replacesAccount = existing != null && BanShanCrypto.decrypt(existing.url) != subUrl
        if (replacesAccount) {
            // Switching accounts removes the old servers: check that the new subscription has some first.
            val problem = validateSubscription(subUrl)
            if (problem != null) {
                return@withContext LoginOutcome(false, problem)
            }
        }
        val groupId = saveGroup(subUrl, encrypted)
            ?: return@withContext LoginOutcome(false, app.getString(R.string.banshan_login_failed))
        if (replacesAccount) clearProfiles(groupId)
        val download = downloadNodes(groupId)
        if (!download.success) {
            return@withContext LoginOutcome(false, download.message)
        }

        DataStore.banshanUsername = account
        DataStore.banshanLoggedIn = true
        runCatching { focusGroup(groupId) }.onFailure { Logs.w("BanShan focus failed", it) }
        Logs.i("BanShan login succeeded: ${download.count} profiles in group $groupId")
        LoginOutcome(true, app.getString(R.string.banshan_login_success, download.count), groupId, download.count)
    }

    /** The group that holds the login subscription: a new one, or the existing one with the new address. */
    private suspend fun saveGroup(subUrl: String, encryptedUrl: String): Long? = try {
        val existing = managedGroup()
        if (existing == null) {
            GroupRepo.add(
                ProxyGroup(
                    name = groupName(),
                    url = encryptedUrl,
                    skipAutoUpdate = false,
                )
            ).id
        } else {
            val sameAddress = BanShanCrypto.decrypt(existing.url) == subUrl
            val updated = existing.copy(
                name = groupName(),
                url = encryptedUrl,
                skipAutoUpdate = false,
                archive = false,
            )
            if (!sameAddress) {
                // What the previous address' server said does not apply to this account.
                updated.subMetadata = SubUserInfo()
                updated.subLastUpdate = 0L
                updated.info = ""
            }
            GroupRepo.save(updated)
            existing.id
        }
    } catch (e: Exception) {
        Logs.w("BanShan group could not be stored", e)
        null
    }

    /**
     * A re-login fetches the new subscription before anything is removed, so a broken or empty account never leaves
     * the app without its current servers.
     */
    private fun validateSubscription(url: String): String? = try {
        val fetched = SubscriptionFetch.fetch(url, groupName(), RequestIdentity.resolve(null))
        if (fetched.error != null) {
            app.getString(R.string.banshan_login_download_failed)
        } else {
            val parsed = ProfileImport.parse(
                fetched.body,
                SettingsMapper.xrayVlessPreference(),
                SettingsMapper.buildContext(),
            )
            if (parsed.outbounds.isEmpty()) app.getString(R.string.banshan_login_no_nodes) else null
        }
    } catch (e: Exception) {
        Logs.w("BanShan subscription check failed", e)
        app.getString(R.string.banshan_login_download_failed)
    }

    /** The previous account's servers are dropped; the refresh then imports into the empty group. */
    private suspend fun clearProfiles(groupId: Long) {
        val ids = ProfileManager.memberIds(groupId)
        if (ids.isEmpty()) return
        Logs.i("BanShan account changed: removing ${ids.size} old profiles")
        ProfileManager.batchDeleteProfiles(ids, stopRunning = true)
    }

    /**
     * Asks :bg for the first refresh and waits for its profiles: the queue answers before the download, so the list
     * is polled, and a refresh that finished without profiles (an empty or broken answer) ends the wait too.
     */
    private suspend fun downloadNodes(groupId: Long): Download = coroutineScope {
        val failure = AtomicReference<String?>()
        val watcher = launch {
            SubscriptionClient.reports.collect { report ->
                if (report.gid == groupId && report.error && report.text.isNotBlank()) {
                    failure.compareAndSet(null, report.text)
                }
            }
        }
        try {
            SubscriptionClient.refreshGroup(groupId, showDiff = false)
            val deadline = SystemClock.elapsedRealtime() + FIRST_DOWNLOAD_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                // Ids only: the rows would parse every server at each poll.
                val memberIds = ProfileManager.memberIds(groupId)
                if (memberIds.isNotEmpty()) return@coroutineScope Download(true, memberIds.size)
                if (failure.get() != null) break
                val group = GroupRepo.get(groupId) ?: break
                if (group.subLastUpdate > 0L) break
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            watcher.cancel()
        }
        val message = failure.get() ?: app.getString(R.string.banshan_login_download_failed)
        Download(false, 0, message)
    }

    /** The new group becomes the current tab, and its first profile the selected one. */
    private fun focusGroup(groupId: Long) {
        GroupRepo.setCurrent(groupId)
        val first = ProfileManager.memberIds(groupId).firstOrNull() ?: return
        DataStore.selectedProxy = first
    }

    /** At every start: the subscription refreshes itself once it is older than [REFRESH_INTERVAL_SECONDS]. */
    fun refreshIfStale() {
        if (!DataStore.banshanLoggedIn) return
        val group = managedGroup() ?: return
        // A group left without servers by a failed update retries now, whatever its stamp says.
        if (ProfileManager.memberIds(group.id).isEmpty()) {
            SubscriptionClient.refreshGroup(group.id, showDiff = false)
            return
        }
        val now = System.currentTimeMillis() / 1000
        val last = group.subLastUpdate
        if (last > 0 && now - last < REFRESH_INTERVAL_SECONDS) return
        SubscriptionClient.refreshGroup(group.id, showDiff = false)
    }

    // ------------------------------------------------------------------------------------------------ network

    private class HttpAnswer(
        @JvmField val httpCode: Int,
        @JvmField val body: String,
        @JvmField val exception: Exception? = null,
    )

    private class Answer(
        @JvmField val code: Int,
        @JvmField val message: String,
        @JvmField val subUrl: String?,
    )

    /**
     * The desktop's BanShanSubHandler.PostLoginAsync: a form POST straight out (never through the proxy), since no
     * profile is configured yet on the first start.
     */
    private fun postLogin(userName: String, password: String): HttpAnswer {
        val form = FormBody.Builder()
            .add("username", userName)
            .add("password", password)
            .add("client", CLIENT)
            .add("version", BuildConfig.VERSION_NAME)
            .build()
        val request = Request.Builder()
            .url(LOGIN_URL)
            .header("User-Agent", appUserAgent())
            .header("Accept", "*/*")
            .post(form)
            .build()
        return try {
            newHttpClient(viaProxy = false, timeoutSeconds = 20).newCall(request).execute().use { response ->
                HttpAnswer(response.code, response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            HttpAnswer(0, "", e)
        }
    }

    private fun parseAnswer(body: String): Answer? {
        if (body.isBlank()) return null
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            Logs.w("BanShan login answer is not JSON", e)
            return null
        }
        val code = root.opt("code")?.toString()?.trim()?.toIntOrNull() ?: -1
        val message = root.stringOrNull("msg") ?: root.stringOrNull("message") ?: ""
        val subUrl = findSubUrl(root) ?: root.optJSONObject("data")?.let(::findSubUrl)
        return Answer(code, message, subUrl)
    }

    private fun findSubUrl(obj: JSONObject): String? {
        for (key in SUBSCRIPTION_KEYS) {
            obj.stringOrNull(key)?.let { return it }
        }
        return null
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    private class Download(
        @JvmField val success: Boolean,
        @JvmField val count: Int,
        @JvmField val message: String = "",
    )
}
