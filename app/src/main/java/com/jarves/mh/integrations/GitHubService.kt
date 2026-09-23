package com.jarves.mh.integrations

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/**
 * GitHub integration service for AGENTIC
 * Handles GitHub API calls with secure token storage in Android Keystore
 */
class GitHubService(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "github_api_token"
        private const val TOKEN_FILE = "github_token_encrypted"

        // GitHub API endpoints
        private const val BASE_URL = "https://api.github.com"
        private const val GITHUB_WEB_URL = "https://github.com"
    }

    private val keystore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    private val tokenFile: File by lazy {
        context.filesDir.resolve(TOKEN_FILE)
    }

    // Generate a secure random key for AES-GCM encryption
    private fun generateEncryptionKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun getEncryptionKey(): SecretKey {
        return try {
            keystore.getKey(KEY_ALIAS, null) as SecretKey
        } catch (e: Exception) {
            generateEncryptionKey()
        }
    }

    /**
     * Store GitHub token securely in Keystore-encrypted file
     */
    fun saveToken(token: String): Result<Unit> {
        return try {
            val key = getEncryptionKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val iv = cipher.iv
            val encrypted = cipher.doFinal(token.toByteArray())

            val output = StringBuilder()
            output.append(String(Base64.encode(iv, Base64.DEFAULT)))
            output.append("\n")
            output.append(String(Base64.encode(encrypted, Base64.DEFAULT)))

            tokenFile.writeText(output.toString())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Load GitHub token from Keystore-encrypted file
     */
    fun loadToken(): Result<String> {
        return try {
            if (!tokenFile.exists()) {
                return Result.failure(Exception("Token not found"))
            }

            val key = getEncryptionKey()
            val content = tokenFile.readText()
            val lines = content.split("\n")

            val iv = Base64.decode(lines[0], Base64.DEFAULT)
            val encrypted = Base64.decode(lines[1], Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

            val decrypted = cipher.doFinal(encrypted)
            Result.success(String(decrypted))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clear stored token
     */
    fun clearToken(): Result<Unit> {
        return try {
            if (tokenFile.exists()) {
                tokenFile.delete()
            }
            keystore.deleteEntry(KEY_ALIAS)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if token is stored
     */
    fun hasToken(): Boolean {
        return tokenFile.exists()
    }

    /**
     * Make authenticated GitHub API call
     */
    private fun apiCall(endpoint: String, method: String = "GET", body: JSONObject? = null): Result<JSONObject> {
        return try {
            val token = loadToken().getOrNull() ?: return Result.failure(Exception("No token stored"))
            val url = "$BASE_URL$endpoint"

            val connection = java.net.URL(url).openConnection() as HttpsURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "AGentic-Android")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }

            val responseCode = connection.responseCode
            val inputStream: InputStream = if (responseCode >= 200 && responseCode < 300) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            if (responseCode >= 400) {
                Result.failure(Exception("GitHub API error: $response"))
            } else {
                Result.success(JSONObject(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun apiArrayCall(endpoint: String, method: String = "GET", body: JSONObject? = null): Result<JSONArray> {
        return try {
            val token = loadToken().getOrNull() ?: return Result.failure(Exception("No token stored"))
            val url = "$BASE_URL$endpoint"

            val connection = java.net.URL(url).openConnection() as HttpsURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "AGentic-Android")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }

            val responseCode = connection.responseCode
            val inputStream: InputStream = if (responseCode >= 200 && responseCode < 300) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            if (responseCode >= 400) {
                Result.failure(Exception("GitHub API error: $response"))
            } else {
                Result.success(JSONArray(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get authenticated user info
     */
    fun getAuthUser(): Result<GitHubUser> {
        return apiCall("/user").map { json ->
            GitHubUser(
                login = json.getString("login"),
                id = json.getInt("id"),
                name = json.optString("name", ""),
                email = json.optString("email", ""),
                avatarUrl = json.getString("avatar_url"),
                htmlUrl = json.getString("html_url")
            )
        }
    }

    /**
     * List repositories for authenticated user
     */
    fun listRepositories(page: Int = 1, perPage: Int = 30): Result<List<GitHubRepo>> {
        return apiArrayCall("/user/repos?page=$page&per_page=$perPage").map { json ->
            val repos = mutableListOf<GitHubRepo>()
            for (i in 0 until json.length()) {
                val repo = json.getJSONObject(i)
                repos.add(
                    GitHubRepo(
                        id = repo.getInt("id"),
                        name = repo.getString("name"),
                        fullName = repo.getString("full_name"),
                        description = repo.optString("description", ""),
                        htmlUrl = repo.getString("html_url"),
                        visibility = repo.getString("visibility"),
                        isFork = repo.getBoolean("fork"),
                        pushedAt = repo.optString("pushed_at", ""),
                        createdAt = repo.optString("created_at", "")
                    )
                )
            }
            repos
        }
    }

    /**
     * Get repository details
     */
    fun getRepo(fullName: String): Result<GitHubRepo> {
        return apiCall("/repos/$fullName").map { json ->
            GitHubRepo(
                id = json.getInt("id"),
                name = json.getString("name"),
                fullName = json.getString("full_name"),
                description = json.optString("description", ""),
                htmlUrl = json.getString("html_url"),
                visibility = json.getString("visibility"),
                isFork = json.getBoolean("fork"),
                pushedAt = json.optString("pushed_at", ""),
                createdAt = json.optString("created_at", "")
            )
        }
    }

    /**
     * List issues for a repository
     */
    fun listIssues(fullName: String, state: String = "open", page: Int = 1): Result<List<GitHubIssue>> {
        return apiArrayCall("/repos/$fullName/issues?state=$state&page=$page").map { json ->
            val issues = mutableListOf<GitHubIssue>()
            for (i in 0 until json.length()) {
                val issue = json.getJSONObject(i)
                issues.add(
                    GitHubIssue(
                        id = issue.getInt("id"),
                        number = issue.getInt("number"),
                        title = issue.getString("title"),
                        body = issue.optString("body", ""),
                        state = issue.getString("state"),
                        htmlUrl = issue.getString("html_url"),
                        createdAt = issue.optString("created_at", ""),
                        updatedAt = issue.optString("updated_at", "")
                    )
                )
            }
            issues
        }
    }

    /**
     * Create an issue
     */
    fun createIssue(fullName: String, title: String, body: String = ""): Result<GitHubIssue> {
        val json = JSONObject()
        json.put("title", title)
        if (body.isNotEmpty()) json.put("body", body)

        return apiCall("/repos/$fullName/issues", "POST", json).map {
            GitHubIssue(
                id = it.getInt("id"),
                number = it.getInt("number"),
                title = it.getString("title"),
                body = it.optString("body", ""),
                state = it.getString("state"),
                htmlUrl = it.getString("html_url"),
                createdAt = it.optString("created_at", ""),
                updatedAt = it.optString("updated_at", "")
            )
        }
    }

    /**
     * List recent commits for a repository
     */
    fun listCommits(fullName: String, sha: String = "main", page: Int = 1): Result<List<GitHubCommit>> {
        return apiArrayCall("/repos/$fullName/commits?sha=$sha&page=$page").map { json ->
            val commits = mutableListOf<GitHubCommit>()
            for (i in 0 until json.length()) {
                val commit = json.getJSONObject(i)
                val commitData = commit.getJSONObject("commit")
                val author = commit.getJSONObject("author")

                commits.add(
                    GitHubCommit(
                        sha = commit.getString("sha"),
                        message = commitData.getString("message"),
                        author = commitData.getJSONObject("author").getString("name"),
                        authorLogin = author.optString("login", ""),
                        createdAt = commitData.getJSONObject("author").getString("date"),
                        htmlUrl = commit.getString("html_url")
                    )
                )
            }
            commits
        }
    }

    /**
     * Clone repository URL for local checkout
     */
    fun getCloneUrl(fullName: String, withToken: Boolean = false): String {
        return if (withToken && hasToken()) {
            val token = loadToken().getOrNull() ?: ""
            "https://x-access-token:$token@github.com/$fullName.git"
        } else {
            "$GITHUB_WEB_URL/$fullName.git"
        }
    }

    /**
     * OAuth flow helper - returns the URL for the user to authorize
     */
    fun getOAuthUrl(clientId: String, state: String): String {
        return "https://github.com/login/oauth/authorize?client_id=$clientId&state=$state&scope=repo"
    }

    /**
     * Exchange authorization code for access token
     */
    fun exchangeOAuthCode(code: String, clientId: String, clientSecret: String): Result<String> {
        return try {
            val connection = java.net.URL("https://github.com/login/oauth/access_token").openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            val body = JSONObject()
            body.put("client_id", clientId)
            body.put("client_secret", clientSecret)
            body.put("code", code)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            if (json.has("access_token")) {
                Result.success(json.getString("access_token"))
            } else {
                Result.failure(Exception(json.optString("error", "Failed to exchange code")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Data classes for GitHub API responses
 */
data class GitHubUser(
    val login: String,
    val id: Int,
    val name: String,
    val email: String,
    val avatarUrl: String,
    val htmlUrl: String
)

data class GitHubRepo(
    val id: Int,
    val name: String,
    val fullName: String,
    val description: String,
    val htmlUrl: String,
    val visibility: String,
    val isFork: Boolean,
    val pushedAt: String,
    val createdAt: String
)

data class GitHubIssue(
    val id: Int,
    val number: Int,
    val title: String,
    val body: String,
    val state: String,
    val htmlUrl: String,
    val createdAt: String,
    val updatedAt: String
)

data class GitHubCommit(
    val sha: String,
    val message: String,
    val author: String,
    val authorLogin: String,
    val createdAt: String,
    val htmlUrl: String
)
