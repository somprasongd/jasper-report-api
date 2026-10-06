package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import jakarta.annotation.PostConstruct;
import net.sf.jasperreports.engine.JRVirtualizer;
import net.sf.jasperreports.engine.fill.JRSwapFileVirtualizer;
import net.sf.jasperreports.engine.util.JRSwapFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * One swap-file virtualizer per render, so a report with thousands of pages keeps at most
 * {@code report.virtualizer.max-pages-in-memory} of them on the heap. The swap file is deleted when the render ends,
 * whatever the outcome; files a crashed process left behind are removed at start-up.
 */
@Component
public class RenderVirtualizers {

    private static final Logger log = LoggerFactory.getLogger(RenderVirtualizers.class);
    private static final int BLOCK_SIZE = 4096;
    private static final int MIN_GROW_COUNT = 1024;
    private static final Duration STALE_AFTER = Duration.ofHours(1);

    private final ReportProperties.Virtualizer settings;
    private final Path directory;

    public RenderVirtualizers(ReportProperties properties) {
        this.settings = properties.virtualizer();
        String configured = settings.directory();
        this.directory = (configured.isBlank() ? Path.of(properties.cache().workDir()).resolve("swap") : Path.of(configured))
                .toAbsolutePath().normalize();
    }

    @PostConstruct
    void removeStaleSwapFiles() {
        if (!settings.enabled() || !Files.isDirectory(directory)) {
            return;
        }
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().startsWith("swap_")).forEach(f -> {
                try {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(f);
                    }
                } catch (IOException e) {
                    log.warn("Could not remove stale swap file {}: {}", f, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not scan swap directory {}: {}", directory, e.getMessage());
        }
    }

    /** Never null; {@link Swap#virtualizer()} is null when virtualization is off or the swap file cannot be created. */
    public Swap open() {
        if (!settings.enabled()) {
            return Swap.NONE;
        }
        try {
            Files.createDirectories(directory);
            JRSwapFile file = new JRSwapFile(directory.toString(), BLOCK_SIZE, MIN_GROW_COUNT);
            return new Swap(new JRSwapFileVirtualizer(Math.max(1, settings.maxPagesInMemory()), file, true));
        } catch (IOException | RuntimeException e) {
            log.warn("Rendering without a virtualizer, swap file in {} cannot be created: {}", directory, e.getMessage());
            return Swap.NONE;
        }
    }

    public static final class Swap implements AutoCloseable {

        static final Swap NONE = new Swap(null);

        private final JRSwapFileVirtualizer virtualizer;

        private Swap(JRSwapFileVirtualizer virtualizer) {
            this.virtualizer = virtualizer;
        }

        public JRVirtualizer virtualizer() {
            return virtualizer;
        }

        /** Call once filling is done: pages are only read from now on, which lets the virtualizer skip write-backs. */
        public void readOnly() {
            if (virtualizer != null) {
                virtualizer.setReadOnly(true);
            }
        }

        /** Deletes the swap file; the filled report cannot be used afterwards. */
        @Override
        public void close() {
            if (virtualizer != null) {
                virtualizer.cleanup();
            }
        }
    }
}
