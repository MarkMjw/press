package me.saket.press.shared.home

import com.badoo.reaktive.completable.andThen
import com.badoo.reaktive.completable.completableFromFunction
import com.badoo.reaktive.observable.Observable
import com.badoo.reaktive.observable.ObservableWrapper
import com.badoo.reaktive.observable.combineLatest
import com.badoo.reaktive.observable.flatMapCompletable
import com.badoo.reaktive.observable.map
import com.badoo.reaktive.observable.mapIterable
import com.badoo.reaktive.observable.merge
import com.badoo.reaktive.observable.observableOf
import com.badoo.reaktive.observable.observableOfEmpty
import com.badoo.reaktive.observable.observeOn
import com.badoo.reaktive.observable.ofType
import com.badoo.reaktive.observable.publish
import com.badoo.reaktive.observable.switchMap
import com.badoo.reaktive.observable.wrap
import me.saket.press.PressDatabase
import me.saket.press.shared.db.NoteId
import me.saket.press.shared.editor.EditorOpenMode.NewNote
import me.saket.press.shared.editor.EditorPresenter
import me.saket.press.shared.editor.EditorPresenter.Companion.NEW_NOTE_PLACEHOLDER
import me.saket.press.shared.editor.EditorScreenKey
import me.saket.press.shared.editor.PreSavedNoteId
import me.saket.press.shared.home.HomeEvent.NewNoteClicked
import me.saket.press.shared.home.HomeEvent.SearchTextChanged
import me.saket.press.shared.keyboard.KeyboardShortcuts
import me.saket.press.shared.keyboard.KeyboardShortcuts.Companion.newNote
import me.saket.press.shared.localization.Strings
import me.saket.press.shared.note.HeadingAndBody
import me.saket.press.shared.rx.Schedulers
import me.saket.press.shared.rx.asObservable
import me.saket.press.shared.rx.mapToList
import me.saket.press.shared.rx.mapToOneOrNull
import me.saket.press.shared.rx.mergeWith
import me.saket.press.shared.time.Clock
import me.saket.press.shared.ui.Navigator
import me.saket.press.shared.ui.Presenter

class HomePresenter(
  private val args: Args,
  private val database: PressDatabase,
  private val schedulers: Schedulers,
  private val strings: Strings,
  private val clock: Clock,
  private val keyboardShortcuts: KeyboardShortcuts
) : Presenter<HomeEvent, HomeUiModel>() {
  private val noteQueries get() = database.noteQueries

  override fun defaultUiModel() =
    HomeUiModel(rows = emptyList(), title = "")

  override fun models(): ObservableWrapper<HomeUiModel> {
    return viewEvents().publish { events ->
      merge(populateNotes(events), openNewNoteScreen())
    }.wrap()
  }

  private fun openNewNoteScreen(): Observable<HomeUiModel> {
    return viewEvents().ofType<NewNoteClicked>()
      .mergeWith(keyboardShortcuts.listen(newNote))
      .observeOn(schedulers.io)
      .flatMapCompletable {
        completableFromFunction {
          // Inserting a new note before-hand makes it possible for
          // two-pane layouts to immediately show the new note in the list.
          val newNoteId = NoteId.generate()
          noteQueries.insert(
            id = newNoteId,
            folderId = args.screenKey.folder,
            content = NEW_NOTE_PLACEHOLDER,
            createdAt = clock.nowUtc(),
            updatedAt = clock.nowUtc()
          )

          args.navigator.lfg(
            EditorScreenKey(NewNote(PreSavedNoteId(newNoteId)))
          )
        }
      }
      .andThen(observableOfEmpty())
  }

  private fun populateNotes(events: Observable<HomeEvent>): Observable<HomeUiModel> {
    val folderId = args.screenKey.folder

    val screenTitle = if (folderId != null) {
      database.folderQueries.folder(folderId)
        .asObservable(schedulers.io)
        .mapToOneOrNull()
        .map { folder -> folder?.name ?: strings.common.app_name }
    } else {
      observableOf(strings.common.app_name)
    }

    return events.ofType<SearchTextChanged>().publish { searchTexts ->
      val folderModels = searchTexts.switchMap { (searchText) ->
        if (searchText.isBlank()) {
          database.folderQueries.nonEmptyFoldersUnder(folderId)
            .asObservable(schedulers.io)
            .mapToList()
            .mapIterable { folder ->
              HomeUiModel.Folder( // todo: avoid double mapping from cursors.
                id = folder.id,
                title = folder.name
              )
            }
        } else {
          observableOf(emptyList())
        }
      }

      val noteModels = searchTexts.switchMap { (searchText) ->
        // todo: move filtering of empty notes back to kotlin to clean up this mess.
        val query = when (args.screenKey.folder) {
          null -> when {
            args.includeBlankNotes -> noteQueries.visibleNotes(searchText)
            else -> noteQueries.visibleNonEmptyNotes(searchText)
          }
          else -> when {
            args.includeBlankNotes -> noteQueries.visibleNotesInFolder(folderId, searchText)
            else -> noteQueries.visibleNonEmptyNotesInFolder(folderId, searchText)
          }
        }
        query.asObservable(schedulers.io)
          .mapToList()
          .mapIterable { note ->
            val (heading, body) = HeadingAndBody.parse(note.content)
            HomeUiModel.Note(
              id = note.id,
              title = heading,
              body = body
            )
          }
      }

      return@publish combineLatest(screenTitle, folderModels, noteModels) { title, folders, notes ->
        HomeUiModel(
          title = title,
          rows = folders + notes
        )
      }
    }
  }

  fun interface Factory {
    fun create(args: Args): HomePresenter
  }

  data class Args(
    val screenKey: HomeScreenKey,

    /**
     * [EditorPresenter] creates a new note as soon as the editor screen is opened,
     * causing the new note to show up on home while the new note screen is opening.
     * This flag ensures that doesn't happen by ignoring empty notes when its set to
     * false. In the future, this can be set to true for multi-pane layouts on desktop
     * or tablets.
     *
     * Should be kept in sync with [EditorPresenter.Args.deleteBlankNewNoteOnExit].
     */
    val includeBlankNotes: Boolean,

    val navigator: Navigator
  )
}
