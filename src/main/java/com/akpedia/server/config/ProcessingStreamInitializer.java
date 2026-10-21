package com.akpedia.server.config;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes sure the processing stream and its consumer group exist before anything is published.
 *
 * <p>Runs {@code XGROUP CREATE <stream> <group> 0 MKSTREAM} at startup. The command fails with
 * {@code BUSYGROUP} when the group is already there -- on every restart, or when a worker got
 * to it first, since the worker creates it too -- and that answer is treated as success. Any
 * other failure, such as Redis being unreachable, stops the boot: a server that cannot queue
 * documents should not look healthy.
 *
 * <p>The group starts at offset {@code 0} so messages published before the first worker joined
 * are still delivered to it.
 */
@Component
@EnableConfigurationProperties(ProcessingProperties.class)
public class ProcessingStreamInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessingStreamInitializer.class);

    private final StringRedisTemplate redis;
    private final ProcessingProperties properties;

    public ProcessingStreamInitializer(StringRedisTemplate redis, ProcessingProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureGroup();
    }

    /**
     * Creates the stream and the group if missing; a no-op when the group already exists.
     *
     * @return {@code true} when this call created the group, {@code false} when it was there
     */
    public boolean ensureGroup() {
        byte[] key = properties.stream().getBytes(StandardCharsets.UTF_8);
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(key, properties.group(), ReadOffset.from("0"), true));
        } catch (DataAccessException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
            LOG.debug("Consumer group '{}' on stream '{}' already exists",
                    properties.group(), properties.stream());
            return false;
        }
        LOG.info("Created consumer group '{}' on stream '{}'", properties.group(), properties.stream());
        return true;
    }

    private static boolean isBusyGroup(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
