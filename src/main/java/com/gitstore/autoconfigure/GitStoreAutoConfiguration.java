package com.gitstore.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitstore.core.GitDocumentTemplate;
import com.gitstore.core.JGitPlumbingEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(GitStoreProperties.class)
@ConditionalOnProperty(prefix = "gitstore", name = "repo")
public class GitStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JGitPlumbingEngine jGitPlumbingEngine(GitStoreProperties properties) {
        return new JGitPlumbingEngine(
                properties.getRepo(),
                properties.getBranch(),
                properties.getToken(),
                properties.getBasePath()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public GitDocumentTemplate gitDocumentTemplate(JGitPlumbingEngine engine,
                                                   @org.springframework.beans.factory.annotation.Autowired(required = false) ObjectMapper objectMapper) {
        if (objectMapper != null) {
            return new GitDocumentTemplate(engine, objectMapper);
        }
        return new GitDocumentTemplate(engine);
    }
}
