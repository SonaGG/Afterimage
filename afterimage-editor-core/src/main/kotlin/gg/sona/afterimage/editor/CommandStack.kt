package gg.sona.afterimage.editor

import gg.sona.afterimage.core.event.Listeners
import java.util.*

class CommandStack(private val project: EditorProject, private val limit: Int = 500) {
    private val undoStack = ArrayDeque<EditorCommand>()
    private val redoStack = ArrayDeque<EditorCommand>()
    val listeners = Listeners<CommandStackListener>()

    var version: Long = 0L
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val undoLabel: String? get() = undoStack.peekLast()?.label

    val redoLabel: String? get() = redoStack.peekLast()?.label

    val history: List<EditorCommand> get() = undoStack.toList()

    fun execute(command: EditorCommand) {
        command.apply(project)
        project.dirty = true
        redoStack.clear()
        val previous = undoStack.peekLast()
        val merged = previous?.mergeWith(command)
        if (merged != null) {
            undoStack.pollLast()
            undoStack.addLast(merged)
        } else {
            undoStack.addLast(command)
            while (undoStack.size > limit) undoStack.pollFirst()
        }
        version++
        listeners.dispatch { it.onHistoryChanged(this) }
    }

    fun undo(): Boolean {
        val command = undoStack.pollLast() ?: return false
        command.revert(project)
        redoStack.addLast(command)
        project.dirty = true
        version++
        listeners.dispatch { it.onHistoryChanged(this) }
        return true
    }

    fun redo(): Boolean {
        val command = redoStack.pollLast() ?: return false
        command.apply(project)
        undoStack.addLast(command)
        project.dirty = true
        version++
        listeners.dispatch { it.onHistoryChanged(this) }
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        version++
        listeners.dispatch { it.onHistoryChanged(this) }
    }
}
