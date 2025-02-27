package com.github.sco1237896.tools.maven.connector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.github.sco1237896.tools.maven.connector.model.ConnectorDefinition;
import com.github.sco1237896.tools.maven.connector.support.Annotation;
import com.github.sco1237896.tools.maven.connector.support.AppBootstrapProvider;
import com.github.sco1237896.tools.maven.connector.support.CatalogConstants;
import com.github.sco1237896.tools.maven.connector.support.CatalogSupport;
import com.github.sco1237896.tools.maven.connector.support.Connector;
import com.github.sco1237896.tools.maven.connector.support.ConnectorIndex;
import com.github.sco1237896.tools.maven.connector.support.ConnectorManifest;
import com.github.sco1237896.tools.maven.connector.support.KameletsCatalog;
import com.github.sco1237896.tools.maven.connector.support.MojoSupport;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.SetUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import com.github.sco1237896.tools.maven.connector.validator.Validator;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.impl.RemoteRepositoryManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import io.quarkus.bootstrap.BootstrapException;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.maven.dependency.ResolvedDependency;
import net.javacrumbs.jsonunit.assertj.JsonAssertions;
import net.javacrumbs.jsonunit.core.Option;

import static java.util.Optional.ofNullable;
import static com.github.sco1237896.tools.maven.connector.support.CatalogSupport.asKey;

