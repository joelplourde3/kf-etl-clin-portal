package bio.ferlab.fhir.etl

import bio.ferlab.fhir.etl.task.FhavroExporter
import cats.implicits.catsSyntaxValidatedId
import org.slf4j.{Logger, LoggerFactory}

object FhavroExport extends App {
  val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  withSystemExit {
    withLog {
      withConfiguration { configuration =>
        val fhavroExporter = new FhavroExporter(configuration)

        configuration.fhirConfig.resources.foreach(fhirRequest => {

          val resources = fhavroExporter.requestExportFor(fhirRequest)

          fhavroExporter.uploadFiles(fhirRequest, configuration.fhirConfig.schemaPath, resources)
        }).valid[String].valid
      }
    }
  }
}
