# GitStore 📦

> **A Git-backed Object Document Mapper (ODM) for Java & Spring Boot using JGit plumbing.**  
> Think of it as **MongoDB for Git** — define your document classes with annotations, and GitStore handles serialization, tree updates, and versioned commits directly against your GitHub/Git repository.

---

## 🌟 Why GitStore?

- 🍃 **MongoDB-like ODM Experience**: Annotate your POJOs with `@GitDocument` and `@GitId`.
- ⚡ **Pure JGit Plumbing (No Porcelain, No Checked-Out Files)**: Interacts with Git's Object Database in memory via `ObjectInserter` (`hash-object`), `DirCache` (`mktree`), and `CommitBuilder` (`commit-tree`). Zero local disk clutter.
- 📜 **Built-in Audit Trail**: Every document write is an atomic commit with an exact SHA. Use `@GitVersion` to capture the commit hash automatically.
- 🔌 **Spring Boot 3 Starter Ready**: Plug-and-play auto-configuration — just add credentials to `application.yml` and `@Autowired private GitDocumentTemplate gitTemplate;`.
- ☕ **Plain Java Support**: Works equally well in standalone Java applications with zero Spring dependency.

---

## 🏗️ Architecture & How It Maps to Git

When you define a document entity:

```java
@GitDocument(path = "customers")
public class Customer {
    @GitId
    private String id;       // e.g., "cust-101"

    @GitVersion
    private String version;  // Injected with Git commit SHA: "7f4c9a..."

    private String name;
    private String tier;
}
```

GitStore stores it in the remote repository as:

```text
your-github-repo/
└── data/                        <-- Base path (configurable)
    └── customers/               <-- @GitDocument(path = "customers")
        ├── cust-101.json        <-- @GitId serialized as JSON
        ├── cust-102.json
        └── cust-103.json
```

---

## 🚀 Getting Started

### 1. Maven Dependency

Add GitStore to your `pom.xml`:

```xml
<dependency>
    <groupId>com.gitstore</groupId>
    <artifactId>gitstore-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

---

### 2. Configuration

Add your GitHub repository details and token to `application.yml` (or `application.properties`):

```yaml
gitstore:
  repo: "username/my-data-store"   # owner/repo format or https:// clone URL
  branch: "main"                   # Target branch (defaults to 'main')
  token: "${GITHUB_TOKEN}"         # GitHub Personal Access Token (PAT)
  base-path: "data"                # Root directory inside repo (defaults to 'data')
```

> 💡 **GitHub Token Requirements**:
> - **Fine-Grained PAT**: Repository permissions -> `Contents: Read and write`.
> - **Classic PAT**: Select the `repo` scope.

---

## 📖 Complete Usage Walkthrough

### Step 1: Define Your Document

```java
package com.example.demo.model;

import com.gitstore.annotation.GitDocument;
import com.gitstore.annotation.GitId;
import com.gitstore.annotation.GitVersion;

import java.time.Instant;
import java.util.Map;

@GitDocument(path = "users")
public class UserProfile {

    @GitId
    private String username;

    @GitVersion
    private String version; // Automatically populated with commit SHA

    private String displayName;
    private String email;
    private Map<String, Object> preferences;
    private Instant createdAt;

    public UserProfile() {}

    public UserProfile(String username, String displayName, String email) {
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.createdAt = Instant.now();
    }

    // Standard Getters and Setters
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Map<String, Object> getPreferences() { return preferences; }
    public void setPreferences(Map<String, Object> preferences) { this.preferences = preferences; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

---

### Step 2: Use `GitDocumentTemplate` in Spring Boot

Just inject `GitDocumentTemplate` anywhere in your application:

```java
package com.example.demo.service;

import com.example.demo.model.UserProfile;
import com.gitstore.core.GitDocumentTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    @Autowired
    private GitDocumentTemplate gitTemplate;

    /**
     * Create or update a user document
     */
    public UserProfile saveUser(String username, String name, String email) {
        UserProfile user = new UserProfile(username, name, email);
        
        // Saves document and sets user.version to the new Git commit SHA
        UserProfile saved = gitTemplate.save(user);
        System.out.println("Saved under commit SHA: " + saved.getVersion());
        return saved;
    }

    /**
     * Find a user by ID
     */
    public Optional<UserProfile> findUser(String username) {
        return gitTemplate.findById(username, UserProfile.class);
    }

    /**
     * Check if a user exists without fetching the full JSON content
     */
    public boolean userExists(String username) {
        return gitTemplate.existsById(username, UserProfile.class);
    }

    /**
     * Fetch all users in the 'users/' collection
     */
    public List<UserProfile> listAllUsers() {
        return gitTemplate.findAll(UserProfile.class);
    }

    /**
     * Delete a user document
     */
    public void deleteUser(String username) {
        gitTemplate.deleteById(username, UserProfile.class);
    }
}
```

---

### Step 3: Standalone Java Usage (Without Spring)

If you are not using Spring Boot, you can instantiate the template programmatically:

```java
import com.gitstore.core.GitDocumentTemplate;
import com.gitstore.core.JGitPlumbingEngine;
import com.example.demo.model.UserProfile;

public class Main {
    public static void main(String[] args) {
        // Initialize the low-level JGit plumbing engine
        JGitPlumbingEngine engine = new JGitPlumbingEngine(
            "my-org/my-storage-repo",       // repo
            "main",                         // branch
            System.getenv("GITHUB_TOKEN"),  // token
            "data"                          // base path
        );

        // Create the document template
        GitDocumentTemplate template = new GitDocumentTemplate(engine);

        // Save
        UserProfile user = new UserProfile("octocat", "Mona Lisa", "mona@github.com");
        template.save(user);

        // Fetch
        template.findById("octocat", UserProfile.class).ifPresent(found -> {
            System.out.println("Found: " + found.getDisplayName());
            System.out.println("Commit SHA: " + found.getVersion());
        });
    }
}
```

---

## 🔬 Under the Hood: The JGit Plumbing Engine

GitStore avoids high-level "porcelain" commands (`git checkout`, `git add`, `git commit`) and operates directly on Git's **Object Database (ODB)**:

| Action | Plumbing Mechanism | What Happens |
| :--- | :--- | :--- |
| **Write Object** | `ObjectInserter.insert(OBJ_BLOB, jsonBytes)` | Hashes the JSON payload into a compressed Git `blob` object (equivalent to `git hash-object -w`). |
| **Update Tree** | `DirCache` & `writeTree(inserter)` | Modifies the repository directory Merkle tree in memory to point the document's path to the new blob (equivalent to `git mktree`). |
| **Commit** | `CommitBuilder` | Creates a commit pointing to the new tree and parent commit (equivalent to `git commit-tree`). |
| **Push** | JGit Transport HTTP/SSL | Advances the remote branch ref using authenticated smart HTTP. |
| **Read Object** | `TreeWalk.forPath` + `ObjectReader` | Navigates the Merkle tree graph to the exact path and streams only that blob into Jackson. |

---

## 🛡️ License

MIT License. Feel free to use, modify, and distribute.
