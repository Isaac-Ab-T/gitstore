package com.gitstore.core;

import com.gitstore.exception.AuthenticationException;
import com.gitstore.exception.DocumentNotFoundException;
import com.gitstore.exception.GitStoreException;
import com.gitstore.exception.PushConflictException;
import com.gitstore.exception.RepositoryAccessException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handles low-level Git plumbing operations:
 * - Hash Object (Blobs)
 * - Tree Construction (Trees)
 * - Commit Tree (Commits)
 * - Fast-forward push and fetch
 */
public class JGitPlumbingEngine {

    private static final Logger log = LoggerFactory.getLogger(JGitPlumbingEngine.class);

    private final String repoUri;
    private final String branch;
    private final String token;
    private final String basePath;
    private final Repository repository;

    public JGitPlumbingEngine(String repoUri, String branch, String token, String basePath) {
        this.repoUri = normalizeRepoUri(repoUri);
        this.branch = branch != null && !branch.trim().isEmpty() ? branch.trim() : "main";
        this.token = token;
        this.basePath = basePath != null ? basePath.replaceAll("^/+|/+$", "") : "data";
        this.repository = new InMemoryRepository(new DfsRepositoryDescription("gitstore-in-memory"));
    }

    public JGitPlumbingEngine(Repository repository, String repoUri, String branch, String token, String basePath) {
        this.repository = repository;
        this.repoUri = repoUri != null ? normalizeRepoUri(repoUri) : null;
        this.branch = branch != null && !branch.trim().isEmpty() ? branch.trim() : "main";
        this.token = token;
        this.basePath = basePath != null ? basePath.replaceAll("^/+|/+$", "") : "data";
    }

