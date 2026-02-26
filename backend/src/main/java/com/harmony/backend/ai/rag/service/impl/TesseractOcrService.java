package com.harmony.backend.ai.rag.service.impl;

import com.harmony.backend.ai.rag.config.RagOcrProperties;
import com.harmony.backend.ai.rag.service.OcrService;
import lombok.RequiredArgsConstructor;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

@Service
@RequiredArgsConstructor
public class TesseractOcrService implements OcrService {

    private final RagOcrProperties properties;

    @Override
    public String extractText(byte[] imageBytes, String originalFilename, String contentType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return "";
        }
        if (!properties.isEnabled()) {
            return "";
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                return "";
            }
            BufferedImage processed = preprocess(image);
            Tesseract tesseract = new Tesseract();
            if (StringUtils.hasText(properties.getTessdataPath())) {
                tesseract.setDatapath(properties.getTessdataPath());
            }
            if (StringUtils.hasText(properties.getLanguage())) {
                tesseract.setLanguage(properties.getLanguage());
            }
            tesseract.setTessVariable("user_defined_dpi", "300");
            String text = tesseract.doOCR(processed);
            return text == null ? "" : text.trim();
        } catch (TesseractException e) {
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private BufferedImage preprocess(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        int targetW = Math.max(1, width * 2);
        int targetH = Math.max(1, height * 2);
        Image scaled = src.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return binarize(out);
    }

    private BufferedImage binarize(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        int threshold = 160;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                int gray = rgb & 0xFF;
                int val = gray > threshold ? 0xFFFFFF : 0x000000;
                out.setRGB(x, y, val);
            }
        }
        return out;
    }
}
