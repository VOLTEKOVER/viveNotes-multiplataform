package com.vivenotes.typst

/** Result of compiling a diagram: SVG bytes ready for the attachment store, or a readable error. */
sealed interface TypstRenderResult {
    data class Success(val svg: String) : TypstRenderResult
    data class Failure(val message: String) : TypstRenderResult
}

/** Platform renderer injected into the UI; JVM compiles locally with the bundled Typst engine. */
fun interface TypstRenderer {
    suspend fun render(diagram: TypstDiagramSource): TypstRenderResult
}

/** A renderer that is unavailable on this target; the panel explains why instead of crashing. */
object UnsupportedTypstRenderer : TypstRenderer {
    override suspend fun render(diagram: TypstDiagramSource): TypstRenderResult =
        TypstRenderResult.Failure("Typst rendering is not available on this platform.")
}
