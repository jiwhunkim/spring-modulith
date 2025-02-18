/*
 * Copyright 2023-2025 the original author or authors.
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
package org.springframework.modulith.events.aws.sqs;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.Externalized;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for SQS-based event publication.
 *
 * @author Maciej Walkowiak
 * @author Oliver Drotbohm
 * @since 1.1
 */
@SpringBootTest
class SqsEventPublicationIntegrationTests {

	@Autowired TestPublisher publisher;
	@Autowired SqsAsyncClient sqsAsyncClient;

	@SpringBootApplication
	static class TestConfiguration {

		@Bean
		LocalStackContainer localStackContainer() {
			return new LocalStackContainer(DockerImageName.parse("localstack/localstack:latest"));
		}

		@Bean
		DynamicPropertyRegistrar localStackDynamicProperties(LocalStackContainer localstack) {

			return registry -> {

				registry.add("spring.cloud.aws.endpoint", localstack::getEndpoint);
				registry.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
				registry.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
				registry.add("spring.cloud.aws.region.static", localstack::getRegion);
			};
		}

		@Bean
		TestPublisher testPublisher(ApplicationEventPublisher publisher) {
			return new TestPublisher(publisher);
		}

		@Bean
		TestListener testListener() {
			return new TestListener();
		}
	}

	@Test
	void publishesEventToSqs() throws Exception {

		var queueUrl = sqsAsyncClient.createQueue(request -> request.queueName("target"))
				.join()
				.queueUrl();

		publisher.publishEvent();

		await().untilAsserted(() -> {
			var response = sqsAsyncClient.receiveMessage(r -> r.queueUrl(queueUrl)).join();

			assertThat(response.hasMessages()).isTrue();
		});
	}

	@Test
	void publishesEventWithGroupIdToSqs() throws Exception {

		var queueUrl = sqsAsyncClient.createQueue(request -> request.queueName("target.fifo")
				.attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
				.join()
				.queueUrl();

		publisher.publishEventWithKey();

		await().untilAsserted(() -> {
			var response = sqsAsyncClient.receiveMessage(r -> r.queueUrl(queueUrl)).join();

			assertThat(response.hasMessages()).isTrue();
		});
	}

	@Externalized("target")
	static class TestEvent {}

	@Value
	@Externalized("target.fifo::#{getKey()}")
	static class TestEventWithKey {
		String key;
	}

	@RequiredArgsConstructor
	static class TestPublisher {

		private final ApplicationEventPublisher events;

		@Transactional
		void publishEvent() {
			events.publishEvent(new TestEvent());
		}

		@Transactional
		void publishEventWithKey() {
			events.publishEvent(new TestEventWithKey("aKey"));
		}
	}

	static class TestListener {

		@ApplicationModuleListener
		void on(TestEvent event) {}

		@ApplicationModuleListener
		void on(TestEventWithKey event) {}
	}
}
