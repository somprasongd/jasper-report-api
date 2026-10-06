package com.github.somprasongd.jasperreport.api.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Housekeeping for the per-version folders under {@code report.cache.work-dir}. */
final class WorkDirs {

    private static final Logger log = LoggerFactory.getLogger(WorkDirs.class);

    private WorkDirs() {
    }

    /** Keeps {@code current} plus the newest other versions, {@code keep} in total. */
    static void prune(Path bundleRoot, Path current, int keep) {
        try (Stream<Path> versions = Files.list(bundleRoot)) {
            List<Path> old = versions.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith(".") && !p.equals(current))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .skip(Math.max(1, keep) - 1L)
                    .toList();
            for (Path path : old) {
                deleteRecursively(path);
            }
        } catch (IOException e) {
            log.warn("Could not prune old bundle versions in {}: {}", bundleRoot, e.getMessage());
        }
    }

    static void deleteRecursively(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
