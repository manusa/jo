package dev.kameshs.actions;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.eclipse.jkube.kit.build.service.docker.ImageConfiguration;
import org.eclipse.jkube.kit.build.service.docker.ServiceHub;
import org.eclipse.jkube.kit.build.service.docker.ServiceHubFactory;
import org.eclipse.jkube.kit.common.Assembly;
import org.eclipse.jkube.kit.common.AssemblyConfiguration;
import org.eclipse.jkube.kit.common.AssemblyFileSet;
import org.eclipse.jkube.kit.common.JavaProject;
import org.eclipse.jkube.kit.common.KitLogger;
import org.eclipse.jkube.kit.config.JKubeConfiguration;
import org.eclipse.jkube.kit.config.image.build.Arguments;
import org.eclipse.jkube.kit.config.image.build.BuildConfiguration;
import org.eclipse.jkube.kit.config.resource.RuntimeMode;
import org.eclipse.jkube.kit.config.service.BuildServiceConfig;
import org.eclipse.jkube.kit.config.service.JKubeServiceHub;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.kameshs.data.GitHubContent;
import dev.kameshs.service.GitHubContentService;
import dev.kameshs.utils.ImageResolverUtil;


@ApplicationScoped
public class ImageBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(
      ImageBuilder.class.getName());

  @Inject
  ImageResolverUtil imageResolverUtil;

  @Inject
  @RestClient
  GitHubContentService ghContentService;

  @ConfigProperty(name = "dev.kameshs.jo-container-repo")
  Optional<String> joContainerRepo;

  KitLogger kitLogger;
  ServiceHub serviceHub;
  JKubeConfiguration configuration;
  BuildServiceConfig dockerBuildServiceConfig;

  @PostConstruct
  void init() {
    kitLogger = new KitLogger.StdoutLogger();
    kitLogger.info(
        "Initiating default JKube configuration and required services...");
    kitLogger.info(" - Creating Docker Service Hub");
    serviceHub = new ServiceHubFactory().createServiceHub(kitLogger);
    kitLogger.info(" - Creating Docker Build Service Configuration");
    dockerBuildServiceConfig = BuildServiceConfig.builder().build();
    kitLogger.info(" - Creating configuration for JKube");
    configuration = JKubeConfiguration.builder()
        .project(JavaProject.builder()
            .baseDirectory(Paths.get("").toAbsolutePath().toFile())
            .build())
        .outputDirectory("target")
        .build();
  }

  public Optional<String> build(String strImageUri) throws Exception {
    String imageName = null;
    //TODO #2 #1 Resolve base image based on the URI
    URI uri = new URI(strImageUri);
    final String fromImage = imageResolverUtil.resolveFromImage(uri)
      .orElseThrow(() -> new IllegalStateException("Unable to find base image for URI " + uri));
    final String path = uri.getPath();
    LOGGER.debug("Image Host {} and Path {}", uri.getHost(), path);

    String[] segments = path.split("/");
    String jbangScriptSegment = segments[segments.length - 1];

    imageName = joContainerRepo
      .map(r -> String.join("/", r, jbangScriptSegment))
      .orElse(jbangScriptSegment);

    String downloadURL = "https://" + uri.getHost() + path;

    String destinationFile = Stream.of(Arrays.copyOfRange(segments, 0, segments.length -1))
      .filter(s -> !s.isBlank())
      .collect(Collectors.joining("-")) + "/" + jbangScriptSegment;

    String jbangExecScript = "/scripts/" + jbangScriptSegment;

    if (jbangScriptSegment.endsWith(".java")) {
      imageName =
          jbangScriptSegment.substring(0,
              jbangScriptSegment.lastIndexOf("."));
    } else {
      downloadURL += ".java";
      destinationFile += ".java";
      jbangExecScript += ".java";
    }

    Optional<File> downloadedSource =
        downloadScriptFile(segments, downloadURL, destinationFile);

    if (downloadedSource.isPresent()) {

      LOGGER.debug("Script Name: {} ", jbangExecScript);

      //Copy the script file to destination folder
      AssemblyFileSet fileSet = AssemblyFileSet.builder()
        .directory(downloadedSource.get())
        .fileMode("0777")
        .build();

      AssemblyConfiguration scriptAssembly = AssemblyConfiguration
          .builder()
          .targetDir("/scripts")
          .inline(Assembly.builder()
            .fileSet(fileSet)
            .build())
          .build();

      //Image build Configuration
      BuildConfiguration bc = BuildConfiguration.builder()
        .from(fromImage)
        .entryPoint(Arguments.builder().shell("/jbang/bin/jbang " + jbangExecScript).build())
        .cmd(Arguments.builder()
          .shell("/jbang/bin/jbang " + jbangExecScript)
          .build())
        .assembly(scriptAssembly)
        .port("8080")
        .build();

      final ImageConfiguration imageConfiguration = ImageConfiguration
          .builder()
          .name(imageName)
          .build(bc)
          .build();

      JKubeServiceHub jKubeServiceHub = JKubeServiceHub.builder()
          .log(kitLogger)
          .configuration(configuration)
          .platformMode(RuntimeMode.kubernetes)
          .dockerServiceHub(serviceHub)
          .buildServiceConfig(dockerBuildServiceConfig)
          .build();
      jKubeServiceHub.getBuildService().build(imageConfiguration);

      final String imageId = jKubeServiceHub.getDockerServiceHub()
          .getDockerAccess().getImageId(imageName);

      kitLogger.info("Docker image built successfully (%s)!", imageId);
    }
    return Optional.ofNullable(imageName);
  }

  //TODO #3 handle other sources, for now only GitHub
  private Optional<File> downloadScriptFile(String[] segments,
      String downloadURL,
      String destinationFile) {
    File file = null;
    try {
      String repoOwner = segments[1];
      String repo = segments[2];
      String filePath =
          String.join("/", Arrays.copyOfRange(segments, 3, segments.length));

      if (!filePath.endsWith(".java")) {
        filePath += ".java";
      }

      LOGGER.debug("Repo {} Repo-Owner{} Filepath {} ", repoOwner, repo,
          filePath);

      GitHubContent ghContent =
          ghContentService.githubFile(repoOwner, repo, filePath);

      file = new File(System.getProperty("java.io.tmpdir"), destinationFile);
      if (ghContent != null && file.getParentFile().mkdirs()) {
        try (
          FileWriter fw = new FileWriter(file);
          BufferedWriter writer = new BufferedWriter(fw)
        ) {
          final String decodedContent = new String(
            Base64.getDecoder().decode(ghContent.content.replaceAll("[\n\r]","")),
            StandardCharsets.UTF_8
          );
          LOGGER.debug("GitHub Content: {} ", decodedContent);
          writer.write(decodedContent);
        }
      }

    } catch (Exception e) {
      LOGGER.error("Error downloading file {}", downloadURL, e);
      file = null;
    }
    return Optional.ofNullable(file);
  }
}
