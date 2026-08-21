package cn.huohuas001.bot.service

import java.awt.image.BufferedImage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
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
            Result(similarity = imageSimilarity(qqImage, openIdImage))
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

    internal fun imageSimilarity(first: BufferedImage, second: BufferedImage): Double {
        val distance = java.lang.Long.bitCount(phash(first) xor phash(second))
        return 1.0 - distance / 64.0
    }

    // Matches the Python implementation: convert the complete image to grayscale,
    // resize directly to 32x32, then calculate a 64-bit pHash. Do not crop first:
    // Python's Image.resize preserves the complete source image, including its aspect ratio.
    internal fun phash(source: BufferedImage): Long {
        val size = 32
        val hashSize = 8
        val pixels = resizeLuminance(source, size)
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

    /**
     * Equivalent to PIL's convert("L").resize((size, size), Image.LANCZOS).
     * Keeping this independent of AWT's image scaler makes the hash stable across JDKs.
     */
    private fun resizeLuminance(source: BufferedImage, size: Int): DoubleArray {
        val width = source.width
        val height = source.height
        val luminance = DoubleArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = source.getRGB(x, y)
                val red = rgb ushr 16 and 0xFF
                val green = rgb ushr 8 and 0xFF
                val blue = rgb and 0xFF
                luminance[y * width + x] = (299 * red + 587 * green + 114 * blue + 500) / 1000.0
            }
        }

        val horizontal = resizeAxis(luminance, width, height, size, horizontal = true)
        return resizeAxis(horizontal, size, height, size, horizontal = false)
    }

    /** Pillow applies its Lanczos filter as two fixed-point, 8-bit image passes. */
    private fun resizeAxis(
        source: DoubleArray,
        width: Int,
        height: Int,
        targetSize: Int,
        horizontal: Boolean
    ): DoubleArray {
        val sourceLength = if (horizontal) width else height
        val scale = targetSize.toDouble() / sourceLength
        val filterScale = minOf(scale, 1.0)
        val radius = 3.0 / filterScale
        val targetWidth = if (horizontal) targetSize else width
        val targetHeight = if (horizontal) height else targetSize
        val result = DoubleArray(targetWidth * targetHeight)

        for (output in 0 until targetSize) {
            val center = (output + 0.5) / scale - 0.5
            val start = kotlin.math.ceil(center - radius).toInt().coerceAtLeast(0)
            val end = floor(center + radius).toInt().coerceAtMost(sourceLength - 1)
            val weights = IntArray(end - start + 1)
            val floatingWeights = DoubleArray(end - start + 1)
            var weightTotal = 0.0
            for (sample in start..end) {
                val weight = lanczos((sample - center) * filterScale)
                floatingWeights[sample - start] = weight
                weightTotal += weight
            }
            for (index in floatingWeights.indices) {
                val normalized = floatingWeights[index] / weightTotal * (1 shl 22)
                weights[index] = if (normalized < 0.0) (normalized - 0.5).toInt() else (normalized + 0.5).toInt()
            }

            val fixed = if (horizontal) height else width
            for (position in 0 until fixed) {
                var value = 1 shl 21
                for (sample in start..end) {
                    val sourceIndex = if (horizontal) position * width + sample else sample * width + position
                    value += source[sourceIndex].toInt() * weights[sample - start]
                }
                // Pillow clips after shifting each pass back from its 22-bit fixed point value.
                val rounded = (value shr 22).coerceIn(0, 255).toDouble()
                val targetIndex = if (horizontal) position * targetWidth + output else output * targetWidth + position
                result[targetIndex] = rounded
            }
        }
        return result
    }

    private fun lanczos(value: Double): Double {
        val absolute = kotlin.math.abs(value)
        if (absolute >= 3.0) return 0.0
        if (absolute < 1.0e-12) return 1.0
        return (sin(PI * value) / (PI * value)) * (sin(PI * value / 3.0) / (PI * value / 3.0))
    }
}
