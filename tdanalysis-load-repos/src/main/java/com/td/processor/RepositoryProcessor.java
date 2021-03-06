package com.td.processor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import com.td.db.IssueRepository;
import com.td.db.ProjectRepository;
import com.td.helpers.VersionControlHelper;
import com.td.models.IssueModel;
import com.td.models.RepositoryModel;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RepositoryProcessor {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryProcessor.class);

    @Value("${git.clone.path}")
    private String tempFolder;

    @Value("${jira.username}")
    private String jiraUsername;

    @Value("${jira.password}")
    private String jiraPassword;

    @Value("${github.username}")
    private String githubUsername;

    @Value("${github.token}")
    private String githubToken;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CommitProcessor commitProcessor;

    /**
     * Process the repositories in the file as follows:
     * 1. Clones them to local disk or reads them if they are not present.
     * 2. Process each commit one by one.
     */
    public void processRepositories(List<RepositoryModel> repositories) {
        repositories.stream().forEach(this::processRepository);
    }

    public void processRepository(RepositoryModel repo) {
        projectRepository.save(repo);
        IssueProcessor issueProcessor = createIssueProcessor(repo);

        Runnable run = () -> {
            Optional<VersionControlHelper> optVc = readOrCloneRepository(repo);
            if (!optVc.isPresent()) {
                logger.error(String.format("Unable to read or clone repository %s, thread will exit.", repo.getName()));
                return;
            }

            VersionControlHelper vch = optVc.get();

            // process commits
            vch.getCommitStream().forEachOrdered(commit -> {

                // checkout revision
                if (!vch.checkoutRevision(commit.getSha())) {
                    return;
                }

                // get issues
                List<IssueModel> issues = issueProcessor.getIssues(commit);
                commit.setIssueIds(issueProcessor.getIssueIds(issues));
                issueRepository.save(issues);

                // process commit
                commitProcessor.processCommit(commit, repo);
                commitProcessor.saveCommit(commit);
            });

            vch.close();
        };

        Thread t = new Thread(run);
        t.start();
    }

    /**
     * This method will try to clone a repository to the local disk. If the
     * repository already exists it will try to open it.
     * @return an {@link Optional} containing a {@link VersionControlHelper} if
     * successful or null otherwise.  
     */
    Optional<VersionControlHelper> readOrCloneRepository(RepositoryModel repo) {
        logger.info(String.format("Reading repository info %s:%s", repo.getAuthor(), repo.getName()));

        File repoPath = new File(Paths.get(tempFolder, repo.getName()).toString());
        VersionControlHelper versionControlHelper = null;

        try {
            versionControlHelper = repoPath.exists() ? new VersionControlHelper(repoPath)
                    : new VersionControlHelper(repo.getURI(), repoPath);
            repo.setProjectFolder(repoPath);
        } catch (IOException | GitAPIException e) {
            logger.error(String.format("An error occurred when processing repository %s", repo.getURI()), e);
        } catch (SecurityException e) {
            logger.error("Read cccess to the specified folder is restricted", e);
        }

        return Optional.ofNullable(versionControlHelper);
    }

    private IssueProcessor createIssueProcessor(RepositoryModel repo) {
        if (repo.getIssueTrackerURI().contains("jira")) {
            return new IssueProcessor(jiraUsername, jiraPassword, repo);
        } else {
            return new IssueProcessor(githubUsername, githubToken, repo);
        }
    }
}
