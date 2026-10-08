package com.akpedia.server.config;

import com.akpedia.server.entity.User;
import com.akpedia.server.repository.UserRepository;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code akpedia.default-user-email} to the seeded user's id once, at startup.
 *
 * <p>Resolving here rather than per request is what makes a missing seed a startup failure that
 * names the problem, instead of an unexplained 404 on the first upload. {@link InitializingBean}
 * runs while the beans are still being created, so the web server never starts taking requests
 * on a database without the user. The repository is only reachable after Flyway has run, because
 * Spring Boot makes the entity manager depend on the migration initializer.
 */
@Component
public class DefaultUserProvider implements InitializingBean {

    private final DefaultUserProperties properties;
    private final UserRepository users;

    private Long userId;

    public DefaultUserProvider(DefaultUserProperties properties, UserRepository users) {
        this.properties = properties;
        this.users = users;
    }

    @Override
    public void afterPropertiesSet() {
        String email = properties.defaultUserEmail();
        this.userId = users.findByEmail(email)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException(
                        ("Usuario padrao nao encontrado pelo email '%s'. "
                                + "A configuracao akpedia.default-user-email (variavel "
                                + "DEFAULT_USER_EMAIL) tem que apontar para um usuario que exista "
                                + "no banco. Confira se a migration V5__seed_default_approver.sql "
                                + "rodou e se o email configurado e o mesmo que ela insere.")
                                .formatted(email)));
    }

    /**
     * Id of the configured user, already resolved.
     *
     * @return the id; never {@code null}, since startup fails when it cannot be resolved
     */
    public Long userId() {
        return userId;
    }
}
