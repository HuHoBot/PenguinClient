package cn.huohuas001.bot.service

import java.awt.image.BufferedImage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/** QQ 头像与 QQ 群成员 OpenID 头像的感知哈希认证。 */
object AvatarAuthenticationService {
    const val MIN_SIMILARITY = 0.98

    data class Result(
        val similarity: Double = -1.0,
        val code: Int = 0,
        val message: String = "成功"
    )

    fun compare(appId: String, qq: String, openId: String): Result {
        val qqUrl = "https://q.qlogo.cn/g?b=qq&nk=$qq&s=100"
        val openIdUrl = "https://q.qlogo.cn/qqapp/$appId/$openId/100"
        val qqImage = download(qqUrl)
            ?: return Result(code = 1, message = "QQ头像下载失败（可能原因：网络超时或QQ号不存在） URL: $qqUrl")
        val openIdImage = download(openIdUrl)
            ?: return Result(code = 2, message = "OpenID头像下载失败（可能原因：授权过期或用户未设置头像） URL: $openIdUrl")

        return try {
            val distance = java.lang.Long.bitCount(phash(qqImage) xor phash(openIdImage))
            Result(similarity = 1.0 - distance / 64.0)
        } catch (error: Exception) {
            Result(code = 5, message = "哈希计算失败（${error.message ?: error.javaClass.simpleName}）")
        }
    }

    private fun download(url: String): BufferedImage? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.inputStream.use { input ->
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null else ImageIO.read(input)
        }
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }

    // Matches the Python implementation: grayscale 32x32 DCT-II, then 64-bit pHash.
    private fun phash(source: BufferedImage): Long {
        val size = 32
        val hashSize = 8
        val pixels = DoubleArray(size * size)
        val square = minOf(source.width, source.height)
        val left = (source.width - square) / 2
        val top = (source.height - square) / 2
        val cropped = source.getSubimage(left, top, square, square)
        val gray = BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = gray.createGraphics()
        graphics.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC
        )
        graphics.drawImage(cropped, 0, 0, size, size, null)
        graphics.dispose()
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = gray.raster.getSample(x, y, 0).toDouble()
            }
        }

        val coefficients = DoubleArray(hashSize * hashSize)
        var index = 0
        for (u in 0 until hashSize) {
            for (v in 0 until hashSize) {
                var sum = 0.0
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        sum += pixels[y * size + x] * cos(PI * (2 * y + 1) * u / (2 * size)) *
                                cos(PI * (2 * x + 1) * v / (2 * size))
                    }
                }
                val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                coefficients[index++] = sum * cu * cv * 2.0 / size
            }
        }

        val median = coefficients.drop(1).sorted()[coefficients.size / 2 - 1]
        var hash = 0L
        coefficients.forEach { value ->
            hash = (hash shl 1) or if (value > median) 1L else 0L
        }
        return hash
    }
}
