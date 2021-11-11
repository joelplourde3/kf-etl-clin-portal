package bio.ferlab.fhir.etl

import bio.ferlab.fhir.etl.model.FhirBulkResource
import bio.ferlab.fhir.etl.task.FhavroBulkExporter
import cats.implicits.catsSyntaxValidatedId
import org.slf4j.{Logger, LoggerFactory}

object FhavroBulkExport extends App {

  val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  val processedResources = scala.collection.mutable.Set[String]()

  withSystemExit {
    withLog {
      withConfiguration { configuration =>
        val bulkExporter = new FhavroBulkExporter(configuration)

        LOGGER.info(s"Requesting Bulk Export for resource(s).")
        val resources = configuration.fhirConfig.resources
          .map(resource => FhirBulkResource(resource.`type`, resource.schema, resource.tag, bulkExporter.requestBulkExportFor(resource)))

        LOGGER.info(s"Polling Bulk Export for resource(s).")
        while (!checkIfAllResourcesAreProcessed(resources)) {
          resources
            .filter(resource => !checkIfResourceIsProcessed(resource))
            .foreach(resource => {
              LOGGER.info(s"Polling Status for ${resource.name}")
              bulkExporter.checkPollingStatusOnce(resource.url) match {
                case Some(files) =>
                  bulkExporter.uploadFiles(resource, configuration.fhirConfig.schemaPath, files).validNel[String]

                  LOGGER.info(s"Marking ${resource.name} as processed.")
                  markResourceAsProcessed(resource)
                case None =>
                  Thread.sleep(configuration.pollingConfig.interval._1)
              }
            })
        }
        processedResources.valid[String].valid
      }
    }
  }

  private def checkIfAllResourcesAreProcessed(resources: List[FhirBulkResource]): Boolean = {
    LOGGER.info(s"Processed resource(s): ${processedResources.size}/${resources.length}")
    resources.length == processedResources.size
  }

  private def checkIfResourceIsProcessed(resource: FhirBulkResource): Boolean = {
    processedResources.contains(getUniqueKey(resource))
  }

  private def markResourceAsProcessed(resource: FhirBulkResource): Unit = {
    val uniqueKey = getUniqueKey(resource)
    LOGGER.info(s"Marking $uniqueKey as processed.")
    processedResources.add(uniqueKey)
  }

  private def getUniqueKey(resource: FhirBulkResource): String = {
    s"${resource.name}/${resource.schema}/${resource.tag}"
  }
}
