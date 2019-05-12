package org.muehlbachler.gradle.plugin.dependencyupdatenotifier;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import io.specto.hoverfly.junit.core.Hoverfly;
import io.specto.hoverfly.junit.core.SimulationSource;
import io.specto.hoverfly.junit.dsl.HoverflyDsl;
import io.specto.hoverfly.junit.dsl.RequestMatcherBuilder;
import io.specto.hoverfly.junit.dsl.ResponseCreators;
import io.specto.hoverfly.junit5.HoverflyExtension;
import okio.Okio;
import org.assertj.core.api.Assertions;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.gitlab.GitlabClient;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.gitlab.GitlabNotifierTask;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.model.gitlab.issue.GitlabIssue;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@ExtendWith(HoverflyExtension.class)
public class DependencyUpdateNotifierPluginTest {
    @TempDir
    public Path testProjectDir;
    File buildFile;
    File propertiesFile;
    File reportJson;

    final JsonAdapter<GitlabIssue> gitlabIssueAdapter = new Moshi.Builder().build().adapter(GitlabIssue.class);


    @BeforeEach
    void setup() throws IOException {
        Assertions.assertThat(Files.isDirectory(testProjectDir));

        buildFile = Files.createFile(testProjectDir.resolve("build.gradle")).toFile();
        propertiesFile = Files.createFile(testProjectDir.resolve("gradle.properties")).toFile();
        reportJson = Files.createFile(testProjectDir.resolve("report.json")).toFile();
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void shouldCreateSimpleIssue(final Hoverfly hoverfly) throws IOException {
        initHoverfly(hoverfly, "shouldCreateSimpleIssue");

        final BuildResult result = executeRunner();
        Assertions.assertThat(result.task(":" + GitlabNotifierTask.NAME))
                .isNotNull()
                .hasFieldOrPropertyWithValue("outcome", TaskOutcome.SUCCESS);
        hoverfly.verifyAll();
    }

    @Test
    void shouldCreateIssueWithGradle(final Hoverfly hoverfly) throws IOException {
        initHoverfly(hoverfly, "shouldCreateIssueWithGradle");

        final BuildResult result = executeRunner();
        Assertions.assertThat(result.task(":" + GitlabNotifierTask.NAME))
                .isNotNull()
                .hasFieldOrPropertyWithValue("outcome", TaskOutcome.SUCCESS);
        hoverfly.verifyAll();
    }

    @Test
    void shouldCreateIssueWithGradleOnly(final Hoverfly hoverfly) throws IOException {
        initHoverfly(hoverfly, "shouldCreateIssueWithGradleOnly");

        final BuildResult result = executeRunner();
        Assertions.assertThat(result.task(":" + GitlabNotifierTask.NAME))
                .isNotNull()
                .hasFieldOrPropertyWithValue("outcome", TaskOutcome.SUCCESS);
        hoverfly.verifyAll();
    }

    @Test
    void shouldCreateIssuePreferGradleStableVersion(final Hoverfly hoverfly) throws IOException {
        initHoverfly(hoverfly, "shouldCreateIssuePreferGradleStableVersion");

        final BuildResult result = executeRunner();
        Assertions.assertThat(result.task(":" + GitlabNotifierTask.NAME))
                .isNotNull()
                .hasFieldOrPropertyWithValue("outcome", TaskOutcome.SUCCESS);
        hoverfly.verifyAll();
    }

    @Test
    void shouldNotCreateIssueForGradleNightly() throws IOException {
        generateBuildFile(0);
        copyReportJson("shouldNotCreateIssueForGradleNightly");

        final BuildResult result = executeRunner();
        Assertions.assertThat(result.task(":" + GitlabNotifierTask.NAME))
                .isNotNull()
                .hasFieldOrPropertyWithValue("outcome", TaskOutcome.SUCCESS);
    }

    @Test
    void shouldCreateIssueGradleReleaseCandidate(final Hoverfly hoverfly) throws IOException {
        initHoverfly(hoverfly, "shouldCreateIssueGradleReleaseCandidate");

        final BuildResult result = executeRunner();
        Assertions.assertThat(result.task(":" + GitlabNotifierTask.NAME))
                .isNotNull()
                .hasFieldOrPropertyWithValue("outcome", TaskOutcome.SUCCESS);
        hoverfly.verifyAll();
    }

    @Test
    void shouldCreateComplexIssue(final Hoverfly hoverfly) throws IOException {
        initHoverfly(hoverfly, "shouldCreateComplexIssue");

        final BuildResult result = executeRunner();
        Assertions.assertThat(result.task(":" + GitlabNotifierTask.NAME))
                .isNotNull()
                .hasFieldOrPropertyWithValue("outcome", TaskOutcome.SUCCESS);
        hoverfly.verifyAll();
    }

    @Test
    void shouldFailWithoutReportFile() throws IOException {
        generateBuildFile(0);

        final BuildResult result = executeFailedRunner();
        Assertions.assertThat(result.task(":" + GitlabNotifierTask.NAME))
                .isNotNull()
                .hasFieldOrPropertyWithValue("outcome", TaskOutcome.FAILED);
    }

    @Test
    void shouldFailWithGitlabApiError(final Hoverfly hoverfly) throws IOException {
        initFailedHoverfly(hoverfly, "shouldFailWithGitlabApiError");

        final BuildResult result = executeFailedRunner();
        Assertions.assertThat(result.task(":" + GitlabNotifierTask.NAME))
                .isNotNull()
                .hasFieldOrPropertyWithValue("outcome", TaskOutcome.FAILED);
        hoverfly.verifyAll();
    }

    private GradleRunner getRunner() {
        return GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments(GitlabNotifierTask.NAME)
                .withDebug(true)
                .withPluginClasspath()
                .forwardOutput();
    }

    private BuildResult executeRunner() {
        return getRunner()
                .build();
    }

    private BuildResult executeFailedRunner() {
        return getRunner()
                .buildAndFail();
    }

    private RequestMatcherBuilder getHoverflyRequestMatcherBuilder(final String testName) throws IOException {
        return HoverflyDsl.service("gitlab.test")
                .post("/projects/1/issues")
                .header(GitlabClient.TOKEN, "token")
                .body(readHoverflyRequestBody(testName));
    }

    private void initHoverfly(final Hoverfly hoverfly, final String testName) throws IOException {
        hoverfly.simulate(SimulationSource.dsl(getHoverflyRequestMatcherBuilder(testName)
                .willReturn(ResponseCreators
                        .success(readResource("hoverfly/response.json"), "application/json"))));

        prepareBuildDir(hoverfly, testName);
    }

    private void initFailedHoverfly(final Hoverfly hoverfly, final String testName) throws IOException {
        hoverfly.simulate(SimulationSource.dsl(getHoverflyRequestMatcherBuilder(testName)
                .willReturn(ResponseCreators.forbidden())));

        prepareBuildDir(hoverfly, testName);
    }

    private void prepareBuildDir(final Hoverfly hoverfly, final String testName) throws IOException {
        final int proxyPort = hoverfly.getHoverflyConfig().getProxyPort();
        generateBuildFile(proxyPort);
        copyReportJson(testName);
    }

    private String readResource(final String name) throws IOException {
        final InputStream file = getClass()
                .getClassLoader()
                .getResourceAsStream(name);
        return Okio.buffer(Okio.source(file)).readUtf8();
    }

    private String readHoverflyRequestBody(final String name) throws IOException {
        final GitlabIssue issue = gitlabIssueAdapter.fromJson(readResource("hoverfly/" + name + "_request.json"));
        return gitlabIssueAdapter.toJson(issue);
    }

    private void copyReportJson(final String name) throws IOException {
        final String reportJsonContent = readResource("dependencyUpdates/" + name + ".json");
        writeFile(reportJson, reportJsonContent);
    }

    private void generateBuildFile(final int proxyPort) throws IOException {
        final String buildFileContent = readResource("test.build.gradle");
        writeFile(buildFile, buildFileContent);

        if(proxyPort > 0) {
            final String propertiesFileContent = readResource("test.gradle.properties")
                    .replaceAll("%proxyPort", Integer.toString(proxyPort));
            writeFile(propertiesFile, propertiesFileContent);
        }
    }

    private void writeFile(final File destination, final String content) throws IOException {
        try(final BufferedWriter output = new BufferedWriter(new FileWriter(destination))) {
            output.write(content);
        }
    }
}
