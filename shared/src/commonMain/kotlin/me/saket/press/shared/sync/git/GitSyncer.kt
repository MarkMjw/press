package me.saket.press.shared.sync.git

import com.badoo.reaktive.observable.Observable
import com.badoo.reaktive.observable.combineLatest
import com.badoo.reaktive.subject.behavior.BehaviorSubject
import com.soywiz.klock.DateTime
import kotlinx.coroutines.Runnable
import me.saket.kgit.Git
import me.saket.kgit.GitCommit
import me.saket.kgit.GitConfig
import me.saket.kgit.GitError.AuthFailed
import me.saket.kgit.GitError.NetworkError
import me.saket.kgit.GitError.Unknown
import me.saket.kgit.GitPullResult
import me.saket.kgit.GitRepository
import me.saket.kgit.GitTreeDiff
import me.saket.kgit.GitTreeDiff.Change.Add
import me.saket.kgit.GitTreeDiff.Change.Copy
import me.saket.kgit.GitTreeDiff.Change.Delete
import me.saket.kgit.GitTreeDiff.Change.Modify
import me.saket.kgit.GitTreeDiff.Change.Rename
import me.saket.kgit.PushResult.AlreadyUpToDate
import me.saket.kgit.PushResult.Failure
import me.saket.kgit.PushResult.Success
import me.saket.kgit.UtcTimestamp
import me.saket.kgit.abbreviated
import me.saket.kgit.identify
import me.saket.press.PressDatabase
import me.saket.press.data.shared.Note
import me.saket.press.shared.db.NoteId
import me.saket.press.shared.settings.Setting
import me.saket.press.shared.sync.LastPushedSha1
import me.saket.press.shared.sync.LastSyncedAt
import me.saket.press.shared.sync.SyncState
import me.saket.press.shared.sync.SyncState.IN_FLIGHT
import me.saket.press.shared.sync.SyncState.PENDING
import me.saket.press.shared.sync.SyncState.SYNCED
import me.saket.press.shared.sync.Syncer
import me.saket.press.shared.sync.Syncer.Status.LastOp.Failed
import me.saket.press.shared.sync.Syncer.Status.LastOp.Idle
import me.saket.press.shared.sync.Syncer.Status.LastOp.InFlight
import me.saket.press.shared.sync.git.FileNameRegister.FileSuggestion
import me.saket.press.shared.sync.git.service.GitRepositoryInfo
import me.saket.press.shared.time.Clock

