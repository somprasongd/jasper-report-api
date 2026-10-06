package com.github.somprasongd.jasperreport.api.render;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.somprasongd.jasperreport.api.source.ResolvedBundle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Gives each report folder version its own resource lookup root so {@code resourceBundle="messages"} finds
 * {@code messages.properties}, {@code messages_en.properties}, ... next to the JRXML, for the report and its
 * sub-reports (JasperReports then falls back from {@code th_TH} to {@code th} to the base file by itself).
 * <p>
 * The folder is searched <em>before</em> the application class path, so a generic name such as {@code messages}
 * cannot be shadowed by a file inside one of the libraries. A new loader per folder version also means edited
 * message files are picked up: {@link java.util.ResourceBundle} caches per class loader.
 */
@Component
public class BundleClassLoaders {

    private final Cache<String, ClassLoader> loaders = Caffeine.newBuilder().maximumSize(200).build();

    public ClassLoader forBundle(ResolvedBundle bundle) {
        return loaders.get(bundle.cacheKey(), key -> create(bundle));
    }

    private static ClassLoader create(ResolvedBundle bundle) {
        try {
            return new DirectoryFirstClassLoader(bundle.dir().toUri().toURL(), BundleClassLoaders.class.getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class DirectoryFirstClassLoader extends URLClassLoader {

        DirectoryFirstClassLoader(URL dir, ClassLoader parent) {
            super(new URL[]{dir}, parent);
        }

        @Override
        public URL getResource(String name) {
            URL own = findResource(name);
            return own != null ? own : super.getResource(name);
        }

        @Override
        public void close() throws IOException {
            super.close();
        }
    }
}
