/*
 * Copyright 2016-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.local;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.local.LocalAppDeployerIntegrationTests.Config;
import org.springframework.cloud.deployer.spi.test.AbstractAppDeployerIntegrationJUnit5Tests;
import org.springframework.cloud.deployer.spi.test.AbstractIntegrationTests;
import org.springframework.cloud.deployer.spi.test.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for {@link LocalAppDeployer}.
 *
 * Now supports running with Docker images for tests, just set this env var:
 *
 *   SPRING_CLOUD_DEPLOYER_SPI_TEST_USE_DOCKER=true
 *
 * @author Eric Bottard
 * @author Mark Fisher
 * @author Oleg Zhurakousky
 * @author Janne Valkealahti
 * @author Ilayaperumal Gopinathan
 */
@SpringBootTest(classes = {Config.class, AbstractIntegrationTests.Config.class}, value = {
		"maven.remoteRepositories.springRepo.url=https://repo.spring.io/snapshot"})
public class LocalAppDeployerIntegrationTests extends AbstractAppDeployerIntegrationJUnit5Tests {

	private static final String TESTAPP_DOCKER_IMAGE_NAME = "springcloud/spring-cloud-deployer-spi-test-app:latest";

	@Autowired
	private AppDeployer appDeployer;

	@Value("${spring-cloud-deployer-spi-test-use-docker:false}")
	private boolean useDocker;

	@Override
	protected AppDeployer provideAppDeployer() {
		return appDeployer;
	}

	@Override
	protected Resource testApplication() {
		if (useDocker) {
			log.info("Using Docker image for tests");
			return new DockerResource(TESTAPP_DOCKER_IMAGE_NAME);
		}
		return super.testApplication();
	}

	@Override
	protected String randomName() {
		if (LocalDeployerUtils.isWindows()) {
			// tweak random dir name on win to be shorter
			String uuid = UUID.randomUUID().toString();
			long l = ByteBuffer.wrap(uuid.getBytes()).getLong();
			return testName + Long.toString(l, Character.MAX_RADIX);
		}
		else {
			return super.randomName();
		}
	}

	@Test
	public void testEnvVariablesInheritedViaEnvEndpoint() {
		if (useDocker) {
			// would not expect to be able to check anything on docker
			return;
		}
		Map<String, String> properties = new HashMap<>();
		AppDefinition definition = new AppDefinition(randomName(), properties);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());

