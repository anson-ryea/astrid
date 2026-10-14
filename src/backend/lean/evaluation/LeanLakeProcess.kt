package backend.lean.evaluation

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Runs Lean through the project's Lake environment. */
class LeanLakeProcess(
    private val projectRoot: Path,
    private val inheritLeanPath: Boolean = true,
): LeanProcess {
    init {
        require(projectRoot.isAbsolute && projectRoot == projectRoot.normalize()) {
            "project root must be absolute and normalised"
        }
    }

    override fun run(source: String, timeout: Duration): LeanProcessResult {
        require(timeout > 0.seconds) {
            "Lean timeout must be positive"
        }

        val temporary = try {
            Files.createTempDirectory("astrid-lean-")
        } catch (failure: IOException) {
            return LeanProcessResult.InfrastructureFailure(
                "could not create temporary Lean directory: ${failure.message}",
            )
        }

        val candidate = temporary.resolve("Candidate.lean")
        val output = temporary.resolve("Output.txt")
        var process: Process? = null

        val result: LeanProcessResult = try {
            Files.writeString(candidate, source)

            val builder = ProcessBuilder(
                "lake", "env", "lean", candidate.toString(),
            )
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
            if (!inheritLeanPath) {
                builder.environment().remove("LEAN_PATH")
                builder.environment().remove("LEAN_SRC_PATH")
            }
            val running = builder.start()

            process = running

            if (running.waitFor(timeout.toJavaDuration())) {
                LeanProcessResult.Completed(
                    exitCode = running.exitValue(),
                    output = Files.readString(output),
                )
            } else {
                running.destroyForcibly()
                running.waitFor(5.seconds.toJavaDuration())
                LeanProcessResult.TimedOut
            }
        } catch (_: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            LeanProcessResult.InfrastructureFailure("Lean execution was interrupted")
        } catch (failure: IOException) {
            LeanProcessResult.InfrastructureFailure(
                "could not run Lean: ${failure.message}",
            )
        } catch (failure: SecurityException) {
            LeanProcessResult.InfrastructureFailure(
                "could not run Lean: ${failure.message}",
            )
        }

        val cleanupFailure = try {
            Files.deleteIfExists(output)
            Files.deleteIfExists(candidate)
            Files.deleteIfExists(temporary)
            null
        } catch (failure: IOException) {
            failure
        }

        return if (cleanupFailure == null) {
            result
        } else {
            LeanProcessResult.InfrastructureFailure(
                "could not remove temporary Lean files: ${cleanupFailure.message}",
            )
        }
    }
}
