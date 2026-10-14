package backend.lean.workspace

import kotlin.test.*

class LeanContextReaderTest {
    @Test fun importClosureDiscoveryIgnoresNestedCommentsAndStrings() {
        val source = """
            /- import dataset.paper_1.formalisation.Answer
               /- nested -/
            -/
              import dataset.paper_37.formalisation.First dataset.paper_37.formalisation.Second -- comment
            def note := "import dataset.paper_1.formalisation.Answer"
            def quote : Char := '"'
            def slash : Char := '\\'
            public import dataset.paper_37.formalisation.Third
        """.trimIndent()
        assertEquals(listOf("dataset.paper_37.formalisation.First", "dataset.paper_37.formalisation.Second",
            "dataset.paper_37.formalisation.Third"), LeanContextReader.sourceImports(source))
        assertFails { LeanContextReader.sourceImports("/- import Hidden") }
    }
}
