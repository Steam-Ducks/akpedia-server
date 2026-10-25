package com.akpedia.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The consumer group bootstrap, against a real Redis. A throwaway stream key keeps the tests
 * from touching the one the application itself uses.
 */
@SpringBootTest
class ProcessingStreamInitializerTest {

    private static final String STREAM = "test:documents:processing";
    private static final String GROUP = "ml-workers";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ProcessingStreamInitializer applicationInitializer;

    @Autowired
    private ProcessingProperties applicationProperties;

    @AfterEach
    void removeTestStream() {
        redis.delete(STREAM);
    }

    @Test
    @DisplayName("booting leaves the configured group on the configured stream")
    void startupCreatesTheConfiguredGroup() {
        // The context already ran the initializer once; running it again must find the group.
        assertThat(groupNames(applicationProperties.stream())).contains(applicationProperties.group());
        assertThat(applicationInitializer.ensureGroup()).isFalse();
    }

    @Test
    @DisplayName("creates the stream and the group when neither exists")
    void createsStreamAndGroup() {
        redis.delete(STREAM);

        boolean created = initializerFor(STREAM).ensureGroup();

        assertThat(created).isTrue();
        assertThat(redis.hasKey(STREAM)).isTrue();
        assertThat(groupNames(STREAM)).containsExactly(GROUP);
    }

    @Test
    @DisplayName("running again when the group exists is a no-op, not an error")
    void isIdempotent() {
        ProcessingStreamInitializer initializer = initializerFor(STREAM);
        initializer.ensureGroup();

        assertThat(initializer.ensureGroup()).isFalse();
        assertThat(groupNames(STREAM)).containsExactly(GROUP);
    }

    private ProcessingStreamInitializer initializerFor(String stream) {
        return new ProcessingStreamInitializer(redis, new ProcessingProperties(stream, GROUP, Duration.ofSeconds(30), Duration.ofMinutes(1), 50));
    }

    private List<String> groupNames(String stream) {
        return redis.opsForStream().groups(stream).stream().map(XInfoGroup::groupName).toList();
    }
}
