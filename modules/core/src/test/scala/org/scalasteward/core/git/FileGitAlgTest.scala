package org.scalasteward.core.git

import better.files.File
import cats.Monad
import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.scalasteward.core.TestInstances.ioLogger
import org.scalasteward.core.git.FileGitAlgTest.{
  conflictsNo,
  conflictsYes,
  ioAuxGitAlg,
  ioGitAlg,
  master
}
import org.scalasteward.core.io.FileAlgTest.ioFileAlg
import org.scalasteward.core.io.ProcessAlgTest.ioProcessAlg
import org.scalasteward.core.io.{FileAlg, ProcessAlg, WorkspaceAlg}
import org.scalasteward.core.mock.MockConfig.{config, mockRoot}
import org.scalasteward.core.util.Nel

class FileGitAlgTest extends CatsEffectSuite {
  private val rootDir = mockRoot / "git-tests"

  test("add with .gitignore") {
    val repo = rootDir / "branchgitignpreAuthors"
    for {
      _ <- ioAuxGitAlg.createRepo(repo)

      gitIgnoreFile = repo / ".gitignore"
      _ <- ioFileAlg.writeFile(gitIgnoreFile, "ignored.txt")
      _ <- ioAuxGitAlg.addFiles(repo, gitIgnoreFile)

      ignoredFile = repo / "ignored.txt"
      _ <- ioFileAlg.writeFile(ignoredFile, "irrelevant")
      ignoredFileCheck <- ioGitAlg.checkIgnore(repo, ignoredFile.pathAsString)

      notIgnoredFile = repo / "not-ignored.txt"
      _ <- ioFileAlg.writeFile(notIgnoredFile, "irrelevant")
      notIgnoredFileCheck <- ioGitAlg.checkIgnore(repo, notIgnoredFile.pathAsString)

    } yield {
      assert(ignoredFileCheck, "The file is in .gitignore, checkIgnore should return true.")
      assert(
        !notIgnoredFileCheck,
        "The file is not in .gitignore, checkIgnore should return false."
      )
    }
  }

