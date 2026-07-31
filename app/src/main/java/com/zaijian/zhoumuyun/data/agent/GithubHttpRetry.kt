package com.zaijian.zhoumuyun.data.agent

import kotlinx.coroutines.delay
import java.io.IOException

/**
 * GitHub API 专用 HTTP 异常，携带状态码和响应体摘要。
 * 用于 [githubHttpRetry] 区分可重试和不可重试错误。
 *
 * @param statusCode HTTP 状态码；网络层异常（未拿到响应）使用 -1。
 * @param responseBody 响应体摘要（建议调用方截断），用于日志和错误信息。
 */
class GithubHttpException(
    val statusCode: Int,
    val responseBody: String,
    cause: Throwable? = null,
) : IOException("GitHub API 错误 (HTTP $statusCode): $responseBody", cause) {
    /**
     * 是否为可重试的 GitHub API 错误：
     *   - 429（限流）
     *   - 5xx（服务端错误）
     *   - 网络超时/连接异常（statusCode = -1，未拿到 HTTP 响应）
     *
     * 4xx（除 429）属于客户端错误（鉴权失败/资源不存在/参数非法等），
     * 重试无意义，立即抛出以节省重试预算和用户等待时间。
     */
    val isRetryable: Boolean
        get() = statusCode == -1 || statusCode == 429 || statusCode >= 500
}

/**
 * GitHub API 调用的指数退避重试帮助函数（B5-Fix6）。
 *
 * 适用于所有 GitHub API HTTP 调用，提供统一的重试策略：
 *   - 仅重试 [GithubHttpException.isRetryable] 为 true 的错误
 *   - 网络层 [IOException]（连接超时/读超时/DNS 失败/连接被重置等）自动视为
 *     可重试瞬时故障（statusCode = -1），调用方无需手动包装
 *   - 指数退避：1s, 2s, 4s...（默认最多 3 次尝试，2 次重试）
 *   - 每次重试前通过 [onRetry] 回调记录日志
 *   - 不可重试错误（4xx 除 429）立即抛出，不浪费重试预算
 *
 * 调用约定：[block] 内部遇到非成功 HTTP 状态码时应抛出 [GithubHttpException]，
 * 携带真实状态码和响应体摘要；遇到可重试状态码（429/5xx）会被本函数自动重试，
 * 遇到不可重试状态码（4xx 除 429）会立即向上抛出。
 *
 * @param maxAttempts 最大尝试次数（默认 3 次，即最多 2 次重试）
 * @param onRetry 重试前的回调（attempt 从 1 开始计数），可用于记录日志
 * @param block 实际的 API 调用逻辑
 * @return block 的返回值
 */
suspend fun <T> githubHttpRetry(
    maxAttempts: Int = 3,
    onRetry: (attempt: Int, cause: GithubHttpException) -> Unit = { _, _ -> },
    block: suspend () -> T,
): T {
    if (maxAttempts <= 0) throw IllegalArgumentException("maxAttempts must be > 0")
    var lastError: GithubHttpException? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: GithubHttpException) {
            // 不可重试的 HTTP 错误（4xx 除 429）立即抛出
            if (!e.isRetryable) throw e
            lastError = e
            if (attempt < maxAttempts - 1) {
                onRetry(attempt + 1, e)
                delay(1000L * (1L shl attempt)) // 指数退避：1s, 2s, 4s...
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消不吞
        } catch (e: IOException) {
            // 网络层异常（连接超时/读超时/DNS 失败/连接被重置等）视为可重试瞬时故障
            val networkError = GithubHttpException(
                statusCode = -1,
                responseBody = e.message ?: "网络异常",
                cause = e,
            )
            lastError = networkError
            if (attempt < maxAttempts - 1) {
                onRetry(attempt + 1, networkError)
                delay(1000L * (1L shl attempt))
            }
        }
    }
    throw lastError ?: IllegalStateException("githubHttpRetry: unreachable")
}
