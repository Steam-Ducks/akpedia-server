package com.akpedia.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akpedia.server.AkpediaServerApplication;
import com.akpedia.server.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The startup contract around the seeded user, against a real database and real migrations.
 * The unit-level behaviour lives in {@link DefaultUserProviderTest}; what is checked here is
 * that a full boot resolves the user V5 seeds, and that a boot without it stops.
 */
@SpringBootTest
class DefaultUserStartupTest {

    @Autowired
    private DefaultUserProvider provider;

    @Autowired
    private UserRepository users;

    @Autowired
    private DefaultUserProperties properties;

    @Test
    @DisplayName("booting resolves the configured user to the seeded id")
    void startupResolvesTheSeededUser() {
        // That the context got this far already proves the resolution happened; this pins down
        // that it landed on the seeded row rather than on some other user.
        Long seeded = users.findByEmail(properties.defaultUserEmail()).orElseThrow().getId();

        assertThat(provider.userId()).isEqualTo(seeded);
    }

    @Test
    @DisplayName("booting with an email no user has fails, naming the email and the migration")
    void startupFailsWhenConfiguredUserDoesNotExist() {
        // A separate context on purpose: the point is the failure during startup, which the
        // surrounding @SpringBootTest cannot show because it has to start successfully.
        assertThatThrownBy(() -> new SpringApplicationBuilder(AkpediaServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties("akpedia.default-user-email=nao-existe@akpedia.local")
                .run()
                .close())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nao-existe@akpedia.local")
                .hasMessageContaining("akpedia.default-user-email")
                .hasMessageContaining("V5__seed_default_approver.sql");
    }
}