		String deploymentId = appDeployer().deploy(request);
		Timeout timeout = deploymentTimeout();
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.deployed);
		});

		Map<String, AppInstanceStatus> instances = appDeployer().status(deploymentId).getInstances();
		String url = null;
		if (instances.size() == 1) {
			url = instances.entrySet().iterator().next().getValue().getAttributes().get("url");
		}
		String env = null;
		if (url != null) {
			RestTemplate template = new RestTemplate();
			env = template.getForObject(url + "/actuator/env", String.class);
		}

		log.info("Undeploying {}...", deploymentId);

		timeout = undeploymentTimeout();
		appDeployer().undeploy(deploymentId);
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.unknown);
		});

		assertThat(url).isNotNull();
		if (LocalDeployerUtils.isWindows()) {
			// windows is weird, we may still get Path or PATH
			assertThat(env).containsIgnoringCase("path");
		}
		else {
			assertThat(env).contains("\"PATH\"");
			// we're defaulting to SAJ so it's i.e.
			// instance.index not INSTANCE_INDEX
			assertThat(env).contains("\"instance.index\"");
			assertThat(env).contains("\"spring.application.index\"");
			assertThat(env).contains("\"spring.cloud.application.guid\"");
			assertThat(env).contains("\"spring.cloud.stream.instanceIndex\"");
		}
	}

	@Test
	public void testAppLogRetrieval() {
		Map<String, String> properties = new HashMap<>();
		AppDefinition definition = new AppDefinition(randomName(), properties);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = appDeployer().deploy(request);
		Timeout timeout = deploymentTimeout();
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.deployed);
		});
		String logContent = appDeployer().getLog(deploymentId);
		assertThat(logContent).contains("Starting DeployerIntegrationTestApplication");
	}

	// TODO: remove when these two are forced in tck tests
	@Test
	public void testScale() {
		doTestScale(false);
	}

	@Test
	public void testScaleWithIndex() {
		doTestScale(true);
	}

	@Test
	public void testFailureToCallShutdownOnUndeploy() {
		Map<String, String> properties = new HashMap<>();
		// disable shutdown endpoint
		properties.put("endpoints.shutdown.enabled", "false");
		AppDefinition definition = new AppDefinition(randomName(), properties);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());

		String deploymentId = appDeployer().deploy(request);
		Timeout timeout = deploymentTimeout();
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.deployed);
		});

		log.info("Undeploying {}...", deploymentId);

		timeout = undeploymentTimeout();
		appDeployer().undeploy(deploymentId);
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.unknown);
		});
	}

	@Test
	// was triggered by GH-50 and subsequently GH-55
	public void testNoStdoutStderrOnInheritLoggingAndNoNPEOnGetAttributes() {
		Map<String, String> properties = new HashMap<>();
		AppDefinition definition = new AppDefinition(randomName(), properties);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.singletonMap(LocalDeployerProperties.INHERIT_LOGGING, "true"));

		AppDeployer deployer = appDeployer();
		String deploymentId = deployer.deploy(request);
		AppStatus appStatus = deployer.status(deploymentId);
		assertThat(appStatus.getInstances()).hasSizeGreaterThan(0);
		for (Entry<String, AppInstanceStatus> instanceStatusEntry : appStatus.getInstances().entrySet()) {
			Map<String, String> attributes = instanceStatusEntry.getValue().getAttributes();
			assertThat(attributes).doesNotContainKey("stdout");
			assertThat(attributes).doesNotContainKey("stderr");
		}
		deployer.undeploy(deploymentId);
	}

	@Test
	public void testInDebugModeWithSuspended() throws Exception {
		Map<String, String> properties = new HashMap<>();
		AppDefinition definition = new AppDefinition(randomName(), properties);
		Resource resource = testApplication();
		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put(LocalDeployerProperties.DEBUG_PORT, "9999");
		deploymentProperties.put(LocalDeployerProperties.DEBUG_SUSPEND, "y");
		deploymentProperties.put(LocalDeployerProperties.INHERIT_LOGGING, "true");
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, deploymentProperties);

		AppDeployer deployer = appDeployer();
		String deploymentId = deployer.deploy(request);
		Thread.sleep(5000);
		AppStatus appStatus = deployer.status(deploymentId);
		if (resource instanceof DockerResource) {
			try {
				String containerId = getCommandOutput("docker ps -q --filter ancestor="+ TESTAPP_DOCKER_IMAGE_NAME);
				String logOutput = getCommandOutput("docker logs "+ containerId);
				assertThat(logOutput).contains("Listening for transport dt_socket at address: 9999");
			} catch (IOException e) {
			}
		}
		else {
			assertThat(appStatus.toString()).contains("deploying");
		}

		deployer.undeploy(deploymentId);
	}

	@Test
	public void testInDebugModeWithSuspendedUseCamelCase() throws Exception {
		Map<String, String> properties = new HashMap<>();
		AppDefinition definition = new AppDefinition(randomName(), properties);
		Resource resource = testApplication();
		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put(LocalDeployerProperties.PREFIX + ".debugPort", "8888");
		deploymentProperties.put(LocalDeployerProperties.PREFIX + ".debugSuspend", "y");
		deploymentProperties.put(LocalDeployerProperties.PREFIX + ".inheritLogging", "true");
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, deploymentProperties);

		AppDeployer deployer = appDeployer();
		String deploymentId = deployer.deploy(request);
		Thread.sleep(5000);
		AppStatus appStatus = deployer.status(deploymentId);
		if (resource instanceof DockerResource) {
			try {
				String containerId = getCommandOutput("docker ps -q --filter ancestor="+ TESTAPP_DOCKER_IMAGE_NAME);
				String logOutput = getCommandOutput("docker logs "+ containerId);
				assertThat(logOutput).contains("Listening for transport dt_socket at address: 8888");
				String containerPorts = getCommandOutputAll("docker port "+ containerId);
				assertThat(containerPorts).contains("8888/tcp -> 0.0.0.0:8888");
			} catch (IOException e) {
			}
		}
		else {
			assertThat(appStatus.toString()).contains("deploying");
		}

		deployer.undeploy(deploymentId);
	}

	@Test
	public void testUseDefaultDeployerProperties()  throws IOException {

		LocalDeployerProperties localDeployerProperties = new LocalDeployerProperties();
		Path tmpPath = new File(System.getProperty("java.io.tmpdir")).toPath();
		Path customWorkDirRoot = tmpPath.resolve("test-default-directory");
		localDeployerProperties.setWorkingDirectoriesRoot(customWorkDirRoot.toFile().getAbsolutePath());

		// Create a new LocalAppDeployer using a working directory that is different from the default value.
		AppDeployer appDeployer = new LocalAppDeployer(localDeployerProperties);

		List<Path> beforeDirs = getBeforePaths(customWorkDirRoot);

		Map<String, String> properties = new HashMap<>();
		properties.put("server.port", "0");
		AppDefinition definition = new AppDefinition(randomName(), properties);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);


		// Deploy
		String deploymentId = appDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer.status(deploymentId).getState()).isEqualTo(DeploymentState.deployed);
		});

		timeout = undeploymentTimeout();
		// Undeploy
		appDeployer.undeploy(deploymentId);
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer.status(deploymentId).getState()).isEqualTo(DeploymentState.unknown);
		});

		List<Path> afterDirs = getAfterPaths(customWorkDirRoot);
		assertThat(afterDirs).as("Additional working directory not created").hasSize(beforeDirs.size() + 1);
	}

	@Test
	public void testZeroPortReportsDeployed() throws IOException {
		Map<String, String> properties = new HashMap<>();
		properties.put("server.port", "0");
		Path tmpPath = new File(System.getProperty("java.io.tmpdir")).toPath();
		Path customWorkDirRoot = tmpPath.resolve("spring-cloud-deployer-app-workdir");
		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put(LocalDeployerProperties.PREFIX + ".working-directories-root", customWorkDirRoot.toFile().getAbsolutePath());

		AppDefinition definition = new AppDefinition(randomName(), properties);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, deploymentProperties);

		List<Path> beforeDirs = getBeforePaths(customWorkDirRoot);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = appDeployer().deploy(request);
		Timeout timeout = deploymentTimeout();
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.deployed);
		});

		log.info("Undeploying {}...", deploymentId);

		timeout = undeploymentTimeout();
		appDeployer().undeploy(deploymentId);
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.unknown);
		});


		List<Path> afterDirs = getAfterPaths(customWorkDirRoot);
		assertThat(afterDirs.size()).as("Additional working directory not created").isEqualTo(beforeDirs.size() + 1);
	}

	@Test
	public void testStartupProbeFail() {
		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put(LocalDeployerProperties.PREFIX + ".startup-probe.path", "/fake");

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, deploymentProperties);

		log.info("Deploying {}...", request.getDefinition().getName());

		String deploymentId = appDeployer().deploy(request);

		await().atMost(Duration.ofSeconds(30)).until(() -> appDeployer().status(deploymentId).getState() == DeploymentState.deploying);
		await().during(Duration.ofSeconds(10)).atMost(Duration.ofSeconds(12)).until(() -> appDeployer().status(deploymentId).getState() == DeploymentState.deploying);

		log.info("Undeploying {}...", deploymentId);

		Timeout timeout = undeploymentTimeout();
		appDeployer().undeploy(deploymentId);
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.unknown);
		});
	}

	@Test
	public void testStartupProbeSucceed() {
		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put(LocalDeployerProperties.PREFIX + ".startup-probe.path", "/actuator/info");

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, deploymentProperties);

		log.info("Deploying {}...", request.getDefinition().getName());

		String deploymentId = appDeployer().deploy(request);

		await().atMost(Duration.ofSeconds(30)).until(() -> appDeployer().status(deploymentId).getState() == DeploymentState.deploying);
		await().atMost(Duration.ofSeconds(12)).until(() -> appDeployer().status(deploymentId).getState() == DeploymentState.deployed);

		log.info("Undeploying {}...", deploymentId);

		Timeout timeout = undeploymentTimeout();
		appDeployer().undeploy(deploymentId);
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.unknown);
		});
	}

	@Test
	public void testHealthProbeFail() {
		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put(LocalDeployerProperties.PREFIX + ".health-probe.path", "/fake");

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, deploymentProperties);

		log.info("Deploying {}...", request.getDefinition().getName());

		String deploymentId = appDeployer().deploy(request);

		await().during(Duration.ofSeconds(10)).atMost(Duration.ofSeconds(12)).until(() -> appDeployer().status(deploymentId).getState() == DeploymentState.failed);

		log.info("Undeploying {}...", deploymentId);

		Timeout timeout = undeploymentTimeout();
		appDeployer().undeploy(deploymentId);
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.unknown);
		});
	}

	@Test
	public void testHealthProbeSucceed() {
		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put(LocalDeployerProperties.PREFIX + ".health-probe.path", "/actuator/info");

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, deploymentProperties);

		log.info("Deploying {}...", request.getDefinition().getName());

		String deploymentId = appDeployer().deploy(request);

		await().atMost(Duration.ofSeconds(30)).until(() -> appDeployer().status(deploymentId).getState() == DeploymentState.deployed);

		await().during(Duration.ofSeconds(10)).atMost(Duration.ofSeconds(15)).until(() -> appDeployer().status(deploymentId).getState() == DeploymentState.deployed);

		log.info("Undeploying {}...", deploymentId);

		Timeout timeout = undeploymentTimeout();
		appDeployer().undeploy(deploymentId);
		await().pollInterval(Duration.ofMillis(timeout.pause))
				.atMost(Duration.ofMillis(timeout.maxAttempts * timeout.pause))
				.untilAsserted(() -> {
			assertThat(appDeployer().status(deploymentId).getState()).isEqualTo(DeploymentState.unknown);
		});
	}

	private List<Path> getAfterPaths(Path customWorkDirRoot) throws IOException {
		if (!Files.exists(customWorkDirRoot)) {
			return new ArrayList<>();
		}
		return Files.walk(customWorkDirRoot, 1)
					.filter(Files::isDirectory)
					.filter(path -> !path.getFileName().toString().startsWith("."))
					.collect(Collectors.toList());
	}

	private List<Path> getBeforePaths(Path customWorkDirRoot) throws IOException {
		List<Path> beforeDirs = new ArrayList<>();
		beforeDirs.add(customWorkDirRoot);
		if (Files.exists(customWorkDirRoot)) {
			beforeDirs = Files.walk(customWorkDirRoot, 1)
					.filter(Files::isDirectory)
					.collect(Collectors.toList());
		}
		return beforeDirs;
	}

	private String getCommandOutput(String cmd) throws IOException {
		Process process = Runtime.getRuntime().exec(cmd);
		BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
		return stdInput.lines().findFirst().get();
	}

	private String getCommandOutputAll(String cmd) throws IOException {
		Process process = Runtime.getRuntime().exec(cmd);
		BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
		return stdInput.lines().collect(Collectors.joining());
	}

	@Configuration
	@EnableConfigurationProperties(LocalDeployerProperties.class)
	public static class Config {

		@Bean
		public AppDeployer appDeployer(LocalDeployerProperties properties) {
			return new LocalAppDeployer(properties);
		}
	}

}
