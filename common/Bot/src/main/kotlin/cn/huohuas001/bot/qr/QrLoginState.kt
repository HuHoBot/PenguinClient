package cn.huohuas001.bot.qr

/** Public QR progress never includes the task encryption key or application secret. */
data class QrLoginState(
    val status: String,
    val qrCodeUrl: String? = null,
    val qrImageBase64: String? = null,
    val message: String? = null
)
