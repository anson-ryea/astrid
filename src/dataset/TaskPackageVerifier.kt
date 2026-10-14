package dataset

import domain.BenchmarkTask
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/** Verifies the structure, path safety, and declared hashes of a task package. */
class TaskPackageVerifier(
    private val hashCalculator: HashCalculator = HashCalculator(),
) {
    /** Verifies [task], failing if its package does not match its manifest. */
    fun verify(task: BenchmarkTask) {
        val packageDirectory = task.packageDirectory

        check(packageDirectory.isDirectory()) {
            "task package is not a directory: $packageDirectory"
        }
        check(!packageDirectory.isSymbolicLink()) {
            "task package must not be a symbolic link: $packageDirectory"
        }

        val manifestPath = packageDirectory.resolve("manifest.json")

        check(manifestPath.isRegularFile()) {
            "task package is missing manifest.json: $packageDirectory"
        }
        check(!manifestPath.isSymbolicLink()) {
            "task manifest must not be a symbolic link: $manifestPath"
        }

        val realPackageDirectory = packageDirectory.toRealPath()

        task.manifest.artefacts.forEach { artefact ->
            val artefactPath = task.pathFor(artefact)

            check(!artefactPath.isSymbolicLink()) {
                "task artefact must not be a symbolic link: $artefactPath"
            }
            check(artefactPath.isRegularFile()) {
                "task artefact is missing or is not a regular file: " +
                        artefactPath
            }
            check(artefactPath.toRealPath().startsWith(realPackageDirectory)) {
                "task artefact escapes its package directory: $artefactPath"
            }

            val actualDigest = hashCalculator.sha256(artefactPath)

            check(actualDigest == artefact.sha256Digest) {
                "task artefact hash mismatch: $artefactPath; " +
                        "expected ${artefact.sha256Digest}, " +
                        "found $actualDigest"
            }
        }
    }
}