// TODO:
//  Stop ship
//   - broadcast an event when a merge conflict is resolved.
//  Others
//   - commit deleted notes.
//   - show errors in status UI
class GitSyncer(
  git: Git,
  private val config: Setting<GitSyncerConfig>,
  private val database: PressDatabase,
  private val deviceInfo: DeviceInfo,
  private val clock: Clock,
  private val lastSyncedAt: Setting<LastSyncedAt>,
  private val lastPushedSha1: Setting<LastPushedSha1>
) : Syncer() {

  internal val directory = File(deviceInfo.appStorage, "git")
  private val noteQueries get() = database.noteQueries
  private val register = FileNameRegister(directory)
  private val remote: GitRepositoryInfo? get() = config.get()?.remote
  private val loggers = SyncLoggers(PrintLnSyncLogger, FileBasedSyncLogger(directory))
  private val git = {
    val config = config.get()!!
    git.repository(
        path = directory.path,
        sshKey = config.sshKey,
        remoteSshUrl = config.remote.sshUrl,
        userConfig = GitConfig(
            "author" to listOf("name" to config.user.name, "email" to (config.user.email ?: "")),
            "committer" to listOf("name" to "Press", "email" to "press@saket.me"),
            "diff" to listOf("renames" to "true")
        )
    )
  }

  companion object {
    private val lastOp = BehaviorSubject(Idle)
  }

  override fun status(): Observable<Status> {
    return combineLatest(config.listen(), lastOp) { config, op ->
      when (config) {
        null -> Status.Disabled
        else -> Status.Enabled(
            lastOp = op,
            lastSyncedAt = lastSyncedAt.get(),
            syncingWith = config.remote
        )
      }
    }
  }

  override fun sync() {
    if (config.get() == null) return      // Sync is disabled.
    if (lastOp.value == InFlight) return  // Another sync ongoing.

    lastOp.onNext(InFlight)
    loggers.onSyncStart()
    directory.makeDirectory(recursively = true)

    try {
      with(GitScope(git())) {
        resetState()
        val pullResult = pull()
        commitAllChanges(pullResult)
        processCommits(pullResult)
        push(pullResult)
      }
      lastSyncedAt.set(LastSyncedAt(clock.nowUtc()))
      lastOp.onNext(Idle)

    } catch (e: Throwable) {
      val errorMessage = when (Git.identify(e)) {
        NetworkError -> "Network error. Will retry later."
        Unknown -> "Unknown error. Will retry later."
        AuthFailed -> {
          disable()
          "Auth failed. Deploy key was likely revoked. Disabling sync."
        }
      }

      log("$errorMessage ${e.stackTraceToString()}")
      lastOp.onNext(Failed)
      loggers.onSyncComplete()
    }
  }

  private class GitScope(val git: GitRepository)

  override fun disable() {
    config.set(null)
    lastSyncedAt.set(null)
    lastPushedSha1.set(null)
    directory.delete(recursively = true)
    noteQueries.swapSyncStates(old = SyncState.values().toList(), new = PENDING)
  }

  private fun GitScope.resetState() {
    // Commit an announcement that syncing has been setup.
    if (git.headCommit() == null) {
      with(File(directory, ".press/")) {
        makeDirectory(recursively = true)
        File(this, "README.md").write(
            "Press uses files in this directory for storing meta-data of your synced notes. " +
                "They are auto-generated and shouldn't be modified. If you run into any " +
                "issues with syncing of notes, feel free to file a [bug report here]" +
                "(https://github.com/saket/press/issues) and attach [sync logs](sync_log.txt)" +
                " after removing/redacting any private info."
        )
      }

      git.commitAll(
          message = "Setup syncing on '${deviceInfo.deviceName()}'",
          timestamp = UtcTimestamp(clock),
          allowEmpty = true
      )

      // JGit doesn't offer a way to set the initial branch name and it
      // won't allow changing the branch without committing anything either
      // so Press changes it after committing something.
      git.checkout(remote!!.defaultBranch, create = true)
    }

    // Remove all unsynced and dirty changes.
    val lastCleanSha1 = lastPushedSha1.get()?.sha1 ?: git.headCommit()!!.sha1.value
    log("Resetting to sha1: $lastCleanSha1.")
    git.deleteChangesSince(lastCleanSha1)

    check(!git.isStagingAreaDirty()) { "Hard reset didn't work" }
  }

  private data class PullResult(
    val headBefore: GitCommit,
    val headAfter: GitCommit
  )

  private fun GitScope.pull(): PullResult {
    val localHead = git.headCommit()!!  // non-null because of resetState().

    return when (val it = git.pull(rebase = true)) {
      is GitPullResult.Success -> {
        val upstreamHead = git.headCommit()!!
        if (upstreamHead != localHead) {
          log("Pulled upstream. Moved head from $localHead to $upstreamHead.")
          val pullDiff = git.diffBetween(localHead, upstreamHead)
          if (pullDiff.isNotEmpty()) {
            log("\nPulled changes (${pullDiff.size}):")
            log(pullDiff.flattenToString())
          }

        } else {
          log("Nothing to pull.")
        }
        PullResult(headBefore = localHead, headAfter = upstreamHead)
      }
      is GitPullResult.Failure -> {
        throw error("Failed to rebase: $it")
      }
    }
  }

  @Suppress("CascadeIf")
  private fun GitScope.commitAllChanges(pullResult: PullResult) {
    val pendingSyncNotes = noteQueries.notesInState(listOf(PENDING, IN_FLIGHT)).executeAsList()
    if (pendingSyncNotes.isEmpty()) {
      log("Nothing to commit.")
      return
    }

    // Having an intermediate sync state between PENDING and SYNCED
    // is important in case a note gets updated while it is syncing,
    // in which case it'll get marked as PENDING again.
    noteQueries.updateSyncState(
        ids = pendingSyncNotes.map { it.id },
        syncState = IN_FLIGHT
    )

    // When syncing notes for the first time, pick newer notes.
    // If syncing was enabled sometime in the past, the local and the
    // remote repositories may have moved apart a lot.
    val isFirstSync = lastPushedSha1.get() == null
    val conflictResolver = when {
      isFirstSync -> TimeBasedConflictResolver(git, pullResult)
      else -> DuplicateOnConflictResolver(git, pullResult)
    }

    log("Reading unsynced notes (${pendingSyncNotes.size}):")

    for (note in pendingSyncNotes) {
      val suggestion = register.suggestFile(note)
      val notePath = suggestion.suggestedFilePath

      val commitRename = {
        git.commitAll(
            message = "Rename '${suggestion.oldFilePath}' → '$notePath'",
            timestamp = UtcTimestamp(note.updatedAt),
            allowEmpty = false
        )
      }

      log(" • $notePath")
      conflictResolver.resolveAndSave(note, suggestion, commitRename)

      // Staging area may not be dirty if this note had already been processed earlier.
      if (git.isStagingAreaDirty()) {
        git.commitAll(
            message = "Update '$notePath'",
            timestamp = UtcTimestamp(note.updatedAt)
        )
      }
    }
  }

  private abstract class MergeConflictsResolver(git: GitRepository, pullResult: PullResult) {
    protected val pulledPaths = git
        .diffBetween(pullResult.headBefore, pullResult.headAfter)
        .filterNoteChanges()
        .map { it.path }
        .toHashSet()

    abstract fun resolveAndSave(note: Note, suggestion: FileSuggestion, commitRename: () -> Unit?)
  }

  private inner class TimeBasedConflictResolver(
    git: GitRepository,
    pullResult: PullResult
  ) : MergeConflictsResolver(git, pullResult) {

    private val pulledPathTimestamps = git
        .commitsBetween(null, pullResult.headAfter)
        .pathTimestamps(git)

    @Suppress("MapGetWithNotNullAssertionOperator")
    override fun resolveAndSave(note: Note, suggestion: FileSuggestion, commitRename: () -> Unit?) {
      val noteFile = suggestion.suggestedFile
      val notePath = suggestion.suggestedFilePath
      val oldPath = suggestion.oldFilePath

      val isRemoteNewer = { path: String ->
        path in pulledPaths && pulledPathTimestamps[path]!! > note.updatedAt
      }

      if (isRemoteNewer(notePath) || (oldPath != null && isRemoteNewer(oldPath))) {
        log("   picking remote's copy and discarding local")

      } else {
        suggestion.acceptRename?.let {
          it.invoke()
          commitRename()
        }
        noteFile.write(note.content)
        log("   picking local copy and discarding remote")
      }
    }
  }

  private inner class DuplicateOnConflictResolver(
    git: GitRepository,
    pullResult: PullResult
  ) : MergeConflictsResolver(git, pullResult) {

    override fun resolveAndSave(note: Note, suggestion: FileSuggestion, commitRename: () -> Unit?) {
      val noteFile = suggestion.suggestedFile
      val notePath = suggestion.suggestedFilePath
      val oldPath = suggestion.oldFilePath

      // Git makes it easy to handle merge conflicts, but automating it for
      // the user is going to be a challenge. If the same note was modified
      // from different devices, Press duplicates them: note.md & note_2.md.
      // This will also cover non-.md files.
      //
      // Duplicating the local note is fewer work in the current design than
      // remote. If the local note is being edited right now, Press will
      // close and re-open the note.
      if (notePath in pulledPaths) {
        if (noteFile.equalsContent(note.content).not()) {
          // File's content is going to change in a conflicting way. Duplicate the note.
          noteFile.copy(register.findNewNameOnConflict(noteFile)).let {
            it.write(note.content)
            log("   duplicated to '${it.relativePathIn(directory)}' to resolve merge conflict")
          }
        } else {
          log("   skipped (same content)")
        }

      } else if (oldPath in pulledPaths) {
        // Old path was updated on remote, but deleted (on rename) locally. By not
        // accepting the rename, this file will later be processed as a new note.
        noteFile.write(note.content)
        log("   created as a new note to resolve merge conflict (old path = '$oldPath')")

      } else {
        suggestion.acceptRename?.let {
          it.invoke()
          commitRename()
        }
        noteFile.write(note.content)
        log("   created/updated")
      }
    }
  }

  @Suppress("NAME_SHADOWING")
  private fun GitScope.processCommits(pullResult: PullResult) {
    if (pullResult.headBefore == git.headCommit()) {
      log("Nothing to process.")
      return
    }

    val to = git.headCommit()!!
    val from = git.commonAncestor(first = pullResult.headBefore, second = to)

    log("\nProcessing commits from ${from?.sha1?.abbreviated} to ${to.sha1.abbreviated}")

    val commits = git.commitsBetween(from = from, toInclusive = to)
    commits.forEach { log(" • ${it.sha1.abbreviated} - ${it.message.lines().first()}") }

    // DB operations are executed in one go to
    // avoid locking the DB in a transaction for long.
    val dbOperations = mutableListOf<Runnable>()

    val diffs = git.diffBetween(from, to)
    val diffPathTimestamps = commits.pathTimestamps(git)

    log("\nProcessing changes (${diffs.size}):")
    if (diffs.isNotEmpty()) log(diffs.flattenToString())

    for (diff in diffs.filterNoteChanges()) {
      val commitTime = diffPathTimestamps[diff.path]!!

      dbOperations += when (diff) {
        is Copy,
        is Rename,
          // Renaming of note files are ignored. Press
          // generates a name as per the note's heading.
        is Add, is Modify -> {
          val file = File(directory, diff.path)
          val content = file.read()

          val oldPath = if (diff is Rename) diff.fromPath else null
          val record = register.recordFor(diff.path, oldPath = oldPath)
              ?: register.createNewRecordFor(file, id = NoteId.generate())

          val noteId = record.noteId
          val isArchived = record.noteFolder == "archived"
          val isNewNote = !noteQueries.exists(noteId).executeAsOne()

          if (isNewNote) {
            log("Creating new note $noteId for (${diff.path}), isArchived? $isArchived")
          } else {
            log("Updating $noteId (${diff.path}), isArchived? $isArchived")
          }

          if (isNewNote) {
            Runnable {
              noteQueries.insert(
                  id = noteId,
                  content = content,
                  createdAt = commitTime,
                  updatedAt = commitTime
              )
              noteQueries.setArchived(
                  id = noteId,
                  isArchived = isArchived,
                  updatedAt = commitTime
              )
              noteQueries.updateSyncState(
                  ids = listOf(noteId),
                  syncState = IN_FLIGHT
              )
            }
          } else {
            Runnable {
              noteQueries.updateContent(
                  id = noteId,
                  content = content,
                  updatedAt = commitTime
              )
              noteQueries.setArchived(
                  id = noteId,
                  isArchived = isArchived,
                  updatedAt = commitTime
              )
              noteQueries.updateSyncState(
                  ids = listOf(noteId),
                  syncState = IN_FLIGHT
              )
            }
          }
        }
        is Delete -> {
          val noteId = register.noteIdFor(diff.path)
          if (noteId == null) {
            // Commit has already been processed earlier.
            Runnable {}
          } else {
            log("Permanently deleting $noteId (${diff.path})")
            Runnable {
              noteQueries.markAsPendingDeletion(noteId)
              noteQueries.updateSyncState(ids = listOf(noteId), syncState = IN_FLIGHT)
              noteQueries.deleteNote(noteId)
            }
          }
        }
      }
    }

    if (dbOperations.isNotEmpty()) {
      noteQueries.transaction {
        dbOperations.forEach { it.run() }
      }
    }

    val savedNotes = noteQueries.allNotes().executeAsList()
    register.pruneStaleRecords(savedNotes)
    if (git.isStagingAreaDirty()) {
      git.commitAll(
          message = "Update file name records",
          timestamp = UtcTimestamp(clock)
      )
    }
  }

  private fun GitScope.push(pullResult: PullResult) {
    check(git.currentBranch().name == remote!!.defaultBranch) { "Not on the default branch" }
    check(!git.isStagingAreaDirty()) { "Expected staging area to be clean before pushing" }

    val head = git.headCommit()!!
    return if (pullResult.headAfter == head) {
      noteQueries.swapSyncStates(old = listOf(IN_FLIGHT), new = SYNCED)
      lastPushedSha1.set(LastPushedSha1(head))
      log("\nNothing to push.")

    } else {
      log("\nPushing changes")
      loggers.onSyncComplete()
      git.commitAll(
          message = "Update sync logs",
          timestamp = UtcTimestamp(clock)
      )

      when (val result = git.push()) {
        is Success, is AlreadyUpToDate -> {
          noteQueries.swapSyncStates(old = listOf(IN_FLIGHT), new = SYNCED)
          lastPushedSha1.set(LastPushedSha1(head))
        }
        // Merge conflicts can only be avoided if we're always on the latest version of upstream.
        // If push fails, abort this sync. Any unsynced changes will be reset on next sync.
        is Failure -> error("Couldn't push: $result")
      }
    }
  }

  private fun log(message: String) = loggers.log(message)

  private fun List<GitCommit>.pathTimestamps(git: GitRepository): Map<String, DateTime> {
    // Press stores updated-at timestamp of notes in each commit.
    return flatMap { commit ->
      git.changesIn(commit)
          .filterNoteChanges()
          .map { it.path to commit.dateTime }
    }.toMap()
  }
}

@Suppress("FunctionName")
fun UtcTimestamp(time: DateTime): UtcTimestamp {
  return UtcTimestamp(time.unixMillisLong)
}

@Suppress("FunctionName")
fun UtcTimestamp(clock: Clock): UtcTimestamp {
  return UtcTimestamp(clock.nowUtc())
}

private val GitCommit.dateTime: DateTime
  get() = DateTime.fromUnix(utcTimestamp.millis)

private fun GitTreeDiff.flattenToString(): String {
  return joinToString(prefix = " • ", separator = "\n • ", postfix = "\n")
}

private fun List<GitTreeDiff.Change>.filterNoteChanges() = filter { diff ->
  val path = diff.path
  when {
    !path.endsWith(".md") -> false                                        // Not a markdown note.
    path.startsWith(".press/") -> false                                   // Meta-files, ignore.
    path.contains("/") -> {
      when {
        path.startsWith("archived/") && !path.hasMultipleOf('/') -> true  // Archived note.
        else -> error("Folders aren't supported yet: '$path'")
      }
    }
    else -> true
  }
}

@Suppress("FunctionName")
fun LastPushedSha1(commit: GitCommit) = LastPushedSha1(commit.sha1.value)
