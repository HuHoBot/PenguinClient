package cn.huohuas001.bot.service

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarAuthenticationServiceTest {

    @Test
    fun `identical avatars have perfect similarity`() {
        val avatar = avatar()

        assertEquals(1.0, AvatarAuthenticationService.imageSimilarity(avatar, copyOf(avatar)))
    }

    @Test
    fun `phash matches Python reference vectors`() {
        val expected = mapOf(
            (100 to 100) to 0xa7f20551a7f0a6f0UL.toLong(),
            (160 to 100) to 0xd1fc4154f1f481f4UL.toLong(),
            (64 to 64) to 0xc710ca194b1eb7e6UL.toLong()
        )

        expected.forEach { (size, pythonHash) ->
            val image = referencePattern(size.first, size.second)

            assertEquals(pythonHash, AvatarAuthenticationService.phash(image), "size $size")
        }
    }

    @Test
    fun `four pixel corner detail is detected like Python phash`() {
        val original = referencePattern(128, 128)
        val altered = copyOf(original)
        val graphics = altered.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, 4, 4)
        graphics.dispose()

        val similarity = AvatarAuthenticationService.imageSimilarity(original, altered)

        assertTrue(similarity < AvatarAuthenticationService.MIN_SIMILARITY, "similarity was $similarity")
    }

    @Test
    fun `non-square input is resized without the Java-only center crop`() {
        val original = BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB)
        val graphics = original.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, original.width, original.height)
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, 12, original.height)
        graphics.dispose()
        val altered = copyOf(original)
        val alteredGraphics = altered.createGraphics()
        alteredGraphics.color = Color.RED
        alteredGraphics.fillRect(0, 40, 5, 5)
        alteredGraphics.dispose()

        assertTrue(
            AvatarAuthenticationService.imageSimilarity(original, altered) < AvatarAuthenticationService.MIN_SIMILARITY
        )
    }

    private fun referencePattern(width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    image.setRGB(x, y, Color(
                        (x * 17 + y * 3) % 256,
                        (x * 5 + y * 11) % 256,
                        (x * 13 + y * 7) % 256
                    ).rgb)
                }
            }
        }

    private fun avatar(): BufferedImage = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB).also { image ->
        val graphics = image.createGraphics()
        graphics.color = Color(48, 125, 185)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.color = Color(242, 215, 110)
        graphics.fillOval(25, 20, 78, 88)
        graphics.dispose()
    }

    private fun copyOf(source: BufferedImage): BufferedImage = BufferedImage(
        source.width,
        source.height,
        source.type
    ).also { copy ->
        val graphics = copy.createGraphics()
        graphics.drawImage(source, 0, 0, null)
        graphics.dispose()
    }
}
