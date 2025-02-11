package org.muehlbachler.gradle.plugin.dependencyupdatenotifier.gitlab;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.BaseTask;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.model.DependencyAnalysis;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.model.dependency.AvailableDependency;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.model.dependency.Dependencies;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.model.dependency.Dependency;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.model.gitlab.GitlabNotifierConfig;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.model.gitlab.issue.GitlabIssue;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.model.gradle.Gradle;
import org.muehlbachler.gradle.plugin.dependencyupdatenotifier.model.gradle.GradleConfig;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class GitlabNotifierTask extends BaseTask {
	public static final String NAME = "gitlabDependencyUpdateNotifier";

	private static final String GRADLE_DEPENDENCY_NAME = "Gradle";
	private static final String GRADLE_RC_NAME = "(RC)";
	private static final Pattern GRADLE_REGEX = Pattern.compile(".*`(.*)` -> `(.*)`.*");
	private static final Pattern DEPENDENCY_REGEX = Pattern.compile("-.*`(.*):(.*):\\((.*) -> (.*)\\)`.*");

	@Input
	GitlabNotifierConfig config;
	@Internal
	GitlabClient client;

	@Override
	public void init() {
		super.init();
		client = new GitlabClient(config, getLogger());

		setDescription("Creates a GitLab issue for outdated dependencies.");
	}

	@Override
	protected void handleDependencies(final DependencyAnalysis dependencies) throws IOException {
		if (dependencies.getOutdated().getCount() <= 0 && !dependencies.getGradle().isUpdateAvailable()) {
			return;
		}

		final List<String> outdatedIssues = getOutdatedIssues(dependencies);
		final String gradleIssue = getGradleIssue(dependencies);
		if (!outdatedIssues.isEmpty() || StringUtils.isNotEmpty(gradleIssue)) {
			final GitlabIssue gitlabIssue = buildIssue(outdatedIssues, gradleIssue);
			final GitlabIssue issue = client.createIssue(gitlabIssue);
			getLogger().lifecycle("Created GitLab dependency update issue: {}", issue.getWebUrl());
		}
	}

	@Override
	protected DependencyAnalysis getCurrentDependencies() throws IOException {
		return client.getIssues().stream().map(this::extractDependencyAnalysis).reduce(this::mergeDependencyAnalysis)
				.orElseGet(DependencyAnalysis::new);
	}

	private DependencyAnalysis mergeDependencyAnalysis(final DependencyAnalysis analysis1,
			final DependencyAnalysis analysis2) {
		if (Objects.isNull(analysis2)) {
			return analysis1;
		}
		if (Objects.isNull(analysis1)) {
			return analysis2;
		}

		final List<Dependency> analysis2Dependencies = analysis2.getOutdated().getDependencies();
		final List<Dependency> analysis1Dependencies = analysis1.getOutdated().getDependencies().stream()
				.map(dependency -> analysis2Dependencies.stream().filter(dependency2 -> dependency2.equals(dependency))
						.findFirst()
						.filter(dependency2 -> StringUtils.compare(dependency.getAvailable().getNewRelease(),
								dependency2.getAvailable().getNewRelease()) < 0)
						.orElse(dependency))
				.collect(Collectors.toList());
		analysis2.getOutdated().getDependencies().addAll(analysis1Dependencies);
		analysis2.getOutdated().setCount(analysis2.getOutdated().getDependencies().size());
		return analysis2;
	}

	private DependencyAnalysis extractDependencyAnalysis(final GitlabIssue gitlabIssue) {
		final DependencyAnalysis dependencyAnalysis = new DependencyAnalysis();
		final Dependencies outdated = dependencyAnalysis.getOutdated();
		final GradleConfig gradle = dependencyAnalysis.getGradle();

		Arrays.stream(gitlabIssue.getDescription().split("\n")).filter(StringUtils::isNotEmpty).forEach(line -> {
			if (StringUtils.contains(line, GRADLE_DEPENDENCY_NAME)) {
				extractGradleUpdate(gradle, line);
			} else {
				extractDependencyUpdate(outdated, line);
			}
		});
		outdated.setCount(outdated.getDependencies().size());

		return dependencyAnalysis;
	}

	private void extractDependencyUpdate(final Dependencies outdated, final String line) {
		final Matcher matcher = DEPENDENCY_REGEX.matcher(line);
		if (!matcher.matches()) {
			return;
		}

		final AvailableDependency availableDependency = new AvailableDependency().setRelease(matcher.group(4));
		final Dependency dependency = new Dependency().setGroup(matcher.group(1)).setName(matcher.group(2))
				.setVersion(matcher.group(3)).setAvailable(availableDependency);
		outdated.getDependencies().add(dependency);
	}

	private void extractGradleUpdate(final GradleConfig gradle, final String line) {
		final Matcher matcher = GRADLE_REGEX.matcher(line);
		if (!matcher.matches()) {
			return;
		}

		final Gradle running = new Gradle().setUpdateAvailable(false).setVersion(matcher.group(1));
		gradle.setRunning(running);

		final Gradle newRelease = new Gradle().setUpdateAvailable(true).setVersion(matcher.group(2));
		if (StringUtils.contains(line, GRADLE_RC_NAME)) {
			gradle.setReleaseCandidate(newRelease);
		} else {
			gradle.setCurrent(newRelease);
		}
	}

	private List<String> getOutdatedIssues(final DependencyAnalysis dependencies) {
		return dependencies.getOutdated().getDependencies().stream().map(dependency -> {
			String dependencyString = String.format("- [ ] `%s`", dependency.getIssueRepresentation());
			if (dependency.hasProjectUrl()) {
				dependencyString = String.format("%s - [%s](%s)", dependencyString, dependency.getProjectUrl(),
						dependency.getProjectUrl());
			}
			return dependencyString;
		}).collect(Collectors.toList());
	}

	private String getGradleIssue(final DependencyAnalysis dependencies) {
		final GradleConfig gradle = dependencies.getGradle();
		if (!gradle.isUpdateAvailable()) {
			return null;
		}

		String newVersion = "";
		if (gradle.hasCurrentVersionUpdate()) {
			newVersion = gradle.getCurrent().getVersion();
		} else if (gradle.hasReleaseCandidateVersionUpdate()) {
			newVersion = gradle.getReleaseCandidate().getVersion() + " " + GRADLE_RC_NAME;
		}

		return StringUtils.isNotEmpty(newVersion)
				? String.format("- [ ] %s `%s` -> `%s`", GRADLE_DEPENDENCY_NAME, gradle.getRunning().getVersion(),
						newVersion)
				: null;
	}

	private GitlabIssue buildIssue(final List<String> outdatedIssues, final String gradleIssue) {
		final long count = outdatedIssues.size() + (StringUtils.isNotEmpty(gradleIssue) ? 1L : 0L);
		final String title = config.getTitle().replaceAll("%count", Long.toString(count));
		String body = String.join("\n", outdatedIssues);
		if (StringUtils.isNotEmpty(gradleIssue)) {
			body += (outdatedIssues.isEmpty() ? "" : "\n\n") + gradleIssue;
		}
		final List<String> labels = Arrays.asList(config.getLabel().split(","));
		return new GitlabIssue().setTitle(title).setDescription(body).setLabels(labels);
	}
}
