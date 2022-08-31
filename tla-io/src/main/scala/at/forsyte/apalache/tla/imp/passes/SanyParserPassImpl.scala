package at.forsyte.apalache.tla.imp.passes

import at.forsyte.apalache.infra.ExitCodes
import at.forsyte.apalache.infra.passes.Pass.PassResult
import at.forsyte.apalache.io.annotations.store._
import at.forsyte.apalache.io.json.impl.{DefaultTagReader, UJsonRep, UJsonToTla}
import at.forsyte.apalache.tla.imp.src.SourceStore
import at.forsyte.apalache.tla.lir.{CyclicDependencyError, TlaModule}
import at.forsyte.apalache.tla.lir.transformations.standard.DeclarationSorter
import at.forsyte.apalache.io.lir.TlaWriterFactory
import at.forsyte.apalache.tla.imp.{utils, SanyImporter, SanyImporterException}
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging

import java.io.File
import at.forsyte.apalache.infra.passes.options.SourceOption
import scala.io.Source
import at.forsyte.apalache.infra.passes.options.OptionGroup

/**
 * Parsing TLA+ code with SANY.
 *
 * @author
 *   Igor Konnov
 */
class SanyParserPassImpl @Inject() (
    val options: OptionGroup.HasIO,
    val sourceStore: SourceStore,
    val annotationStore: AnnotationStore,
    val writerFactory: TlaWriterFactory)
    extends SanyParserPass with LazyLogging {

  override def name: String = "SanyParser"

  private def loadFromJsonSource(source: SourceOption): PassResult = {
    import SourceOption._
    val readable: ujson.Readable = source match {
      case FileSource(f, Format.Json)      => f
      case StringSource(s, _, Format.Json) => s
      case _ => throw new IllegalArgumentException("loadFromJsonSource called with non Json SourceOption")
    }

    try {
      val moduleJson = UJsonRep(ujson.read(readable))
      val modules = new UJsonToTla(Some(sourceStore))(DefaultTagReader).fromRoot(moduleJson)
      modules match {
        case rMod +: Nil => Right(rMod)
        case _ => {
          logger.error(s"  > Error parsing file ${source}")
          Left(ExitCodes.ERROR_SPEC_PARSE)
        }
      }
    } catch {
      case e: Exception =>
        logger.error(s"  > Error parsing file ${source}")
        logger.error("  > " + e.getMessage)
        Left(ExitCodes.ERROR_SPEC_PARSE)
    }
  }

  private def loadFromTlaFile(file: File): PassResult = {
    val (rootName, modules) =
      new SanyImporter(sourceStore, annotationStore)
        .loadFromFile(file)
    Right(modules.get(rootName).get)
  }

  private def loadFromTlaString(content: String, aux: Seq[String]): PassResult = {
    val (rootName, modules) =
      new SanyImporter(sourceStore, annotationStore)
        .loadFromSource(Source.fromString(content), aux.map(Source.fromString(_)))
    Right(modules.get(rootName).get)
  }

  private def saveLoadedModule(module: TlaModule): Either[ExitCodes.TExitCode, Unit] = {
    // save the output
    writeOut(writerFactory, module)
    // write parser output to specified destination, if requested
    utils.writeToOutput(module, options, writerFactory, logger, sourceStore)
    Right(())
  }

  protected def sortDeclarations(module: TlaModule): PassResult = {
    try {
      Right(DeclarationSorter.instance(module))
    } catch {
      case e: CyclicDependencyError =>
        // re-throw the error for the nice error message
        throw new SanyImporterException(e.getMessage)
    }
  }

  override def execute(module: TlaModule): PassResult = {
    import SourceOption._
    val src = options.input.source
    for {
      rootModule <- src.format match {
        case Format.Json => loadFromJsonSource(src)
        case Format.Tla =>
          src match {
            case StringSource(content, aux, _) => loadFromTlaString(content, aux)
            case FileSource(file, _)           => loadFromTlaFile(file)
          }
      }
      sortedModule <- sortDeclarations(rootModule)
      _ <- saveLoadedModule(sortedModule)
    } yield sortedModule
  }

  override def dependencies = Set()

  override def transformations = Set()
}
