package com.td.helpers;

import com.td.models.CommitModel;
import com.td.models.DiffModel;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

public class VersionControlHelper implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VersionControlHelper.class);
    private static final String GIT_FOLDER = ".git";

    private Git gitProject;

    /***
     * Instantiate a new VersionControlHelper by using an existing repository.
     */
    public VersionControlHelper(File path) throws IOException {
        this.gitProject = openProject(new File(Paths.get(path.toPath().toString(), GIT_FOLDER).toUri()));
    }

    /***
     * Instantiate a new VersionControlHelper by cloning a repository from a URI.
     */
    public VersionControlHelper(String uri, File path) throws GitAPIException {
        this.gitProject = cloneProject(uri, path);
    }

    /***
     * Retrieves all the list of commits on the main branch.
     *
     * Edit: Also adds the diff between the previous commit as well.
     */
    public List<CommitModel> getCommits() {
        List<CommitModel> result = new ArrayList<>();
        try {
            Iterable<RevCommit> commits = gitProject.log().all().call();
            int count = 10; //TODO: remove this. Added for performance reasons
            for (RevCommit commit : commits) {
                PersonIdent committer = commit.getCommitterIdent();
                Date date = committer.getWhen();
                TimeZone zone = committer.getTimeZone();

                CommitModel model = new CommitModel();
                model.setSha(commit.getName());
                model.setAuthor(committer.getName());
                model.setMessage(commit.getFullMessage());
                model.setTimestamp(LocalDateTime.ofInstant(date.toInstant(), zone.toZoneId()));
                model.setDiff(this.getDiff(model.getSha() + "^", model.getSha()));

                result.add(model);

                //TODO: remove this. Added for performance reasons
                count--;
                if (count < 1) {
                    break;
                }
            }
        } catch (GitAPIException | IOException e) {
            LOGGER.error("An exception occurred when retrieving list of commits.", e);
        }

        return result;
    }

    /***
     * Checks out a specific revision of the repository.
     * @param commitSHA SHA of commit to be checked out
     * @throws GitAPIException in case of error (e.g. commit does not exist)
     */
    public void checkoutRevision(String commitSHA) throws GitAPIException {
        LOGGER.info(String.format("Checking out revision %s", commitSHA));
        gitProject.checkout().setName(commitSHA).call();
    }

    /**
     * Opens an existing repository.
     */
    private Git openProject(File repoFile) throws IOException {
        LOGGER.info(String.format("Opening git project residing at %s", repoFile.getAbsolutePath()));
        return new Git(new FileRepositoryBuilder().setGitDir(repoFile).readEnvironment().findGitDir().build());
    }

    /**
     * Clones a repository from URI to a path.
     */
    private Git cloneProject(String uri, File path) throws GitAPIException {
        LOGGER.info(String.format("Cloning git project from %s to path %s", uri, path.getAbsolutePath()));
        return Git.cloneRepository().setURI(uri).setDirectory(path).call();
    }

    /**
     * Lists differences between SHAs of two commits.
     *
     * @param oldCommit commit sha
     * @param newCommit commit sha
     * @throws GitAPIException
     * @throws IOException
     */
    public DiffModel getDiff(String oldCommit, String newCommit) throws GitAPIException, IOException {
        Repository repository = gitProject.getRepository();
        final List<DiffEntry> diffs = gitProject.diff().setOldTree(prepareTreeParser(repository, oldCommit))
                .setNewTree(prepareTreeParser(repository, newCommit)).call();

        DiffModel diffModel = new DiffModel();
        diffModel.setTotalChanges(diffs.size());

        for (DiffEntry diff : diffs) {
            switch (diff.getChangeType()) {
                case ADD:
                case COPY: {
                    diffModel.add(diff.getNewPath());
                    break;
                }
                case RENAME:
                case MODIFY: {
                    diffModel.modify(diff.getNewPath());
                    break;
                }
                case DELETE: {
                    diffModel.delete(diff.getOldPath());
                }
            }
        }

        return diffModel;
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repository, String objectId) throws IOException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        //noinspection Duplicates
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(repository.resolve(objectId));
            RevTree tree = walk.parseTree(commit.getTree().getId());

            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }

            walk.dispose();

            return treeParser;
        }
    }

    @Override
    public void close() {
        // just to make sure we are closing the repository entirely.
        this.gitProject.getRepository().close();
        this.gitProject.close();
    }

}