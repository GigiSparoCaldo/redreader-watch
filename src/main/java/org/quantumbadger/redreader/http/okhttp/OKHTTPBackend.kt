package org.quantumbadger.redreader.http.okhttp

import android.content.Context
import android.util.Log
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.common.Constants
import org.quantumbadger.redreader.common.General.getGeneralErrorForFailure
import org.quantumbadger.redreader.common.General.isSensitiveDebugLoggingEnabled
import org.quantumbadger.redreader.common.Optional
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.RRError
import org.quantumbadger.redreader.common.Result
import org.quantumbadger.redreader.common.TorCommon
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.common.WatchHelper
import org.quantumbadger.redreader.http.FailedRequestBody
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.http.body.HTTPRequestBody
import org.quantumbadger.redreader.http.body.multipart.Part
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request as OkHTTPRequest

class OKHTTPBackend private constructor() : HTTPBackend() {
    private val mClient: OkHttpClient

    init {
        val builder: OkHttpClient.Builder = OkHttpClient.Builder()
        val over18Cookies = listOf(
            Cookie.Builder().domain("reddit.com").name("over18").value("1").path("/").build()
        )
        builder.cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return if (PrefsUtility.pref_behaviour_nsfw() && url.toString().contains("search")) over18Cookies else emptyList()
            }
        })
        if (TorCommon.isTorEnabled()) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8118)))
        }
        builder.followRedirects(true).followSslRedirects(true)
        builder.connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
        builder.connectionPool(ConnectionPool(10, 30, TimeUnit.SECONDS)).retryOnConnectionFailure(true)
        mClient = builder.build()
    }

    @Synchronized
    override fun recreateHttpBackend() {
        httpBackend = OKHTTPBackend()
    }

    override fun resolveRedirectUri(context: Context, url: UriString): Result<UriString> {
        return try {
            val headRequest = OkHTTPRequest.Builder().url(url.value).head().build()
            mClient.newBuilder().followRedirects(false).build().newCall(headRequest).execute().use { response ->
                if (!response.isRedirect) {
                    return Result.Err(RRError("Redirect response not received", null, true, null, response.code, url))
                }
                val loc = response.header("Location") ?: throw RuntimeException("Location header was null")
                Result.Ok(UriString(loc))
            }
        } catch (e: Exception) {
            Result.Err(getGeneralErrorForFailure(context, CacheRequest.RequestFailureType.CONNECTION, e, null, url, Optional.empty()))
        }
    }

    override fun prepareRequest(context: Context, details: RequestDetails): Request {
        val reqBuilder = OkHTTPRequest.Builder()
        reqBuilder.header("User-Agent", Constants.ua(context))
        val requestBody = details.requestBody
        if (requestBody != null) {
            reqBuilder.post(
                when (requestBody) {
                    is HTTPRequestBody.Multipart -> {
                        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                        requestBody.forEachPart { part: Part ->
                            when (part) {
                                is Part.FormData -> builder.addFormDataPart(part.name, part.value)
                                is Part.FormDataBinary -> builder.addFormDataPart(
                                    name = part.name, filename = null,
                                    body = part.value.toRequestBody("application/octet-stream".toMediaType())
                                )
                            }
                        }
                        builder.build()
                    }
                    is HTTPRequestBody.PostFields -> requestBody.encodeFields()
                        .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                }
            )
        } else {
            reqBuilder.get()
        }

        val u = WatchHelper.rewriteUrl(context, details.url.value, reqBuilder, requestBody != null)
        reqBuilder.url(u)
        reqBuilder.cacheControl(CacheControl.FORCE_NETWORK)

        val cancelled = AtomicBoolean(false)
        val callRef = AtomicReference<Call?>()

        return object : Request {
            override fun executeInThisThread(listener: Listener) {
                val call = mClient.newCall(reqBuilder.build())
                callRef.set(call)
                if (cancelled.get()) { call.cancel(); return }
                val response = try {
                    call.execute()
                } catch (e: Exception) {
                    listener.onError(CacheRequest.RequestFailureType.CONNECTION, e, null, null)
                    if (isSensitiveDebugLoggingEnabled) Log.i(TAG, "request didn't even connect: " + e.message)
                    return
                }
                try {
                    val status = response.code
                    val body = response.body
                    if (status == 200 || status == 202) {
                        val bodyStream: InputStream? = body?.byteStream()
                        val bodyLength: Long? = body?.contentLength()?.takeUnless { it < 0 }
                        listener.onSuccess(response.header("Content-Type"), bodyLength, bodyStream)
                    } else {
                        var bodyBytes: FailedRequestBody? = null
                        if (body != null) {
                            try { bodyBytes = FailedRequestBody(body.bytes()) } catch (_: IOException) {}
                        }
                        listener.onError(CacheRequest.RequestFailureType.REQUEST, null, status, bodyBytes)
                    }
                } catch (e: Exception) {
                    listener.onError(CacheRequest.RequestFailureType.REQUEST, e, null, null)
                } finally {
                    response.body?.close()
                }
            }

            override fun cancel() {
                cancelled.set(true)
                callRef.getAndSet(null)?.cancel()
            }

            override fun addHeader(name: String, value: String) {
                if (name.equals("Authorization", ignoreCase = true) &&
                    (value.contains("COOKIE_SESSION") || value.contains("ANON_PUBLIC"))
                ) return
                reqBuilder.addHeader(name, value)
            }
        }
    }

    companion object {
        private const val TAG = "OKHTTPBackend"
        private var httpBackend: HTTPBackend? = null

        @Synchronized
        fun getHttpBackend(): HTTPBackend {
            if (httpBackend == null) httpBackend = OKHTTPBackend()
            return httpBackend!!
        }
    }
}