  test("branchAuthors") {
    val repo = rootDir / "branchAuthors"
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      _ <- ioAuxGitAlg.createConflict(repo)
      authors <- ioGitAlg.branchAuthors(repo, conflictsNo, master)
      _ = assertEquals(authors, List("'Bot Doe'"))
    } yield ()
  }

  test("branchExists") {
    val repo = rootDir / "branchExists"
    val (foo, bar) = (Branch("foo"), Branch("bar"))
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      _ <- ioGitAlg.createBranch(repo, foo)
      b1 <- ioGitAlg.branchExists(repo, foo)
      b2 <- ioGitAlg.branchExists(repo, bar)
      _ <- ioGitAlg.checkoutBranch(repo, master)
      _ <- ioGitAlg.deleteLocalBranch(repo, foo)
      b3 <- ioGitAlg.branchExists(repo, foo)
      _ = assertEquals((b1, b2, b3), (true, false, false))
    } yield ()
  }

  test("branchesDiffer") {
    val repo = rootDir / "branchesDiffer"
    val (foo, bar) = (Branch("foo"), Branch("bar"))
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      _ <- ioGitAlg.createBranch(repo, foo)
      _ <- ioGitAlg.createBranch(repo, bar)
      b1 <- ioGitAlg.branchesDiffer(repo, bar, foo)
      _ <- ioFileAlg.writeFile(repo / "test.txt", "hello")
      _ <- ioAuxGitAlg.git("add", "test.txt")(repo)
      _ <- ioGitAlg.commitAll(repo, CommitMsg("Add test.txt"), signoffCommits = None)
      b2 <- ioGitAlg.branchesDiffer(repo, foo, bar)
      _ <- ioAuxGitAlg.git("rm", "test.txt")(repo)
      _ <- ioGitAlg.commitAll(repo, CommitMsg("Remove test.txt"), signoffCommits = None)
      b3 <- ioGitAlg.branchesDiffer(repo, foo, bar)
      _ = assertEquals((b1, b2, b3), (false, true, false))
    } yield ()
  }

  test("cloneExists") {
    val repo = rootDir / "cloneExists"
    for {
      e1 <- ioGitAlg.cloneExists(repo)
      _ <- ioAuxGitAlg.createRepo(repo)
      e2 <- ioGitAlg.cloneExists(repo)
      _ <- ioGitAlg.removeClone(repo)
      e3 <- ioGitAlg.cloneExists(repo)
      _ = assertEquals((e1, e2, e3), (false, true, false))
    } yield ()
  }

  test("containsChanges") {
    val repo = rootDir / "containsChanges"
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      _ <- ioFileAlg.writeFile(repo / "test.txt", "hello")
      _ <- ioAuxGitAlg.git("add", "test.txt")(repo)
      _ <- ioGitAlg.commitAll(repo, CommitMsg("Add test.txt"), signoffCommits = None)
      c1 <- ioGitAlg.containsChanges(repo)
      _ <- ioFileAlg.writeFile(repo / "test.txt", "hello world")
      c2 <- ioGitAlg.containsChanges(repo)
      m2 = CommitMsg("Modify test.txt", coAuthoredBy = List(Author("name", "email")))
      _ <- ioGitAlg.commitAllIfDirty(repo, m2, signoffCommits = None)
      c3 <- ioGitAlg.containsChanges(repo)
      _ = assertEquals((c1, c2, c3), (false, true, false))
    } yield ()
  }

  test("currentBranch") {
    val repo = rootDir / "currentBranch"
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      branch <- ioGitAlg.currentBranch(repo)
      _ <- ioGitAlg.latestSha1(repo, branch)
      _ = assertEquals(branch, master)
    } yield ()
  }

  test("discardChanges") {
    val repo = rootDir / "discardChanges"
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      file = repo / "test.txt"
      _ <- ioFileAlg.writeFile(file, "hello")
      _ <- ioAuxGitAlg.git("add", "test.txt")(repo)
      _ <- ioGitAlg.commitAll(repo, CommitMsg("Add test.txt"), signoffCommits = None)
      _ <- ioFileAlg.writeFile(file, "world")
      before <- ioFileAlg.readFile(file)
      _ <- ioGitAlg.discardChanges(repo)
      after <- ioFileAlg.readFile(file)
      _ = assertEquals(before, Some("world"))
      _ = assertEquals(after, Some("hello"))
    } yield ()
  }

  test("findFilesContaining") {
    val repo = rootDir / "findFilesContaining"
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      _ <- ioAuxGitAlg.createConflict(repo)
      files <- ioGitAlg.findFilesContaining(repo, "line1")
      _ = assertEquals(files, List("file1", "file2"))
    } yield ()
  }

  test("hasConflicts") {
    val repo = rootDir / "hasConflicts"
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      _ <- ioAuxGitAlg.createConflict(repo)
      c1 <- ioGitAlg.hasConflicts(repo, conflictsYes, master)
      c2 <- ioGitAlg.hasConflicts(repo, conflictsNo, master)
      _ = assertEquals((c1, c2), (true, false))
    } yield ()
  }

  test("isMerged") {
    val repo = rootDir / "isMerged"
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      _ <- ioAuxGitAlg.createConflict(repo)
      m1 <- ioGitAlg.isMerged(repo, conflictsNo, master)
      _ <- ioAuxGitAlg.git("merge", conflictsNo.name)(repo)
      m2 <- ioGitAlg.isMerged(repo, conflictsNo, master)
      _ = assertEquals((m1, m2), (false, true))
    } yield ()
  }

  test("resetHard") {
    val repo = rootDir / "resetHard"
    for {
      _ <- ioAuxGitAlg.createRepo(repo)
      _ <- ioAuxGitAlg.createConflict(repo)
      branch = conflictsYes
      c1 <- ioGitAlg.hasConflicts(repo, branch, master)
      d1 <- ioGitAlg.branchesDiffer(repo, master, branch)
      _ <- ioGitAlg.checkoutBranch(repo, branch)
      _ <- ioGitAlg.resetHard(repo, master)
      c2 <- ioGitAlg.hasConflicts(repo, branch, master)
      d2 <- ioGitAlg.branchesDiffer(repo, master, branch)
      _ = assertEquals((c1, d1, c2, d2), (true, true, false, false))
    } yield ()
  }

  test("version") {
    assertIOBoolean(ioGitAlg.version.map(_.nonEmpty))
  }
}

