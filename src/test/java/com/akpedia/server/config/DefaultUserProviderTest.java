package com.akpedia.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class DefaultUserProviderTest {

    private static final String EMAIL = "aprovador@akpedia.local";

    @Mock
    private UserRepository users;

    @Test
    @DisplayName("resolves the configured email to the user's id at startup")
    void resolvesConfiguredEmailToId() {
        given(users.findByEmail(EMAIL)).willReturn(Optional.of(seededUser(7L)));

        DefaultUserProvider provider = provider();
        provider.afterPropertiesSet();

        assertThat(provider.userId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("fails with a message naming the email and the migration when the user is missing")
    void failsWhenConfiguredUserIsMissing() {
        given(users.findByEmail(EMAIL)).willReturn(Optional.empty());

        assertThatThrownBy(provider()::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                // Whoever reads this in the startup log needs to know which email was looked up
                // and where the user is supposed to come from.
                .hasMessageContaining(EMAIL)
                .hasMessageContaining("akpedia.default-user-email")
                .hasMessageContaining("V5__seed_default_approver.sql");
    }

    @Test
    @DisplayName("binds akpedia.default-user-email next to the other akpedia.* settings")
    void bindsTheConfiguredKey() {
        // The prefix is shared with akpedia.search and akpedia.embedding, which this record does
        // not declare. Those siblings being present must not keep the email from binding.
        runnerWith("akpedia.default-user-email=" + EMAIL,
                "akpedia.search.default-limit=10",
                "akpedia.embedding.base-url=http://localhost:8000")
                .run(context -> assertThat(context)
                        .getBean(DefaultUserProperties.class)
                        .extracting(DefaultUserProperties::defaultUserEmail)
                        .isEqualTo(EMAIL));
    }

    @Test
    @DisplayName("refuses a blank email instead of looking one up")
    void rejectsBlankEmail() {
        runnerWith("akpedia.default-user-email=")
                .run(context -> assertThat(context)
                        .getFailure()
                        .isInstanceOf(ConfigurationPropertiesBindException.class));
    }

    private ApplicationContextRunner runnerWith(String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DefaultUserConfig.class)
                .withPropertyValues(properties);
    }

    private DefaultUserProvider provider() {
        return new DefaultUserProvider(new DefaultUserProperties(EMAIL), users);
    }

    private User seededUser(Long id) {
        User user = new User("Aprovador Padrao", EMAIL, "placeholder-sem-login",
                new Sector("Engenharia", "setor padrao"));
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
