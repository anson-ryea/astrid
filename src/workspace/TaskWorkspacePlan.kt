package workspace

/** Creates a fresh sample environment from previously validated inputs. */
fun interface TaskWorkspacePlan {
    fun create(): TaskWorkspaceSession
}
