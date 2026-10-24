package com.gitstore.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gitstore")
public class GitStoreProperties {

    /**
     * GitHub repository in 'owner/repo' format or full Git clone URL.
     * E.g. 'octocat/my-store' or 'https://github.com/octocat/my-store.git'.
     */
    private String repo;

    /**
     * Target Git branch. Defaults to 'main'.
     */
    private String branch = "main";

    /**
     * GitHub Personal Access Token (PAT).
     */
    private String token;

    /**
     * Base directory path inside the Git repository. Defaults to 'data'.
     */
    private String basePath = "data";

    public String getRepo() {
        return repo;
    }

    public void setRepo(String repo) {
        this.repo = repo;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