@Mojo(name = "generate-catalog", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class GenerateCatalogMojo extends AbstractMojo {

    @Parameter(defaultValue = "false", property = "connectors.catalog.skip")
    private boolean skip = false;

    @Parameter(defaultValue = "false", property = "connector.groups")
    private boolean groups = false;

    @Parameter(readonly = true, defaultValue = "${project}")
    private MavenProject project;

    @Parameter
    private List<Annotation> defaultAnnotations;
    @Parameter
    private List<Annotation> annotations;

    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;
    @Parameter
    private Connector defaults;
    @Parameter
    private List<Connector> connectors;

    @Parameter(defaultValue = "true", property = "connectors.catalog.validate")
    private boolean validate;

    @Parameter(defaultValue = "${project.artifactId}", property = "connector.type")
    private String type;
    @Parameter(defaultValue = "${project.version}", property = "connector.version")
    private String version;
    @Parameter(defaultValue = "0", property = "connector.initial-revision")
    private int initialRevision;
    @Parameter(defaultValue = "connector", property = "connector.container.image-prefix")
    private String containerImagePrefix;
    @Parameter(property = "connector.container.registry")
    private String containerImageRegistry;
    @Parameter(defaultValue = "${project.groupId}", property = "connector.container.organization")
    private String containerImageOrg;
    @Parameter(property = "connector.container.image.base")
    private String containerImageBase;
    @Parameter(property = "connector.container.tag", required = true)
    private String containerImageTag;

    @Parameter
    private List<File> validators;
    @Parameter(defaultValue = "FAIL", property = "connectors.catalog.validation.mode")
    private Validator.Mode mode;

    @Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/connectors")
    private File definitionPathLocal;
    @Parameter(defaultValue = "${connector.catalog.root}/${connectors.catalog.name}")
    private File definitionPath;
    @Parameter(defaultValue = "${connector.catalog.root}")
    private File indexPath;
    @Parameter(defaultValue = "${connectors.catalog.name}")
    private String catalogName;

    @Parameter(required = false, property = "appArtifact")
    private String appArtifact;
    @Parameter(defaultValue = "${project.build.directory}")
    protected File buildDir;
    @Parameter(defaultValue = "${project.build.finalName}")
    protected String finalName;
    @Parameter(defaultValue = "${camel-quarkus.version}")
    private String camelQuarkusVersion;
    @Requirement(role = RepositorySystem.class, optional = false)
    protected RepositorySystem repoSystem;
    @Requirement(role = RemoteRepositoryManager.class, optional = false)
    protected RemoteRepositoryManager remoteRepoManager;
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;
    @Parameter
    private Map<String, String> systemProperties;

    @Component
    protected MavenProjectHelper projectHelper;

    ConnectorManifest manifest;
    String manifestId;
    Path manifestFile;
    Path manifestLocalFile;
    Path indexFile;
    ConnectorIndex index;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping generate-catalog");
        }

        try {
            this.manifestId = type.replace("-", "_");
            this.indexFile = indexPath.toPath().resolve("connectors.json");
            this.manifestFile = definitionPath.toPath().resolve(this.manifestId + ".json");
            this.manifestLocalFile = definitionPathLocal.toPath().resolve(this.manifestId + ".json");
            this.index = MojoSupport.load(indexFile, ConnectorIndex.class, ConnectorIndex::new);

            this.manifest = index.getConnectors().computeIfAbsent(this.manifestId, k -> {
                return new ConnectorManifest(
                        this.catalogName,
                        this.initialRevision,
                        Collections.emptySet(),
                        null,
                        this.containerImageBase,
                        null);
            });

            final KameletsCatalog kameletsCatalog = KameletsCatalog.get(project, getLog());
            final List<Connector> connectorList = MojoSupport.inject(session, defaults, connectors);

            //
            // Update manifest dependencies
            //

            TreeSet<String> newDependencies = new TreeSet<>(dependencies());

            if (!this.manifest.getDependencies().equals(newDependencies)) {
                SetUtils.SetView<String> diff = SetUtils.difference(this.manifest.getDependencies(), newDependencies);
                if (diff.isEmpty()) {
                    diff = SetUtils.difference(newDependencies, this.manifest.getDependencies());
                }

                if (!diff.isEmpty()) {
                    getLog().info("Detected diff in dependencies (" + diff.size() + "):");
                    diff.forEach(d -> {
                        getLog().info("  " + d);
                    });
                } else {
                    getLog().info("Detected diff in dependencies (" + diff.size() + ")");
                }

                this.manifest.bump();
                this.manifest.getDependencies().clear();
                this.manifest.getDependencies().addAll(newDependencies);
            }

            if (!Objects.equals(manifest.getBaseImage(), this.containerImageBase)) {
                getLog().info("Detected diff in base image");

                this.manifest.setBaseImage(this.containerImageBase);
                this.manifest.bump();
            }

            //
            // Connectors
            //

            for (Connector connector : connectorList) {
                ConnectorDefinition def = generateDefinitions(kameletsCatalog, connector);

                this.manifest.getTypes().add(def.getConnectorType().getId());
            }

            //
            // Manifest
            //

            getLog().info("Writing connector manifest to: " + manifestLocalFile);

            this.manifest.setImage(
                    String.format("%s/%s/%s-%s:%s",
                            this.containerImageRegistry,
                            this.containerImageOrg,
                            this.containerImagePrefix, this.type,
                            this.containerImageTag));

            CatalogSupport.JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                    Files.newBufferedWriter(manifestLocalFile),
                    this.manifest);

        } catch (Exception e) {
            throw new MojoExecutionException(e);
        }
    }

    private ConnectorDefinition generateDefinitions(KameletsCatalog kamelets, Connector connector)
            throws MojoExecutionException, MojoFailureException {

        final Connector.EndpointRef kafka = connector.getKafka();
        final Connector.EndpointRef adapter = connector.getAdapter();

        if (kafka.getPrefix() == null) {
            throw new MojoExecutionException("Kamelet Kafka prefix is required");
        }
        if (!Character.isLetterOrDigit(kafka.getPrefix().charAt(kafka.getPrefix().length() - 1))) {
            throw new MojoExecutionException("Kamelet Kafka prefix should end with a letter or digit");
        }
        if (adapter.getPrefix() == null) {
            throw new MojoExecutionException("Kamelet Adapter prefix is required");
        }
        if (!Character.isLetterOrDigit(adapter.getPrefix().charAt(connector.getAdapter().getPrefix().length() - 1))) {
            throw new MojoExecutionException("Kamelet Adapter prefix should end with a letter or digit");
        }

        try {
            final ObjectNode adapterSpec = kamelets.kamelet(
                    adapter.getName(),
                    adapter.getVersion());
            final ObjectNode kafkaSpec = kamelets.kamelet(
                    kafka.getName(),
                    kafka.getVersion());

            final String version = ofNullable(connector.getVersion()).orElseGet(project::getVersion);
            final String name = ofNullable(connector.getName()).orElseGet(project::getArtifactId);
            final String title = ofNullable(connector.getTitle()).orElseGet(project::getName);
            final String description = ofNullable(connector.getDescription()).orElseGet(project::getDescription);
            final String type = CatalogSupport.kameletType(adapterSpec);
            final String id = name.replace("-", "_");

            final Path definitionFile = definitionPath.toPath().resolve(id + ".json");
            final Path definitionLocalFile = definitionPathLocal.toPath().resolve(id + ".json");

            ConnectorDefinition def = new ConnectorDefinition();
            def.getConnectorType().setId(id);
            def.getConnectorType().setKind("ConnectorType");
            def.getConnectorType().setIconRef("TODO");
            def.getConnectorType().setName(title);
            def.getConnectorType().setDescription(description);
            def.getConnectorType().setVersion(version);
            def.getConnectorType().getLabels().add(CatalogSupport.kameletType(adapterSpec));
            def.getConnectorType().setSchema(CatalogSupport.JSON_MAPPER.createObjectNode());
            def.getConnectorType().getSchema().put("type", "object");
            def.getConnectorType().getSchema().put("additionalProperties", false);

            //
            // Adapter
            //

            CatalogSupport.addRequired(
                    groups,
                    adapter,
                    adapterSpec,
                    def.getConnectorType().getSchema());
            CatalogSupport.copyProperties(
                    groups,
                    adapter,
                    adapterSpec,
                    def.getConnectorType().getSchema());

            //
            // Kafka
            //

            CatalogSupport.addRequired(
                    groups,
                    kafka,
                    kafkaSpec,
                    def.getConnectorType().getSchema());
            CatalogSupport.copyProperties(
                    groups,
                    kafka,
                    kafkaSpec,
                    def.getConnectorType().getSchema());

            //
            // DataShape
            //

            var ds = connector.getDataShape();
            if (ds == null) {
                ds = new Connector.DataShapeDefinition();
            }

            CatalogSupport.computeDataShapes(ds, adapterSpec);

            CatalogSupport.dataShape(ds.getConsumes(), def, Connector.DataShape.Type.CONSUMES);
            CatalogSupport.dataShape(ds.getProduces(), def, Connector.DataShape.Type.PRODUCES);

            //
            // ErrorHandler
            //

            if (connector.getErrorHandler() != null && connector.getErrorHandler().getStrategies() != null) {
                CatalogSupport.computeErrorHandler(def, connector);
            }

            // force capabilities if defined
            if (connector.getCapabilities() != null) {
                def.getConnectorType().getCapabilities().addAll(connector.getCapabilities());
            }

            for (String capability : def.getConnectorType().getCapabilities()) {
                switch (capability) {
                    case CatalogConstants.CAPABILITY_PROCESSORS:
                        def.getConnectorType().getSchema()
                                .with("properties")
                                .with(CatalogConstants.CAPABILITY_PROCESSORS);
                        break;
                    case CatalogConstants.CAPABILITY_ERROR_HANDLER:
                        def.getConnectorType().getSchema()
                                .with("properties")
                                .with(CatalogConstants.CAPABILITY_ERROR_HANDLER);
                        break;
                    case CatalogConstants.CAPABILITY_DATA_SHAPE:
                        def.getConnectorType().getSchema()
                                .with("properties")
                                .with(CatalogConstants.CAPABILITY_DATA_SHAPE);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported capability: " + capability);
                }
            }

            //
            // channels
            //

            if (connector.getChannels() != null) {
                for (var ch : connector.getChannels().entrySet()) {
                    ConnectorDefinition.Channel channel = new ConnectorDefinition.Channel();
                    ConnectorDefinition.Metadata metadata = channel.getMetadata();

                    // add channel to the connector definition
                    def.getConnectorType().getChannels().add(ch.getKey());

                    def.getChannels().put(ch.getKey(), channel);

                    metadata.setConnectorImage("placeholder");
                    metadata.setConnectorRevision(this.initialRevision);
                    metadata.setConnectorType(type);

                    metadata.getOperators().add(new ConnectorDefinition.Operator(
                            ch.getValue().getOperatorType(),
                            ch.getValue().getOperatorVersion()));

                    metadata.getKamelets().getAdapter().setName(adapter.getName());
                    metadata.getKamelets().getAdapter().setPrefix(CatalogSupport.asKey(adapter.getPrefix()));

                    metadata.getKamelets().getKafka().setName(kafka.getName());
                    metadata.getKamelets().getKafka().setPrefix(kafka.getPrefix());

                    if (Objects.equals(CatalogConstants.SOURCE, CatalogSupport.kameletType(adapterSpec))) {
                        if (ds.getConsumes() == null && ds.getProduces() != null) {
                            ds.setConsumes(ds.getProduces());
                        }
                    }
                    if (Objects.equals(CatalogConstants.SINK, CatalogSupport.kameletType(adapterSpec))) {
                        if (ds.getProduces() == null && ds.getConsumes() != null) {
                            ds.setProduces(ds.getConsumes());
                        }
                    }

                    if (ds.getConsumes() != null) {
                        metadata.setConsumes(ds.getConsumes().getDefaultFormat());
                        metadata.setConsumesClass(ds.getConsumes().getContentClass());
                    }
                    if (ds.getProduces() != null) {
                        metadata.setProduces(ds.getProduces().getDefaultFormat());
                        metadata.setProducesClass(ds.getProduces().getContentClass());
                    }

                    if (defaultAnnotations != null) {
                        defaultAnnotations.stream().sorted(Comparator.comparing(Annotation::getName)).forEach(annotation -> {
                            metadata.getAnnotations().put(annotation.getName(), annotation.getValue());
                        });
                    }

                    if (annotations != null) {
                        annotations.stream().sorted(Comparator.comparing(Annotation::getName)).forEach(annotation -> {
                            metadata.getAnnotations().put(annotation.getName(), annotation.getValue());
                        });
                    }

                    if (connector.getErrorHandler() != null && connector.getErrorHandler().getDefaultStrategy() != null) {
                        metadata.setErrorHandlerStrategy(
                                connector.getErrorHandler().getDefaultStrategy().name().toLowerCase(Locale.US));
                    }
                }
            }

            //
            // Disable additional properties if empty capabilities
            //

            CatalogSupport.disableAdditionalProperties(
                    def.getConnectorType().getSchema(),
                    "/properties/" + CatalogConstants.CAPABILITY_PROCESSORS);
            CatalogSupport.disableAdditionalProperties(
                    def.getConnectorType().getSchema(),
                    "/properties/" + CatalogConstants.CAPABILITY_ERROR_HANDLER);
            CatalogSupport.disableAdditionalProperties(
                    def.getConnectorType().getSchema(),
                    "/properties/" + CatalogConstants.CAPABILITY_DATA_SHAPE);

            //
            // Patch
            //

            if (connector.getCustomizers() != null) {
                ImportCustomizer ic = new ImportCustomizer();

                CompilerConfiguration cc = new CompilerConfiguration();
                cc.addCompilationCustomizers(ic);

                ClassLoader cl = Thread.currentThread().getContextClassLoader();

                Binding binding = new Binding();
                binding.setProperty("mapper", CatalogSupport.JSON_MAPPER);
                binding.setProperty("log", getLog());
                binding.setProperty("connector", connector);
                binding.setProperty("definition", def);
                binding.setProperty("schema", def.getConnectorType().getSchema());

                for (File customizer : connector.getCustomizers()) {
                    if (!Files.exists(customizer.toPath())) {
                        continue;
                    }

                    getLog().info("Customizing: " + connector.getName() + " with customizer " + customizer);

                    new GroovyShell(cl, binding, cc).run(customizer, new String[] {});
                }
            }

            //
            // Revision
            //

            try {
                if (Files.exists(definitionFile)) {
                    JsonNode newSchema = CatalogSupport.JSON_MAPPER.convertValue(def, ObjectNode.class);
                    JsonNode oldSchema = CatalogSupport.JSON_MAPPER.readValue(definitionFile.toFile(), JsonNode.class);

                    JsonAssertions.assertThatJson(oldSchema)
                            .when(Option.IGNORING_ARRAY_ORDER)
                            .whenIgnoringPaths(
                                    "$.channels.*.shard_metadata.connector_image",
                                    "$.channels.*.shard_metadata.connector_revision")
                            .withDifferenceListener((difference, context) -> {
                                getLog().info("diff: " + difference.toString());
                                manifest.bump();
                            })
                            .isEqualTo(newSchema);
                }
            } catch (AssertionError e) {
                // ignored, just avoid blowing thing up
            }

            //
            // Images
            //

            if (connector.getChannels() != null) {
                for (var ch : connector.getChannels().entrySet()) {
                    ConnectorDefinition.Metadata metadata = def.getChannels().get(ch.getKey()).getMetadata();

                    String image = String.format("%s/%s/%s-%s:%s",
                            this.containerImageRegistry,
                            this.containerImageOrg,
                            this.containerImagePrefix, this.type,
                            this.containerImageTag);

                    metadata.setConnectorRevision(this.manifest.getRevision());
                    metadata.setConnectorImage(image);
                }
            }

            //
            // As Json
            //

            ObjectNode definition = CatalogSupport.JSON_MAPPER.convertValue(def, ObjectNode.class);

            if (connector.allowProcessors()) {
                importProcessorSchema(definition);
            }

            //
            // Validate
            //

            if (validate) {
                validateConnector(connector, definition);
            }

            //
            // Write Definition
            //

            Files.createDirectories(definitionPathLocal.toPath());

            getLog().info("Writing connector definition to: " + definitionLocalFile);

            CatalogSupport.JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                    Files.newBufferedWriter(definitionLocalFile),
                    definition);

            return def;

        } catch (IOException e) {
            throw new MojoExecutionException("", e);
        }
    }

    private void importProcessorSchema(ObjectNode definition) throws IOException {
        ObjectNode dslDefinitions = (ObjectNode) CatalogSupport.JSON_MAPPER.readTree(
                Thread.currentThread().getContextClassLoader().getResourceAsStream(
                        "schema/camel-yaml-dsl-restricted.json"));
        dslDefinitions = (ObjectNode) dslDefinitions.get("items").get("definitions");
        final ObjectNode schema = (ObjectNode) definition.get("connector_type").get("schema");
        if (!schema.has("$defs")) {
            schema.set("$defs", CatalogSupport.JSON_MAPPER.createObjectNode());
        }
        final ObjectNode schemaDefs = (ObjectNode) schema.get("$defs");
        dslDefinitions.fields().forEachRemaining((e) -> {
            ((ObjectNode) e.getValue()).findParents("$ref").forEach((refParent) -> {
                String updatedRef = refParent.get("$ref").asText().replace("#/items/definitions", "#/$defs");
                ((ObjectNode) refParent).set("$ref", new TextNode(updatedRef));
            });
            schemaDefs.set(e.getKey(), e.getValue());
        });

        ObjectNode processors = CatalogSupport.JSON_MAPPER.createObjectNode();
        processors.set("type", new TextNode("array"));
        ObjectNode items = CatalogSupport.JSON_MAPPER.createObjectNode();
        items.set("$ref", new TextNode("#/$defs/org.apache.camel.model.ProcessorDefinition"));
        processors.set("items", items);
        if (!schema.has("properties")) {
            schema.set("properties", CatalogSupport.JSON_MAPPER.createObjectNode());
        }
        final ObjectNode schemaProperties = (ObjectNode) schema.get("properties");
        schemaProperties.set("processors", processors);
    }

    private void validateConnector(Connector connector, ObjectNode definition)
            throws MojoExecutionException, MojoFailureException {

        try {
            final Validator.Context context = of(connector);

            for (Validator validator : ServiceLoader.load(Validator.class)) {
                getLog().info("Validating: " + connector.getName() + " with validator " + validator);
                validator.validate(context, definition);
            }

            if (validators != null) {
                ImportCustomizer ic = new ImportCustomizer();

                CompilerConfiguration cc = new CompilerConfiguration();
                cc.addCompilationCustomizers(ic);

                ClassLoader cl = Thread.currentThread().getContextClassLoader();

                Binding binding = new Binding();
                binding.setProperty("context", context);
                binding.setProperty("schema", definition);

                for (File validator : validators) {
                    if (!Files.exists(validator.toPath())) {
                        return;
                    }

                    getLog().info("Validating: " + connector.getName() + " with validator " + validator);
                    new GroovyShell(cl, binding, cc).run(validator, new String[] {});
                }
            }
        } catch (AssertionError | Exception e) {
            throw new MojoFailureException(e);
        }
    }

    public TreeSet<String> dependencies()
            throws MojoExecutionException, MojoFailureException {

        TreeSet<String> answer = new TreeSet<>();

        try {
            Set<String> propertiesToClear = new HashSet<>();
            propertiesToClear.add("quarkus.container-image.build");
            propertiesToClear.add("quarkus.container-image.push");

            // disable quarkus build
            System.setProperty("quarkus.container-image.build", "false");
            System.setProperty("quarkus.container-image.push", "false");

            if (systemProperties != null) {
                // Add the system properties of the plugin to the system properties
                // if and only if they are not already set.
                for (Map.Entry<String, String> entry : systemProperties.entrySet()) {
                    String key = entry.getKey();
                    if (System.getProperty(key) == null) {
                        System.setProperty(key, entry.getValue());
                        propertiesToClear.add(key);
                    }
                }
            }

            try (CuratedApplication curatedApplication = bootstrapApplication().bootstrapQuarkus().bootstrap()) {
                List<ResolvedDependency> deps = new ArrayList<>(curatedApplication.getApplicationModel().getDependencies());
                deps.sort(Comparator.comparing(ResolvedDependency::toCompactCoords));

                for (ResolvedDependency dep : deps) {
                    MessageDigest digest = DigestUtils.getSha256Digest();
                    Path path = dep.getResolvedPaths().getSinglePath();

                    if (dep.getGroupId().startsWith("com.github.sco1237896")) {
                        try (JarFile jar = new JarFile(path.toFile())) {
                            List<JarEntry> entries = Collections.list(jar.entries());
                            entries.sort(Comparator.comparing(JarEntry::getName));

                            for (JarEntry entry : entries) {
                                if (entry.isDirectory()) {
                                    continue;
                                }
                                if (entry.getName().equals("META-INF/jandex.idx")) {
                                    continue;
                                }
                                if (entry.getName().startsWith("META-INF/quarkus-")) {
                                    continue;
                                }
                                if (entry.getName().endsWith("git.properties")) {
                                    continue;
                                }

                                // include kamelets names to ensure kamelets renaming would trigger
                                // a new connector being generated
                                if (entry.getName().endsWith(".kamelet.yaml")) {
                                    DigestUtils.updateDigest(digest, entry.getName());
                                }

                                try (InputStream is = jar.getInputStream(entry)) {
                                    DigestUtils.updateDigest(digest, is);
                                }
                            }
                        }
                    } else {
                        DigestUtils.updateDigest(digest, dep.toCompactCoords());
                    }

                    answer.add(
                            dep.toCompactCoords() + "@sha256:" + DigestUtils.sha256Hex(digest.digest()));
                }
            } finally {
                // Clear all the system properties set by the plugin
                propertiesToClear.forEach(System::clearProperty);
            }
        } catch (BootstrapException | IOException e) {
            throw new MojoExecutionException("Failed to build quarkus application", e);
        }

        return answer;
    }

    protected AppBootstrapProvider bootstrapApplication() {
        AppBootstrapProvider provider = new AppBootstrapProvider();
        provider.setAppArtifactCoords(this.appArtifact);
        provider.setBuildDir(this.buildDir);
        provider.setConnectors(this.connectors);
        provider.setDefaults(this.defaults);
        provider.setFinalName(this.finalName);
        provider.setLog(getLog());
        provider.setProject(this.project);
        provider.setCamelQuarkusVersion(this.camelQuarkusVersion);
        provider.setRemoteRepoManager(this.remoteRepoManager);
        provider.setRepoSession(this.repoSession);
        provider.setRepoSystem(this.repoSystem);
        provider.setSession(this.session);

        return provider;
    }

    private Validator.Context of(Connector connector) {
        return new Validator.Context() {
            @Override
            public Path getCatalogPath() {
                return definitionPath.toPath();
            }

            @Override
            public Connector getConnector() {
                return connector;
            }

            @Override
            public Log getLog() {
                return GenerateCatalogMojo.this.getLog();
            }

            @Override
            public Validator.Mode getMode() {
                return mode;
            }
        };
    }
}
