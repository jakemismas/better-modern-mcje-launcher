/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.ui;

import lombok.extern.java.Log;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A full-window background panel that displays auto-scrolling screenshots.
 * Screenshots are loaded from instance folders and displayed with crossfade transitions.
 *
 * All state is accessed only from the EDT to avoid threading issues.
 */
@Log
public class ScreenshotCarousel extends JPanel {

    private static final Color FALLBACK_BACKGROUND = new Color(30, 30, 30);
    private static final int TRANSITION_DURATION_MS = 1000;
    private static final int DISPLAY_DURATION_MS = 7000;

    private final File baseDir;
    private final List<File> screenshotFiles = new ArrayList<>();
    private BufferedImage currentImage;
    private BufferedImage nextImage;
    private float transitionAlpha = 1.0f;
    private boolean isTransitioning = false;
    private int currentIndex = 0;

    private Timer displayTimer;
    private Timer transitionTimer;

    public ScreenshotCarousel(File baseDir) {
        this.baseDir = baseDir;
        setOpaque(true);
        setBackground(FALLBACK_BACKGROUND);

        // Load screenshots in background
        SwingWorker<List<File>, Void> loader = new SwingWorker<List<File>, Void>() {
            @Override
            protected List<File> doInBackground() {
                return scanForScreenshots();
            }

            @Override
            protected void done() {
                try {
                    List<File> files = get();
                    screenshotFiles.clear();
                    screenshotFiles.addAll(files);
                    if (!screenshotFiles.isEmpty()) {
                        loadCurrentImage();
                        startCarousel();
                    }
                } catch (Exception e) {
                    log.warning("Failed to scan for screenshots: " + e.getMessage());
                }
            }
        };
        loader.execute();
    }

    private List<File> scanForScreenshots() {
        List<File> files = new ArrayList<>();

        File instancesDir = new File(baseDir, "instances");
        if (!instancesDir.exists() || !instancesDir.isDirectory()) {
            log.info("Instances directory not found: " + instancesDir);
            return files;
        }

        // Scan each instance for screenshots
        File[] instances = instancesDir.listFiles(File::isDirectory);
        if (instances == null) return files;

        for (File instance : instances) {
            File screenshotsDir = new File(instance, "minecraft/screenshots");
            if (screenshotsDir.exists() && screenshotsDir.isDirectory()) {
                File[] screenshots = screenshotsDir.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        String lower = name.toLowerCase();
                        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
                    }
                });

                if (screenshots != null) {
                    for (File screenshot : screenshots) {
                        files.add(screenshot);
                    }
                }
            }
        }

        // Shuffle for variety
        if (!files.isEmpty()) {
            Collections.shuffle(files, new Random());
            log.info("Found " + files.size() + " screenshots");
        }

        return files;
    }

    private void loadCurrentImage() {
        if (screenshotFiles.isEmpty()) return;

        try {
            currentImage = ImageIO.read(screenshotFiles.get(currentIndex));
            repaint();
        } catch (Exception e) {
            log.warning("Failed to load screenshot: " + screenshotFiles.get(currentIndex));
            currentImage = null;
        }
    }

    private void startCarousel() {
        if (screenshotFiles.size() <= 1) return;

        displayTimer = new Timer(DISPLAY_DURATION_MS, e -> transitionToNext());
        displayTimer.start();
    }

    private void transitionToNext() {
        if (screenshotFiles.isEmpty() || isTransitioning) return;

        // Load next image in background, then start transition on EDT
        final int nextIndex = (currentIndex + 1) % screenshotFiles.size();
        SwingWorker<BufferedImage, Void> loader = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return ImageIO.read(screenshotFiles.get(nextIndex));
            }

            @Override
            protected void done() {
                try {
                    nextImage = get();
                } catch (Exception e) {
                    log.warning("Failed to load next screenshot");
                    nextImage = null;
                    currentIndex = nextIndex;
                    return;
                }

                // Start transition on EDT
                isTransitioning = true;
                transitionAlpha = 0.0f;

                transitionTimer = new Timer(16, evt -> { // ~60fps
                    transitionAlpha += 16.0f / TRANSITION_DURATION_MS;
                    if (transitionAlpha >= 1.0f) {
                        transitionAlpha = 1.0f;
                        currentImage = nextImage;
                        nextImage = null;
                        currentIndex = (currentIndex + 1) % screenshotFiles.size();
                        isTransitioning = false;
                        transitionTimer.stop();
                    }
                    repaint();
                });
                transitionTimer.start();
            }
        };
        loader.execute();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        int width = getWidth();
        int height = getHeight();

        // Draw fallback background
        g2d.setColor(FALLBACK_BACKGROUND);
        g2d.fillRect(0, 0, width, height);

        // Draw current image (cover fill)
        if (currentImage != null) {
            if (isTransitioning && nextImage != null) {
                // Draw current image fading out
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f - transitionAlpha));
                drawCoverImage(g2d, currentImage, width, height);

                // Draw next image fading in
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, transitionAlpha));
                drawCoverImage(g2d, nextImage, width, height);
            } else {
                drawCoverImage(g2d, currentImage, width, height);
            }
        }

        // Add slight dark overlay for better text readability
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, width, height);

        g2d.dispose();
    }

    private void drawCoverImage(Graphics2D g2d, BufferedImage image, int containerWidth, int containerHeight) {
        if (image == null) return;

        double imgRatio = (double) image.getWidth() / image.getHeight();
        double containerRatio = (double) containerWidth / containerHeight;

        int drawWidth, drawHeight, drawX, drawY;

        if (imgRatio > containerRatio) {
            // Image is wider - fit height, crop width
            drawHeight = containerHeight;
            drawWidth = (int) (containerHeight * imgRatio);
            drawX = (containerWidth - drawWidth) / 2;
            drawY = 0;
        } else {
            // Image is taller - fit width, crop height
            drawWidth = containerWidth;
            drawHeight = (int) (containerWidth / imgRatio);
            drawX = 0;
            drawY = (containerHeight - drawHeight) / 2;
        }

        g2d.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
    }

    /**
     * Refresh the list of screenshots.
     */
    public void refresh() {
        SwingWorker<List<File>, Void> loader = new SwingWorker<List<File>, Void>() {
            @Override
            protected List<File> doInBackground() {
                return scanForScreenshots();
            }

            @Override
            protected void done() {
                try {
                    List<File> files = get();
                    screenshotFiles.clear();
                    screenshotFiles.addAll(files);
                    if (!screenshotFiles.isEmpty() && currentImage == null) {
                        loadCurrentImage();
                        startCarousel();
                    }
                } catch (Exception e) {
                    log.warning("Failed to refresh screenshots: " + e.getMessage());
                }
            }
        };
        loader.execute();
    }

    /**
     * Stop the carousel and release resources.
     */
    public void stop() {
        if (displayTimer != null) {
            displayTimer.stop();
        }
        if (transitionTimer != null) {
            transitionTimer.stop();
        }
    }
}
