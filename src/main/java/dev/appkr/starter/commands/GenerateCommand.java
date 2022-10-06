package dev.appkr.starter.commands;

import dev.appkr.starter.model.BuildInfo;
import dev.appkr.starter.model.ExitCode;
import dev.appkr.starter.model.GlobalConstants;
import dev.appkr.starter.services.CommandUtils;
import dev.appkr.starter.services.FileUtils;
import dev.appkr.starter.services.TemplateUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

@Command(
    name = "generate",
    description = "Create a new project",
    mixinStandardHelpOptions = true,
    scope = ScopeType.INHERIT
)
public class GenerateCommand implements Callable<Integer> {

  String sourceDir = TemplateUtils.getTemplatePath("templates/webmvc");
  final String targetDir = String.format("%s/.msa-starter", GlobalConstants.USER_HOME);

  final BuildInfo buildInfo = BuildInfo.getInstance();

  @Option(names = {"--useDefault"}, description = "Create a project with all default values")
  boolean useDefault = false;

  @Override
  public Integer call() throws Exception {
    // delete the targetDir if exists and recreate the targetDir
    FileUtils.resetDir(targetDir);

    if (!useDefault) {
      // get project attributes from a user
      getBuildInfo();
      CommandUtils.confirm("'Enter' to continue OR 'n' to quit)?", buildInfo.toString());
    }

    // bind BuildInfo to the templates and create files
    FileUtils.listDir(sourceDir)
        .filter(Files::isRegularFile)
        .filter(Predicate.not(this::shouldSkip))
        .forEach(this::process);

    // make gradlew be executable
    FileUtils.makeExecutable(String.format("%s/%s", targetDir, "gradlew"));

    return ExitCode.SUCCESS;
  }

  private void getBuildInfo() throws IOException {
    final String templates = CommandUtils.ask("A WebMVC/JPA project(m)? Or a WebFlux/R2DBC project(f) (default: {})?",
        "m");
    if (templates.equalsIgnoreCase("f")) {
      buildInfo.setReactiveProject(true);
      sourceDir = TemplateUtils.getTemplatePath("templates/webflux");
    }

    final String isVroongProject = CommandUtils.ask("Is vroong project(y/n, default: {})?", "n");
    if (isVroongProject.equalsIgnoreCase("y")) {
      buildInfo.setVroongProject(true);
      buildInfo.setGroupName("com.vroong");
      buildInfo.setMediaType("application/vnd.vroong.private.v1+json");
      buildInfo.setSkipTokens(List.of(".DS_Store"));
    }

    boolean incorrect = true;
    while (incorrect) {
      final String javaVersion = CommandUtils.ask("Which java version will you choose(1.8/11/17, default: {})?",
          buildInfo.getJavaVersion());
      switch (javaVersion) {
        case "1.8" -> {
          buildInfo.setJavaVersion("1.8");
          buildInfo.setDockerImage("openjdk:8-jre-alpine");
          incorrect = false;
        }
        case "11" -> {
          buildInfo.setJavaVersion("11");
          buildInfo.setDockerImage("amazoncorretto:11-alpine-jdk");
          incorrect = false;
        }
        case "17" -> {
          buildInfo.setJavaVersion("17");
          buildInfo.setDockerImage("amazoncorretto:17-alpine-jdk");
          incorrect = false;
        }
        default -> CommandUtils.warn("Must be one of '1.8', '11', or '17'!");
      }
    }

    buildInfo.setProjectName(CommandUtils.ask("What is the project name(default: {})?", buildInfo.getProjectName()));
    buildInfo.setGroupName(CommandUtils.ask("What is the group name(default: {})?", buildInfo.getGroupName()));
    buildInfo.setPortNumber(CommandUtils.ask("What is the web server port(default: {})?", buildInfo.getPortNumber()));
    buildInfo.setMediaType(
        CommandUtils.ask("What is the media type for request and response(default: {})?", buildInfo.getMediaType()));
    buildInfo.setPackageName(buildInfo.getGroupName() + "." + buildInfo.getProjectName());

    final String includeExample = CommandUtils.ask("Include example codes(y/n, default: {})?", "y");
    if (includeExample.equalsIgnoreCase("n")) {
      buildInfo.setIncludeExample(false);
      buildInfo.mergeSkipTokensWithExamples();
    }
  }

  private boolean shouldSkip(Path path) {
    boolean skip = false;
    for (String token : buildInfo.getSkipTokens()) {
      if (path.toString().indexOf(token) != -1) {
        skip = true;
        break;
      }
    }

    return skip;
  }

  private void process(Path path) {
    String targetFilename = path.toString().replace(sourceDir, targetDir);

    // Calculate destination path
    //   - {sourceDir}/clients/build.gradle -> {targetDir}/clients/build.gradle
    //   - {sourceDir}/src/main/java/Application.java -> {targetDir}/src/main/dev/appkr/example/Application.java
    if (targetFilename.contains("src/main/java")) {
      final String replacement = String.format("src/main/java/%s",
          buildInfo.getPackageName().replace(".", GlobalConstants.DIR_SEPARATOR));
      targetFilename = targetFilename.replace("src/main/java", replacement);
    }
    if (targetFilename.contains("src/test/java")) {
      final String replacement = String.format("src/test/java/%s",
          buildInfo.getPackageName().replace(".", GlobalConstants.DIR_SEPARATOR));
      targetFilename = targetFilename.replace("src/test/java", replacement);
    }

    // NOTE. Handling '.jar' file in templates
    // https://imperceptiblethoughts.com/shadow/configuration/dependencies/#embedding-jar-files-inside-your-shadow-jar
    // Because of the way that Gradle handles dependency configuration, from a plugin perspective,
    // shadow is unable to distinguish between a jar file configured as a dependency and a jar file included in the resource folder.
    // This means that any jar found in a resource directory will be merged into the shadow jar the same as any other dependency.
    // If your intention is to embed the jar inside, you must rename the jar as to not end with .jar before the shadow task begins.
    if (targetFilename.endsWith(".binary")) {
      targetFilename = targetFilename.substring(0, targetFilename.length() - ".binary".length());
    }

    try {
      FileUtils.createDir(Paths.get(targetFilename).getParent());

      if (FileUtils.isBinary(path)) {
        FileUtils.copy(path, Paths.get(targetFilename));
      } else {
        TemplateUtils.write(path.toString(), targetFilename, buildInfo);
      }

      CommandUtils.success(path + " -> " + targetFilename);
    } catch (IOException e) {
      CommandUtils.fail(path + " -> " + targetFilename, e);
    }
  }
}