    private static String normalizeRepoUri(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new GitStoreException("Repository URI or 'owner/repo' must be specified.");
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("git@")) {
            return trimmed;
        }
        return "https://github.com/" + trimmed + ".git";
    }

    public CredentialsProvider getCredentialsProvider() {
        if (token != null && !token.trim().isEmpty()) {
            return new UsernamePasswordCredentialsProvider("oauth2", token);
        }
        return CredentialsProvider.getDefault();
    }

    public String resolveFullPath(String relativePath) {
        String cleaned = relativePath.replaceAll("^/+|/+$", "");
        return basePath.isEmpty() ? cleaned : basePath + "/" + cleaned;
    }

    /**
     * Reads a file content and its commit version by path.
     */
    public StoredBlob readBlob(String relativePath) {
        String fullPath = resolveFullPath(relativePath);
        syncRemoteTip();

        try {
            Ref headRef = repository.exactRef("refs/heads/" + branch);
            if (headRef == null) {
                throw new DocumentNotFoundException("Branch '" + branch + "' not found.");
            }

            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(headRef.getObjectId());
                RevTree tree = commit.getTree();

                try (TreeWalk treeWalk = TreeWalk.forPath(repository, fullPath, tree)) {
                    if (treeWalk == null) {
                        throw new DocumentNotFoundException("Document at path '" + fullPath + "' not found.");
                    }

                    ObjectId blobId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(blobId);
                    byte[] bytes = loader.getBytes();

                    return new StoredBlob(bytes, commit.name(), blobId.name());
                }
            }
        } catch (IOException e) {
            throw new GitStoreException("Failed to read Git blob at " + fullPath, e);
        }
    }

    /**
     * Checks if a path exists.
     */
    public boolean exists(String relativePath) {
        String fullPath = resolveFullPath(relativePath);
        syncRemoteTip();

        try {
            Ref headRef = repository.exactRef("refs/heads/" + branch);
            if (headRef == null) {
                return false;
            }
            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(headRef.getObjectId());
                try (TreeWalk treeWalk = TreeWalk.forPath(repository, fullPath, commit.getTree())) {
                    return treeWalk != null;
                }
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Writes a document using plumbing:
     * 1. Inserts raw blob (git hash-object -w)
     * 2. Builds updated tree (git mktree / DirCache)
     * 3. Creates commit (git commit-tree)
     * 4. Pushes ref
     */
    public String writeBlob(String relativePath, byte[] content, String commitMessage) {
        String fullPath = resolveFullPath(relativePath);
        syncRemoteTip();

        try {
            Ref headRef = repository.exactRef("refs/heads/" + branch);
            ObjectId parentCommitId = headRef != null ? headRef.getObjectId() : null;

            ObjectInserter inserter = repository.newObjectInserter();

            // 1. Plumbing: Insert Blob
            ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, content);

            // 2. Plumbing: Build Tree
            DirCache dirCache = DirCache.newInCore();
            DirCacheBuilder builder = dirCache.builder();

            // Copy existing tree if parent exists
            if (parentCommitId != null) {
                try (RevWalk revWalk = new RevWalk(repository)) {
                    RevCommit parentCommit = revWalk.parseCommit(parentCommitId);
                    try (TreeWalk treeWalk = new TreeWalk(repository)) {
                        treeWalk.addTree(parentCommit.getTree());
                        treeWalk.setRecursive(true);
                        while (treeWalk.next()) {
                            String entryPath = treeWalk.getPathString();
                            if (!entryPath.equals(fullPath)) {
                                DirCacheEntry entry = new DirCacheEntry(entryPath);
                                entry.setFileMode(treeWalk.getFileMode(0));
                                entry.setObjectId(treeWalk.getObjectId(0));
                                builder.add(entry);
                            }
                        }
                    }
                }
            }

            // Add new/updated entry
            DirCacheEntry newEntry = new DirCacheEntry(fullPath);
            newEntry.setFileMode(FileMode.REGULAR_FILE);
            newEntry.setObjectId(blobId);
            builder.add(newEntry);
            builder.finish();

            ObjectId newTreeId = dirCache.writeTree(inserter);

            // 3. Plumbing: Create Commit
            CommitBuilder commitBuilder = new CommitBuilder();
            commitBuilder.setTreeId(newTreeId);
            if (parentCommitId != null) {
                commitBuilder.setParentId(parentCommitId);
            }
            commitBuilder.setMessage(commitMessage != null ? commitMessage : "gitstore: update " + relativePath);
            PersonIdent author = new PersonIdent("GitStore", "gitstore@local");
            commitBuilder.setAuthor(author);
            commitBuilder.setCommitter(author);

            ObjectId newCommitId = inserter.insert(commitBuilder);
            inserter.flush();

            // Update local ref
            updateLocalRef(newCommitId);

            // 4. Push to remote
            pushCommit(newCommitId);

            return newCommitId.name();
        } catch (Exception e) {
            throw new GitStoreException("Failed to write Git document at " + fullPath, e);
        }
    }

    /**
     * Deletes a document by path.
     */
    public String deleteBlob(String relativePath, String commitMessage) {
        String fullPath = resolveFullPath(relativePath);
        syncRemoteTip();

        try {
            Ref headRef = repository.exactRef("refs/heads/" + branch);
            if (headRef == null) {
                throw new DocumentNotFoundException("Document not found at " + fullPath);
            }

            ObjectId parentCommitId = headRef.getObjectId();
            ObjectInserter inserter = repository.newObjectInserter();

            DirCache dirCache = DirCache.newInCore();
            DirCacheBuilder builder = dirCache.builder();
            boolean found = false;

            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit parentCommit = revWalk.parseCommit(parentCommitId);
                try (TreeWalk treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(parentCommit.getTree());
                    treeWalk.setRecursive(true);
                    while (treeWalk.next()) {
                        String entryPath = treeWalk.getPathString();
                        if (entryPath.equals(fullPath)) {
                            found = true;
                        } else {
                            DirCacheEntry entry = new DirCacheEntry(entryPath);
                            entry.setFileMode(treeWalk.getFileMode(0));
                            entry.setObjectId(treeWalk.getObjectId(0));
                            builder.add(entry);
                        }
                    }
                }
            }

            if (!found) {
                throw new DocumentNotFoundException("Document not found at " + fullPath);
            }

            builder.finish();
            ObjectId newTreeId = dirCache.writeTree(inserter);

            CommitBuilder commitBuilder = new CommitBuilder();
            commitBuilder.setTreeId(newTreeId);
            commitBuilder.setParentId(parentCommitId);
            commitBuilder.setMessage(commitMessage != null ? commitMessage : "gitstore: delete " + relativePath);
            PersonIdent author = new PersonIdent("GitStore", "gitstore@local");
            commitBuilder.setAuthor(author);
            commitBuilder.setCommitter(author);

            ObjectId newCommitId = inserter.insert(commitBuilder);
            inserter.flush();

            updateLocalRef(newCommitId);
            pushCommit(newCommitId);

            return newCommitId.name();
        } catch (DocumentNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new GitStoreException("Failed to delete Git document at " + fullPath, e);
        }
    }

    /**
     * Lists all relative file paths under a folder prefix.
     */
    public List<String> listPaths(String relativeFolder) {
        String targetPrefix = resolveFullPath(relativeFolder).replaceAll("^/+|/+$", "");
        syncRemoteTip();

        List<String> results = new ArrayList<>();
        try {
            Ref headRef = repository.exactRef("refs/heads/" + branch);
            if (headRef == null) {
                return results;
            }

            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(headRef.getObjectId());
                try (TreeWalk treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(commit.getTree());
                    treeWalk.setRecursive(true);

                    int basePathOffset = basePath.isEmpty() ? 0 : basePath.length() + 1;

                    while (treeWalk.next()) {
                        String path = treeWalk.getPathString();
                        if (targetPrefix.isEmpty() || path.startsWith(targetPrefix + "/") || path.equals(targetPrefix)) {
                            if (basePathOffset <= path.length()) {
                                results.add(path.substring(basePathOffset));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new GitStoreException("Failed to list paths under " + relativeFolder, e);
        }

        Collections.sort(results);
        return results;
    }

    private void updateLocalRef(ObjectId commitId) throws IOException {
        RefUpdate update = repository.updateRef("refs/heads/" + branch);
        update.setNewObjectId(commitId);
        update.forceUpdate();
    }

    private void syncRemoteTip() {
        if (repoUri == null) {
            return; // In-memory / local test mode
        }
        try {
            Git git = new Git(repository);
            // Fetch target branch or HEAD
            git.fetch()
                    .setRemote(repoUri)
                    .setRefSpecs(
                            new RefSpec("+refs/heads/" + branch + ":refs/heads/" + branch),
                            new RefSpec("+refs/heads/main:refs/remotes/origin/main"),
                            new RefSpec("+refs/heads/master:refs/remotes/origin/master")
                    )
                    .setCredentialsProvider(getCredentialsProvider())
                    .call();

            // If the target branch does not exist yet on remote, fork it from the default branch (main/master)
            Ref targetRef = repository.exactRef("refs/heads/" + branch);
            if (targetRef == null) {
                Ref defaultRef = repository.exactRef("refs/remotes/origin/main");
                if (defaultRef == null) {
                    defaultRef = repository.exactRef("refs/remotes/origin/master");
                }
                if (defaultRef != null) {
                    log.info("Branch '{}' not found on remote. Initializing it from '{}' ({})",
                            branch, defaultRef.getName(), defaultRef.getObjectId().name());
                    updateLocalRef(defaultRef.getObjectId());
                } else {
                    log.info("Branch '{}' not found and repository appears empty. First commit will be an orphan root commit.", branch);
                }
            }
        } catch (GitAPIException e) {
            log.debug("Fetch failed or repository is empty/uninitialized: {}", e.getMessage());
        } catch (IOException e) {
            log.warn("Failed to initialize branch ref locally: {}", e.getMessage());
        }
    }

    private void pushCommit(ObjectId commitId) {
        if (repoUri == null) {
            return; // In-memory / local test mode
        }
        try {
            Git git = new Git(repository);
            Iterable<PushResult> pushResults = git.push()
                    .setRemote(repoUri)
                    .setRefSpecs(new RefSpec(commitId.name() + ":refs/heads/" + branch))
                    .setCredentialsProvider(getCredentialsProvider())
                    .call();

            for (PushResult result : pushResults) {
                for (RemoteRefUpdate rru : result.getRemoteUpdates()) {
                    if (rru.getStatus() == RemoteRefUpdate.Status.REJECTED_NON_FAST_FORWARD) {
                        throw new PushConflictException(
                                "Push rejected (non-fast-forward). Another commit was pushed to branch '" + branch +
                                "' before this write completed. Message: " + rru.getMessage()
                        );
                    } else if (rru.getStatus() != RemoteRefUpdate.Status.OK &&
                               rru.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw new GitStoreException("Push failed with status " + rru.getStatus() + ": " + rru.getMessage());
                    }
                }
            }
        } catch (org.eclipse.jgit.api.errors.TransportException e) {
            handleTransportException(e);
        } catch (GitAPIException e) {
            throw new GitStoreException("Git push failed to " + repoUri + ": " + e.getMessage(), e);
        }
    }

    private void handleTransportException(org.eclipse.jgit.api.errors.TransportException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        if (msg.contains("not authorized") || msg.contains("authentication not supported") ||
            msg.contains("invalid credentials") || msg.contains("401")) {
            throw new AuthenticationException(
                    "GitHub authentication failed. Please verify that your GITHUB_TOKEN is valid, active, and not expired.", e
            );
        }

        if (msg.contains("permission denied") || msg.contains("403")) {
            throw new RepositoryAccessException(
                    "Permission denied for repository '" + repoUri + "'. Ensure the token has 'Contents: Read and write' scope.", e
            );
        }

        if (msg.contains("not found") || msg.contains("404")) {
            throw new RepositoryAccessException(
                    "Repository '" + repoUri + "' not found. Check the owner/repo path and repository visibility.", e
            );
        }

        throw new GitStoreException("Git transport error communicating with remote repository: " + e.getMessage(), e);
    }

    public static class StoredBlob {
        private final byte[] content;
        private final String commitSha;
        private final String blobSha;

        public StoredBlob(byte[] content, String commitSha, String blobSha) {
            this.content = content;
            this.commitSha = commitSha;
            this.blobSha = blobSha;
        }

        public byte[] getContent() {
            return content;
        }

        public String getCommitSha() {
            return commitSha;
        }

        public String getBlobSha() {
            return blobSha;
        }
    }
}
