package com.imagetoexcel.infrastructure

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile

@Component
class ImagePreprocessor {

    fun preprocess(file: MultipartFile): ByteArray {
        val originalBytes = file.bytes

        // 1. 이미지를 BufferedImage로 변환
        val inputStream = ByteArrayInputStream(originalBytes)
        val originalImage: BufferedImage = ImageIO.read(inputStream) ?: return originalBytes

        // 너무 큰 이미지는 스케일 업을 하지 않거나 제한
        val width = originalImage.width
        val height = originalImage.height

        // OCR 권장 해상도(약 1024~1200 지원)보다 작은 경우에만 확대
        val scaleFactor = if (width < 800 || height < 800) 2.0 else 1.0

        if (scaleFactor <= 1.0) {
            return originalBytes // 보정이 필요 없다고 판단되면 원본 반환
        }

        val newWidth = (width * scaleFactor).toInt()
        val newHeight = (height * scaleFactor).toInt()

        // 2. 새로운 해상도의 이미지 버퍼 생성
        val scaledImage = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val g2d: Graphics2D = scaledImage.createGraphics()

        // 3. 렌더링 품질 설정 (Bicubic 보간법 등 적용하여 글씨가 깨지지 않게 보정)
        g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
        )
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // 4. 이미지 그리기 (스케일 업)
        g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null)
        g2d.dispose()

        // 5. 다시 ByteArray로 반환
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(scaledImage, "jpg", outputStream)

        return outputStream.toByteArray()
    }
}
