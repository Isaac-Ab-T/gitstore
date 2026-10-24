package com.gitstore;

import com.gitstore.annotation.GitDocument;
import com.gitstore.annotation.GitId;
import com.gitstore.annotation.GitVersion;
import com.gitstore.core.GitDocumentTemplate;
import com.gitstore.core.JGitPlumbingEngine;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class GitDocumentTemplateTest {

    private GitDocumentTemplate template;
    private InMemoryRepository repository;

    @GitDocument(path = "users")
    public static class User {
        @GitId
        private String username;

        @GitVersion
        private String version;

        private String email;
        private int age;
        private Instant registeredAt;

        public User() {}

        public User(String username, String email, int age) {
            this.username = username;
            this.email = email;
            this.age = age;
            this.registeredAt = Instant.now();
        }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public Instant getRegisteredAt() { return registeredAt; }
        public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
    }

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository(new DfsRepositoryDescription("test-repo"));
        JGitPlumbingEngine engine = new JGitPlumbingEngine(repository, null, "main", null, "data");
        template = new GitDocumentTemplate(engine);
    }

    @Test
    void testSaveAndFindById() {
        User user = new User("alice", "alice@example.com", 28);
        User saved = template.save(user);

        assertThat(saved.getVersion()).isNotNull();

        Optional<User> found = template.findById("alice", User.class);
        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("alice");
        assertThat(found.get().getEmail()).isEqualTo("alice@example.com");
        assertThat(found.get().getAge()).isEqualTo(28);
        assertThat(found.get().getVersion()).isEqualTo(saved.getVersion());
    }

    @Test
    void testExistsAndDelete() {
        User user = new User("bob", "bob@example.com", 32);
        template.save(user);

        assertThat(template.existsById("bob", User.class)).isTrue();
        assertThat(template.existsById("charlie", User.class)).isFalse();

        template.deleteById("bob", User.class);
        assertThat(template.existsById("bob", User.class)).isFalse();
        assertThat(template.findById("bob", User.class)).isEmpty();
    }

    @Test
    void testFindAll() {
        template.save(new User("user1", "u1@test.com", 20));
        template.save(new User("user2", "u2@test.com", 25));
        template.save(new User("user3", "u3@test.com", 30));

        List<User> all = template.findAll(User.class);
        assertThat(all).hasSize(3);
        assertThat(all).extracting(User::getUsername)
                .containsExactlyInAnyOrder("user1", "user2", "user3");
    }
}
