package com.flamingo.comm.llp.spec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads the vendored LLP 4.0.0 conformance corpus from the test classpath.
 *
 * <p>The JSON files are copied verbatim from {@code llp-spec/vectors/} by
 * {@code tools/sync-vectors.sh}. {@code VectorManifestTest} checks them against
 * {@code MANIFEST.sha256}, so the copy cannot drift silently.</p>
 */
public final class VectorLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VectorLoader() {
    }

    /**
     * Loads every case of every vector file under the resource directory.
     *
     * @param resourcePath directory on the test classpath, for example {@code llp-vectors}
     * @return all cases, in file order
     * @throws IOException if the directory or a file cannot be read
     */
    public static List<TestCase> loadAll(String resourcePath) throws IOException {
        List<TestCase> cases = new ArrayList<>();
        for (Path file : jsonFiles(resourcePath)) {
            cases.addAll(loadFile(file));
        }
        return cases;
    }

    /**
     * Loads the cases of the given kind, across every vector file.
     *
     * @param resourcePath directory on the test classpath
     * @param kind         case kind
     * @return matching cases
     * @throws IOException if a file cannot be read
     */
    public static List<TestCase> loadByKind(String resourcePath, TestCase.Kind kind) throws IOException {
        return loadAll(resourcePath).stream()
                .filter(c -> c.kind() == kind)
                .toList();
    }

    private static List<TestCase> loadFile(Path path) throws IOException {
        JsonNode root = MAPPER.readTree(path.toFile());
        JsonNode spec = root.get("spec");
        if (spec == null || !"llp/4.0.0".equals(spec.asString())) {
            throw new IOException("not an LLP 4.0.0 vector file: " + path.getFileName()
                    + " (stale file from an older corpus? run: mvn clean)");
        }
        List<TestCase> cases = new ArrayList<>();
        for (JsonNode node : root.get("cases")) {
            cases.add(TestCase.from(path.getFileName().toString(), node));
        }
        return cases;
    }

    private static List<Path> jsonFiles(String resourcePath) throws IOException {
        URL url = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IOException("Resource not found on classpath: " + resourcePath);
        }
        Path root;
        try {
            root = Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Invalid resource URI: " + url, e);
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    /**
     * Directory that holds the vendored corpus, for the manifest check.
     *
     * @param resourcePath directory on the test classpath
     * @return the resolved directory
     * @throws IOException if it does not exist
     */
    public static Path directory(String resourcePath) throws IOException {
        URL url = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IOException("Resource not found on classpath: " + resourcePath);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Invalid resource URI: " + url, e);
        }
    }
}