object FileGitAlgTest {
  private val master: Branch = Branch("master")
  private val conflictsNo: Branch = Branch("conflicts-no")
  private val conflictsYes: Branch = Branch("conflicts-yes")

  final class AuxGitAlg[F[_]](using
      fileAlg: FileAlg[F],
      gitAlg: GenGitAlg[F, File],
      processAlg: ProcessAlg[F],
      F: Monad[F]
  ) {
    def git(args: String*)(repo: File): F[Unit] =
      processAlg.exec(Nel.of("git", args*), repo).void

    def createRepo(repo: File): F[Unit] =
      for {
        _ <- gitAlg.removeClone(repo)
        _ <- fileAlg.ensureExists(repo)
        _ <- git("-c", s"init.defaultBranch=${master.name}", "init", ".")(repo)
        _ <- gitAlg.setAuthor(repo, config.gitCfg.gitAuthor)
        _ <- git("commit", "--allow-empty", "-m", "Initial commit")(repo)
      } yield ()

    def addFiles(repo: File, files: File*): F[Unit] =
      files.toList.traverse_ { file =>
        git("add", file.pathAsString)(repo) >>
          gitAlg.commitAll(repo, CommitMsg(s"Add ${file.name}"), signoffCommits = None)
      }

    def createConflict(repo: File): F[Unit] =
      for {
        // work on master
        _ <- fileAlg.writeFile(repo / "file1", "file1, line1")
        _ <- fileAlg.writeFile(repo / "file2", "file2, line1")
        _ <- addFiles(repo, repo / "file1", repo / "file2")
        // work on conflicts-no
        _ <- gitAlg.createBranch(repo, conflictsNo)
        _ <- fileAlg.writeFile(repo / "file3", "file3, line1")
        _ <- git("add", "file3")(repo)
        _ <- gitAlg.commitAll(repo, CommitMsg("Add file3 on conflicts-no"), signoffCommits = None)
        _ <- gitAlg.checkoutBranch(repo, master)
        // work on conflicts-yes
        _ <- gitAlg.createBranch(repo, conflictsYes)
        _ <- fileAlg.writeFile(repo / "file2", "file2, line1\nfile2, line2 on conflicts-yes")
        _ <- git("add", "file2")(repo)
        _ <- gitAlg.commitAll(
          repo,
          CommitMsg("Modify file2 on conflicts-yes"),
          signoffCommits = None
        )
        _ <- gitAlg.checkoutBranch(repo, master)
        // work on master
        _ <- fileAlg.writeFile(repo / "file2", "file2, line1\nfile2, line2 on master")
        _ <- git("add", "file2")(repo)
        _ <- gitAlg.commitAll(repo, CommitMsg("Modify file2 on master"), signoffCommits = None)
      } yield ()

    def createConflictFileRemovedOnMaster(repo: File): F[Unit] =
      for {
        // work on master
        _ <- fileAlg.writeFile(repo / "file1", "file1, line1")
        _ <- fileAlg.writeFile(repo / "file2", "file2, line1")
        _ <- addFiles(repo, repo / "file1", repo / "file2")
        // work on conflicts-yes
        _ <- gitAlg.createBranch(repo, conflictsYes)
        _ <- fileAlg.writeFile(repo / "file2", "file2, line1\nfile2, line2 on conflicts-yes")
        _ <- git("add", "file2")(repo)
        _ <- gitAlg.commitAll(
          repo,
          CommitMsg("Modify file2 on conflicts-yes"),
          signoffCommits = None
        )
        _ <- gitAlg.checkoutBranch(repo, master)
        // work on master
        _ <- git("rm", "file2")(repo)
        _ <- git("add", "-A")(repo)
        _ <- gitAlg.commitAll(repo, CommitMsg("Remove file2 on master"), signoffCommits = None)
      } yield ()
  }

  implicit val ioWorkspaceAlg: WorkspaceAlg[IO] =
    WorkspaceAlg.create[IO](config)

  implicit val ioGitAlg: GenGitAlg[IO, File] =
    new FileGitAlg[IO](config).contramapRepoF(IO.pure)

  val ioAuxGitAlg: AuxGitAlg[IO] =
    new AuxGitAlg[IO]
}
