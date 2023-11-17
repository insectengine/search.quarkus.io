package io.quarkus.search.app.quarkusio;

import static io.quarkus.search.app.util.UncheckedIOFunction.uncheckedIO;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hibernate.search.util.common.impl.Closer;

import io.quarkus.search.app.util.CloseableDirectory;
import io.quarkus.search.app.util.GitInputProvider;
import io.quarkus.search.app.util.GitUtils;
import org.apache.commons.io.FilenameUtils;

import io.quarkus.search.app.QuarkusVersions;
import io.quarkus.search.app.asciidoc.Asciidoc;
import io.quarkus.search.app.entity.Guide;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevTree;

public class QuarkusIO implements AutoCloseable {

    public static final String SOURCE_BRANCH = "develop";
    public static final String PAGES_BRANCH = "master";
    private static final String QUARKUS_ORIGIN = "quarkus";

    public static URI httpUrl(URI urlBase, String version, String name) {
        return urlBase.resolve(httpPath(version, name));
    }

    public static String htmlPath(String version, String name) {
        return httpPath(version, name) + ".html";
    }

    private static String httpPath(String version, String name) {
        return QuarkusVersions.LATEST.equals(version) ? "guides/" + name
                : "version/" + version + "/guides/" + name;
    }

    public static String asciidocPath(String version, String name) {
        return QuarkusVersions.LATEST.equals(version) ? "_guides/" + name + ".adoc"
                : "_versions/" + version + "/guides/" + name + ".adoc";
    }

    public static Path yamlMetadataPath(String version) {
        return Path.of("_data", "versioned", version.replace('.', '-'), "index", "quarkus.yaml");
    }

    public static Path yamlQuarkiverseMetadataPath(String version) {
        return Path.of("_data", "versioned", version.replace('.', '-'), "index", "quarkiverse.yaml");
    }

    private final URI webUri;
    private final CloseableDirectory directory;
    private final Git git;
    private final RevTree pagesTree;
    private final Map<GuidesDirectory, BiConsumer<Path, Guide>> guidesMetadata = new HashMap<>();

    public QuarkusIO(QuarkusIOConfig config, CloseableDirectory directory, Git git) throws IOException {
        this.webUri = config.webUri();
        this.directory = directory;
        this.git = git;
        this.pagesTree = GitUtils.firstExistingRevTree(git.getRepository(), "origin/" + PAGES_BRANCH);
    }

    @Override
    public void close() throws Exception {
        try (var closer = new Closer<Exception>()) {
            closer.push(Git::close, git);
            closer.push(CloseableDirectory::close, directory);
            closer.push(Map::clear, guidesMetadata);
        }
    }

    @SuppressWarnings("resource")
    public Stream<Guide> guides() throws IOException {
        return Stream.concat(
                localGuides(),
                quarkiverseGuides());
    }

    private Stream<Guide> localGuides() throws IOException {
        return guideDirectories()
                .flatMap(uncheckedIO(guidesDirectory -> Files.list(guidesDirectory.path)
                        .filter(path -> {
                            String filename = path.getFileName().toString();
                            return !filename.startsWith("_") && !FilenameUtils.getBaseName(filename).equals(
                                    "README")
                                    && FilenameUtils.isExtension(filename, "adoc");
                        })
                        .map(path -> parseGuide(guidesDirectory, path))));
    }

    private Stream<Guide> quarkiverseGuides() throws IOException {
        return Stream.concat(
                quarkiverseDirectories()
                        .flatMap(quarkiverse -> QuarkiverseMetadata
                                .parseYamlMetadata(webUri, quarkiverse.path, quarkiverse.version)
                                .createQuarkiverseGuides()),
                quarkiverseLegacyDirectories()
                        .flatMap(quarkiverse -> QuarkiverseMetadata
                                .parseYamlLegacyMetadata(webUri, quarkiverse.path, quarkiverse.version)
                                .createQuarkiverseGuides()));
    }

    @SuppressWarnings("resource")
    private Stream<GuidesDirectory> guideDirectories() throws IOException {
        return Stream.concat(
                Stream.of(new GuidesDirectory(QuarkusVersions.LATEST, directory.path().resolve("_guides"))),
                Files.list(directory.path().resolve("_versions"))
                        .map(p -> {
                            var version = p.getFileName().toString();
                            return new GuidesDirectory(version, p.resolve("guides"));
                        }));
    }

    private Stream<GuidesDirectory> quarkiverseDirectories() throws IOException {
        return Files.list(directory.path().resolve("_data").resolve("versioned"))
                .map(p -> {
                    var version = p.getFileName().toString().replace('-', '.');
                    Path quarkiverse = p.resolve("index").resolve("quarkiverse.yaml");
                    return Files.exists(quarkiverse) ? new GuidesDirectory(version, quarkiverse) : null;
                })
                .filter(Objects::nonNull);
    }

    private Stream<GuidesDirectory> quarkiverseLegacyDirectories() throws IOException {
        return Files.list(directory.path().resolve("_data"))
                .filter(p -> !Files.isDirectory(p) && p.getFileName().toString().startsWith("guides-"))
                .map(p -> {
                    var version = p.getFileName().toString().replaceAll("guides-|\\.yaml", "").replace('-', '.');
                    return new GuidesDirectory(version, p);
                });
    }

    record GuidesDirectory(String version, Path path) {
    }

    private Guide parseGuide(GuidesDirectory guidesDirectory, Path path) {
        var guide = new Guide();
        guide.version = guidesDirectory.version;
        guide.origin = QUARKUS_ORIGIN;
        String name = FilenameUtils.removeExtension(path.getFileName().toString());
        guide.url = httpUrl(webUri, guidesDirectory.version, name);
        guide.htmlFullContentProvider = new GitInputProvider(git, pagesTree, htmlPath(guidesDirectory.version, name));
        getMetadata(guidesDirectory).accept(path, guide);
        return guide;
    }

    private BiConsumer<Path, Guide> getMetadata(GuidesDirectory guidesDirectory) {
        return guidesMetadata.computeIfAbsent(guidesDirectory, key -> {
            try {
                QuarkusIOMetadata quarkusIOMetadata = parseMetadata(key);
                return quarkusIOMetadata::addMetadata;
            } catch (Exception e) {
                // not all versions (e.g. 2.7) have the quarkus.yml file. For those we are falling back to parsing all the data from an asciidoc file
                return (path, guide) -> Asciidoc.parse(path, title -> guide.title = title,
                        Map.of("summary", summary -> guide.summary = summary,
                                "keywords", keywords -> guide.keywords = keywords,
                                "categories", categories -> guide.categories = toSet(categories),
                                "topics", topics -> guide.topics = toSet(topics),
                                "extensions", extensions -> guide.extensions = toSet(extensions)));
            }
        });
    }

    private QuarkusIOMetadata parseMetadata(GuidesDirectory guidesDirectory) {
        return QuarkusIOMetadata.parseYamlMetadata(directory.path().resolve(yamlMetadataPath(guidesDirectory.version())));
    }

    private static Set<String> toSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
