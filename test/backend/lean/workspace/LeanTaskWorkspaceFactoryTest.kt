package backend.lean.workspace

import support.SimpleTestDataset
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.rag.base.files.model.FileSystemEntry
import backend.lean.evaluation.LeanProcess
import backend.lean.evaluation.LeanProcessResult
import domain.EvaluationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import run.tools.WorkspaceSearchTools
import run.tools.WorkspaceTools

class LeanTaskWorkspaceFactoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path
    private val projectRoot = Path.of("").toAbsolutePath().normalize()
    private val task = SimpleTestDataset.task("proof")
    private fun factory() = LeanTaskWorkspaceFactory(projectRoot, SimpleTestDataset.datasetRoot,
        buildDependencies = { _, _ -> },
        leanProcessFactory = { LeanProcess { _, _ -> LeanProcessResult.Completed(0, "'Benchmark.target' does not depend on any axioms") } })

    @Test
    fun stagesOnlyThePublicClosureAndCreatesIndependentDirectories() = runBlocking {
        val factory = factory()
        val plan = factory.prepare(task)
        val first = plan.create().workspace
        val second = plan.create().workspace
        val firstRoot = first.root
        val secondRoot = second.root
        try {
            assertNotEquals(firstRoot, secondRoot)
            assertTrue(first.visibleFiles().any { it.endsWith("Context.lean") && it.startsWith("dataset/") })
            assertFalse(first.visibleFiles().any { it.contains("private") || it.contains("reference") || it.contains(".lake") })
            for (name in listOf("Unrelated")) {
                val path = "dataset/paper_1/formalisation/$name.lean"
                assertFalse(path in first.visibleFiles(), name)
                assertFalse(Files.exists(firstRoot.resolve(path)), name)
                assertFails(name) { ReadFileTool(WorkspaceTools(first).fileSystem).execute(ReadFileTool.Args("/workspace/$path")) }
            }
            assertContains(first.read("dataset/paper_1/formalisation/Context.lean"), "def base : Nat := 1")
            assertFalse(Files.exists(firstRoot.resolve(".lake/build/lib/lean/dataset")))
            EditFileTool(WorkspaceTools(first).fileSystem).execute(EditFileTool.Args("/workspace/Solution.lean", "__BENCHMARK_PROOF__", "by\n  trivial"))
            WriteFileTool(WorkspaceTools(first).fileSystem).execute(WriteFileTool.Args("/workspace/scratch/helper.lean", "-- private working notes"))
            assertContains(first.read("Solution.lean"), "by\n  trivial")
            assertEquals("-- private working notes", first.read("scratch/helper.lean"))
            assertContains(second.read("Solution.lean"), "__BENCHMARK_PROOF__")
            assertFalse("scratch/helper.lean" in second.visibleFiles())
        } finally {
            first.close()
            second.close()
        }
        assertFalse(Files.exists(firstRoot))
        assertFalse(Files.exists(secondRoot))
        assertTrue(Files.isDirectory(projectRoot.resolve(".lake/packages/mathlib")))
    }

    @Test
    fun rejectsEscapesPrivateFilesAndReadOnlyWritesIncludingSymlinks() = runBlocking {
        factory().prepare(task).create().use { session ->
            val workspace = session.workspace
            val read = ReadFileTool(WorkspaceTools(workspace).fileSystem)
            val write = WriteFileTool(WorkspaceTools(workspace).fileSystem)
            val edit = EditFileTool(WorkspaceTools(workspace).fileSystem)
            val input = workspace.visibleFiles().first { it.endsWith("Context.lean") && it.startsWith("dataset/") }
            val original = workspace.read(input)
            for (path in listOf("/workspace/../outside", "/etc/passwd", "/workspace/.lake/packages/mathlib/Mathlib.lean",
                "/workspace/private/reference.proof", "/workspace-other/Solution.lean", "Solution.lean",
                workspace.root.resolve("Solution.lean").toString())) {
                assertFails(path) { read.execute(ReadFileTool.Args(path)) }
                assertFails(path) { write.execute(WriteFileTool.Args(path, "bad")) }
                assertFails(path) { edit.execute(EditFileTool.Args(path, "", "bad")) }
            }
            assertFails { write.execute(WriteFileTool.Args("/workspace/$input", "bad")) }
            assertFails { edit.execute(EditFileTool.Args("/workspace/$input", "", "bad")) }
            assertEquals(original, workspace.read(input))
            Files.createDirectories(workspace.root.resolve("scratch"))
            Files.createSymbolicLink(workspace.root.resolve("scratch/link.lean"), task.packageDirectory.resolve("Context.lean"))
            assertContains(assertFails { write.execute(WriteFileTool.Args("/workspace/scratch/link.lean", "bad")) }.message.orEmpty(), "symbolic links")
            Files.delete(workspace.root.resolve("scratch/link.lean"))
            Files.delete(workspace.root.resolve("scratch"))
            Files.createSymbolicLink(workspace.root.resolve("scratch"), task.packageDirectory)
            assertContains(assertFails { write.execute(WriteFileTool.Args("/workspace/scratch/new.lean", "bad")) }.message.orEmpty(), "symbolic links")
            assertFalse(Files.exists(task.packageDirectory.resolve("new.lean")))
            Files.delete(workspace.root.resolve("Solution.lean"))
            Files.createSymbolicLink(workspace.root.resolve("Solution.lean"), task.packageDirectory.resolve("Context.lean"))
            assertContains(assertFails { read.execute(ReadFileTool.Args("/workspace/Solution.lean")) }.message.orEmpty(), "symbolic links")
        }
    }

    @Test
    fun builtInListingAndLineRangesExposeOnlyVirtualPublicPaths() = runBlocking {
        factory().prepare(task).create().use { session ->
            val workspace = session.workspace
            Files.writeString(workspace.root.resolve("hidden.lean"), "-- hidden")
            val listing = ListDirectoryTool(WorkspaceTools(workspace).fileSystem)
            fun files(entry: FileSystemEntry): List<String> = when (entry) {
                is FileSystemEntry.File -> listOf(entry.path)
                is FileSystemEntry.Folder -> entry.entries.orEmpty().flatMap(::files)
            }
            val paths = files(listing.execute(ListDirectoryTool.Args("/workspace", depth = 20)).root)
            assertEquals(workspace.visibleFiles().map { "/workspace/$it" }.sorted(), paths.sorted())
            assertFalse(paths.any { it.contains(".lake") || it.contains("hidden") || it.contains(workspace.root.toString()) })
            assertEquals(listOf("/workspace/Solution.lean"),
                files(listing.execute(ListDirectoryTool.Args("/workspace", depth = 20, filter = "Solution.lean")).root))
            assertFails { listing.execute(ListDirectoryTool.Args("/workspace/.lake")) }
            assertFails { ReadFileTool(WorkspaceTools(workspace).fileSystem).execute(ReadFileTool.Args("/workspace/hidden.lean")) }
            val result = ReadFileTool(WorkspaceTools(workspace).fileSystem).execute(ReadFileTool.Args("/workspace/Solution.lean", startLine = 0, endLine = 1))
            assertEquals("/workspace/Solution.lean", result.file.path)
            val excerpt = assertIs<FileSystemEntry.File.Content.Excerpt>(result.file.content)
            assertEquals(workspace.read("Solution.lean").substringBefore('\n') + "\n", excerpt.snippets.single().text)
            assertContains(WorkspaceSearchTools(workspace).searchFiles("theorem target"), "/workspace/Solution.lean:")
            assertEquals("", WorkspaceSearchTools(workspace).searchFiles("-- hidden"))
        }
    }

    @Test
    fun builtInWritesPreserveSizeCountAndScratchRestrictions() = runBlocking {
        factory().prepare(task).create().use { session ->
            val workspace = session.workspace
            val write = WriteFileTool(WorkspaceTools(workspace).fileSystem)
            val original = workspace.read("Solution.lean")
            assertFails { write.execute(WriteFileTool.Args("/workspace/Solution.lean", "x".repeat(256 * 1024 + 1))) }
            assertEquals(original, workspace.read("Solution.lean"))
            assertFails { write.execute(WriteFileTool.Args("/workspace/scratch/helper.txt", "bad")) }
            for (index in 1..31) write.execute(WriteFileTool.Args("/workspace/scratch/helper$index.lean", "-- notes"))
            assertFails { write.execute(WriteFileTool.Args("/workspace/scratch/overflow.lean", "bad")) }
            assertEquals(32, workspace.snapshot().size)
            assertFalse(Files.exists(workspace.root.resolve("scratch/overflow.lean")))
        }
    }

    @Test
    fun evaluatesOnlyTheProofAndProtectsTheTargetAndPrerequisites() = runBlocking<Unit> {
        factory().prepare(task).create().use { session ->
            val workspace = session.workspace
            assertIs<EvaluationResult.Rejected>(session.assess().second)
            EditFileTool(WorkspaceTools(workspace).fileSystem).execute(EditFileTool.Args("/workspace/Solution.lean", "__BENCHMARK_PROOF__", "by\n  trivial"))
            assertEquals("by\n  trivial", session.assess().first)
            assertEquals(EvaluationResult.Accepted, session.assess().second)
            val solution = workspace.read("Solution.lean")
            workspace.write("Solution.lean", solution.replace("theorem target", "theorem forged"))
            assertIs<EvaluationResult.Rejected>(session.assess().second)
            workspace.write("Solution.lean", solution)
            val input = workspace.visibleFiles().first { it.endsWith("Context.lean") && it.startsWith("dataset/") }
            Files.writeString(workspace.root.resolve(input), "-- changed prerequisite")
            assertIs<EvaluationResult.InfrastructureFailure>(session.assess().second)
        }
    }

    @Test
    fun verifiesTheClosureAndTaskImportsBeforeCreatingAWorkspace() {
        val factory = factory()
        val changedIdentity = task.copy(manifest = task.manifest.copy(contextId = domain.ContextId("unrelated")))
        val failure = assertFailsWith<IllegalArgumentException> { factory.prepare(changedIdentity) }
        assertContains(failure.message.orEmpty(), "Context.lean imports")
    }

    @Test
    fun rejectsChangedOrSymlinkedPrerequisitesBeforeBuilding() {
        val root = temporaryDirectory.toRealPath()
        val plan = factory().prepare(task)
        plan.inputs.forEach { input ->
            val relative = when {
                input.path.startsWith("contexts/") || input.path.startsWith("tasks/") -> "dataset/paper_1/${input.path}"
                else -> input.path
            }
            val destination = root.resolve(relative)
            Files.createDirectories(destination.parent)
            Files.writeString(destination, input.content)
        }
        Files.createDirectories(root.resolve(".lake/packages"))
        val copiedTask = task.copy(packageDirectory = root.resolve("dataset/paper_1/tasks/${task.manifest.taskId.value}"))
        val factory = LeanTaskWorkspaceFactory(root, buildDependencies = { _, _ -> error("must not build") })
        val prerequisite = plan.inputs.first { it.path.startsWith("dataset/") }
        val path = root.resolve(prerequisite.path)
        Files.writeString(path, prerequisite.content + "\n-- changed")
        val failure = assertFailsWith<IllegalArgumentException> { factory.prepare(copiedTask) }
        assertContains(failure.message.orEmpty(), "closure hash mismatch")
        Files.delete(path)
        Files.createSymbolicLink(path, SimpleTestDataset.datasetRoot.resolve(prerequisite.path.removePrefix("dataset/")))
        assertFailsWith<IllegalArgumentException> { factory.prepare(copiedTask) }
    }

    @Test
    fun stagesAnExternalDatasetWithItsSelectedLeanEnvironment() {
        val root = temporaryDirectory.toRealPath()
        val environment = Files.createDirectory(root.resolve("environment"))
        val dataset = Files.createDirectory(root.resolve("external-dataset"))
        val original = factory().prepare(task)
        original.inputs.forEach { input ->
            val destination = when {
                input.path.startsWith("contexts/") || input.path.startsWith("tasks/") ->
                    dataset.resolve("paper_1/${input.path}")
                input.path.startsWith("dataset/") -> dataset.resolve(input.path.removePrefix("dataset/"))
                else -> environment.resolve(input.path)
            }
            Files.createDirectories(destination.parent)
            Files.writeString(destination, input.content)
        }
        Files.writeString(environment.resolve("lean-toolchain"), "selected-test-toolchain\n")
        Files.createDirectories(environment.resolve(".lake/packages"))
        val copiedTask = task.copy(packageDirectory = dataset.resolve("paper_1/tasks/${task.manifest.taskId.value}"))
        val externalFactory = LeanTaskWorkspaceFactory(environment, dataset, buildDependencies = { _, _ -> })
        externalFactory.prepare(copiedTask).create().use { session ->
            assertEquals("selected-test-toolchain\n", session.workspace.read("lean-toolchain"))
            assertEquals(environment.resolve(".lake/packages").toRealPath(),
                session.workspace.root.resolve(".lake/packages").toRealPath())
            assertContains(session.workspace.read("Solution.lean"), "__BENCHMARK_PROOF__")
        }
    }

    @Test
    fun removesPartiallyPreparedWorkspaceWhenDependencyBuildFails() {
        var stagedRoot: Path? = null
        val factory = LeanTaskWorkspaceFactory(projectRoot, SimpleTestDataset.datasetRoot, buildDependencies = { root, _ ->
            stagedRoot = root
            error("build failed")
        })
        assertFailsWith<IllegalStateException> { factory.prepare(task).create() }
        assertFalse(Files.exists(requireNotNull(stagedRoot)))
    }
}
