package cn.huohuas001.bot.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 把扫码 URL 渲染成终端可以直接显示的半块字符二维码。
 *
 * 采用「深色终端」配色：二维码中浅色（白色）模块用前景色字符 `█`/`▀`/`▄` 绘制，
 * 深色模块留空显示终端背景色。这样在 Minecraft 服务端常见的深色控制台里
 * 呈现为「亮色模块 + 深色背景」，与手机 QQ 扫码所需的黑白对比一致。
 *
 * 渲染失败（例如 URL 过长）时返回 null，调用方应退化为直接输出链接。
 */
object TerminalQrRenderer {

    fun render(url: String): String? = encode(url)?.let(::render)

    internal fun encode(url: String): BitMatrix? = if (url.isBlank()) null else try {
        MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0)
    } catch (_: Exception) {
        null
    }

    internal fun renderPngBase64(matrix: BitMatrix): String? = try {
        val scale = 6
        val image = BufferedImage(matrix.width * scale, matrix.height * scale, BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            image.setRGB(x, y, if (matrix.get(x / scale, y / scale)) 0x000000 else 0xFFFFFF)
        }
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "PNG 编码器不可用" }
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
    } catch (_: Exception) {
        null
    }

    internal fun render(matrix: BitMatrix): String {
        val builder = StringBuilder((matrix.width + 1) * (matrix.height / 2 + 1))
        var y = 0
        while (y < matrix.height) {
            for (x in 0 until matrix.width) {
                // ZXing 的 matrix 中 true 表示深色模块
                val topLight = !matrix.get(x, y)
                val bottomLight = y + 1 < matrix.height && !matrix.get(x, y + 1)
                builder.append(
                    when {
                        topLight && bottomLight -> '█'
                        topLight -> '▀'
                        bottomLight -> '▄'
                        else -> ' '
                    }
                )
            }
            builder.append('\n')
            y += 2
        }
        return builder.toString()
    }
}
