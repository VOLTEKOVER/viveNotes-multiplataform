package com.vivenotes.typst

import io.github.phfneves.typst.CompileRequest
import io.github.phfneves.typst.CompileResult
import io.github.phfneves.typst.Output
import io.github.phfneves.typst.OutputFormat
import io.github.phfneves.typst.PackageResolver
import io.github.phfneves.typst.PackageSpec
import io.github.phfneves.typst.Typst
import io.github.phfneves.typst.TypstConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Compiles Typst diagrams offline. CeTZ and its formatter dependency are bundled as JVM resources;
 * no network or external Typst binary is used. Compilation is serialized because the native engine
 * is not safe to share across threads.
 */
class JvmTypstRenderer : TypstRenderer {
    private val lock = Mutex()

    override suspend fun render(diagram: TypstDiagramSource): TypstRenderResult = withContext(Dispatchers.Default) {
        lock.withLock {
            val document = TypstDiagramSourceCodec.renderSource(diagram.source, diagram.colors)
            val resolver = PackageResolver { spec: PackageSpec ->
                javaClass.getResourceAsStream(packagePath(spec))?.use { it.readBytes() }
            }
            runCatching {
                Typst.create(TypstConfig(packageResolver = resolver)).use { engine ->
                    when (val result = engine.compile(CompileRequest.of(document, outputs = listOf(OutputFormat.Svg(pretty = true))))) {
                        is CompileResult.Success -> {
                            val svg = (result.outputs.single() as Output.Svg).pages.single()
                            TypstRenderResult.Success(TypstDiagramSourceCodec.embed(svg, diagram))
                        }
                        is CompileResult.Failure -> {
                            val detail = result.errors.joinToString("\n") { it.toString() }
                            TypstRenderResult.Failure(detail.ifBlank { "Typst could not compile the drawing." })
                        }
                    }
                }
            }.getOrElse { TypstRenderResult.Failure(it.message ?: "Typst compilation failed.") }
        }
    }

    private fun packagePath(spec: PackageSpec) =
        "/typst-packages/${spec.namespace}/${spec.name}-${spec.version}.tar.gz"
}
