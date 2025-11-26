package com.examprep.core.processor.impl;

import com.examprep.core.model.ExtractionResult;
import com.examprep.core.processor.DocumentProcessor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class ImageProcessor implements DocumentProcessor {
    
    private final Tesseract tesseract;
    
    public ImageProcessor() {
        this.tesseract = new Tesseract();
        this.tesseract.setDatapath(System.getenv("TESSDATA_PREFIX"));
        this.tesseract.setLanguage("eng");
    }
    
    @Override
    public boolean supports(String fileType) {
        return "png".equalsIgnoreCase(fileType) || 
               "jpg".equalsIgnoreCase(fileType) || 
               "jpeg".equalsIgnoreCase(fileType);
    }
    
    @Override
    public ExtractionResult extract(InputStream inputStream, String fileType) throws Exception {
        try {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IllegalArgumentException("Could not read image file");
            }
            
            String text = tesseract.doOCR(image);
            
            List<String> pageContents = new ArrayList<>();
            List<String> pageTitles = new ArrayList<>();
            
            pageContents.add(text != null ? text.trim() : "");
            pageTitles.add(null);
            
            return ExtractionResult.builder()
                .pageContents(pageContents)
                .pageTitles(pageTitles)
                .totalPages(1)
                .build();
        } catch (TesseractException e) {
            log.error("OCR processing failed", e);
            throw new RuntimeException("Failed to process image with OCR", e);
        }
    }
}

