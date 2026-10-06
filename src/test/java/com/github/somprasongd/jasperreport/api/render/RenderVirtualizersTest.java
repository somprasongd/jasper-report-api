package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RenderVirtualizersTest {

    @TempDir
    Path dir;

    private static RenderVirtualizers virtualizers(Map<String, String> values) {
        ReportProperties properties = new Binder(new MapConfigurationPropertySource(values)).bind("report", ReportProperties.class).get();
        return new RenderVirtualizers(properties);
    }

    private RenderVirtualizers virtualizers(String key, String value) {
        Map<String, String> values = new HashMap<>(Map.of("report.virtualizer.directory", dir.toString()));
        if (key != null) {
            values.put(key, value);
        }
        return virtualizers(values);
    }

    private long files() throws Exception {
        try (Stream<Path> list = Files.list(dir)) {
            return list.count();
        }
    }

    @Test
    void swapFileLivesUntilTheRenderIsClosed() throws Exception {
        try (RenderVirtualizers.Swap swap = virtualizers(null, null).open()) {
            assertThat(swap.virtualizer()).isNotNull();
            assertThat(files()).isEqualTo(1);
        }
        assertThat(files()).isZero();
    }

    @Test
    void disabledMeansNoVirtualizerAndNoFile() throws Exception {
        try (RenderVirtualizers.Swap swap = virtualizers("report.virtualizer.enabled", "false").open()) {
            assertThat(swap.virtualizer()).isNull();
            swap.readOnly(); // harmless without a virtualizer
        }
        assertThat(files()).isZero();
    }

    @Test
    void anUnusableDirectoryFallsBackToRenderingWithoutVirtualizer() throws Exception {
        Path blocked = Files.writeString(dir.resolve("a-file"), "not a directory");
        RenderVirtualizers virtualizers = virtualizers(Map.of("report.virtualizer.directory", blocked.resolve("swap").toString()));
        try (RenderVirtualizers.Swap swap = virtualizers.open()) {
            assertThat(swap.virtualizer()).isNull();
        }
    }

    @Test
    void swapFilesLeftByACrashedProcessAreRemovedAtStartup() throws Exception {
        Path stale = Files.writeString(dir.resolve("swap_stale.data"), "x");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));
        Path fresh = Files.writeString(dir.resolve("swap_fresh.data"), "x");
        Path other = Files.writeString(dir.resolve("keep.txt"), "x");
        Files.setLastModifiedTime(other, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        virtualizers(null, null).removeStaleSwapFiles();

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists(); // could belong to a render running in another process
        assertThat(other).exists(); // not ours
    }
}
