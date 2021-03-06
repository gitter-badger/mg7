package ohnosequences.metagenomica.loquats.assignment

import  ohnosequences.metagenomica._

import ohnosequences.metagenomica.bio4j._, taxonomyTree._, titanTaxonomyTree._

import ohnosequences.loquat.dataProcessing._
import ohnosequences.statika.bundles._
import ohnosequences.statika.instructions._
import ohnosequencesBundles.statika.Blast
import ohnosequences.blast._, api._, data._
import ohnosequences.cosas._, typeSets._, types._, properties._
import ohnosequences.datasets._, dataSets._, fileLocations._, illumina._, reads._
import java.io.{ BufferedWriter, FileWriter, File }
import ohnosequences.fastarious._, fasta._, fastq._
import ohnosequences.blast._, api._, data._, outputFields._

import scala.util.Try


case object dataProcessing {

  // TODO: move it to the config
  case object CSVDataType extends AnyDataType { val label = "fastq" }
  case object lcaCSV extends Data(CSVDataType, "lca.csv")
  case object bbhCSV extends Data(CSVDataType, "bbh.csv")

  // type BlastOutput = loquats.blast.blastTest.blastOutput.type
  val  blastOutput = loquats.blast.blastTest.blastOutput

  type BlastRecord = loquats.blast.blastDataProcessing.outRec.type
  val blastRecord: BlastRecord = loquats.blast.blastDataProcessing.outRec

  val headers: Seq[String] = blastRecord.properties.mapToList(typeLabel)

  // TODO: move it somewhere up for global use
  type ID = String

  type GI = ID
  type TaxID = ID
  type ReadID = ID
  type NodeID = ID

  type LCA = Option[NodeID]
  type BBH = Option[NodeID]

  // TODO: move it somewhere up for global use
  def parseInt(str: String): Option[Int] = Try(str.toInt).toOption

  // this method looks up particular column by its header
  def column(row: Seq[String], header: AnyOutputField): Option[String] =
    headers.zip(row).toMap.get(header.label)

  // FIXME: this thing should be used globally (ideally it should be in datasets)
  implicit def genericParser[D <: AnyData](implicit d: D): DenotationParser[D, FileDataLocation, File] =
    new DenotationParser(d, d.label)({ f: File => Some(FileDataLocation(f)) })


  case object assignmentDataProcessing extends DataProcessingBundle(
    bundles.bio4jTaxonomy,
    bundles.filteredGIs
  )(input = blastOutput :^: DNil,
    output = lcaCSV :^: bbhCSV :^: DNil
  ) {

    def instructions: AnyInstructions = say("Let's see who is who!")

    def processData(
      dataMappingId: String,
      context: Context
    ): Instructions[OutputFiles] = {

      import com.github.tototoshi.csv._

      // Reading TSV file with mapping gis-taxIds
      val gisReader: CSVReader = CSVReader.open( bundles.filteredGIs.location )(new TSVFormat {})
      val gisMap: Map[GI, TaxID] = gisReader.iterator.map { row =>
        row(0) -> row(1)
      }.toMap
      gisReader.close

      val blastReader: CSVReader = CSVReader.open( context.file(blastOutput).javaFile )

      val assignments: Map[ReadID, (LCA, BBH)] = blastReader.iterator.toStream
        .groupBy { row =>
        // grouping rows by the read id
        headers.zip(row).toMap.get(qseqid.label)
      } flatMap {
        case (None, _) => None
        case (Some(readId), hits) => {

          val bbh: BBH =
            // this shouldn't happen, but let's be careful
            if (hits.isEmpty) None
            else {
              // best blast score is just a maximum in the `bitscore` column
              val maxRow: Seq[String] = hits.maxBy { row: Seq[String] =>
                column(row, bitscore).flatMap(parseInt).getOrElse(0)
              }
              column(maxRow, sgi).flatMap(gisMap.get)
            }

          // for each hit row we take the column with GI and lookup its TaxID
          val taxIds: List[TaxID] = hits.toList.flatMap(column(_, sgi)).flatMap(gisMap.get)
          // then we generate Titan taxon nodes
          val nodes: List[TitanTaxonNode] = titanTaxonNodes(bundles.bio4jTaxonomy.graph, taxIds)
          // and return the taxon node ID corresponding to the read
          val lca: LCA = solution(nodes).node.map(_.id)

          Some( (readId, (lca, bbh)) )
        }
      }

      blastReader.close

      // Now we will write these two types of result to two separate files
      val lcaFile = context / "lca.csv"
      val bbhFile = context / "bbh.csv"

      val lcaWriter = CSVWriter.open(lcaFile.javaFile , append = true)
      val bbhWriter = CSVWriter.open(bbhFile.javaFile , append = true)

      assignments foreach { case (readId, (lca, bbh)) =>
        lca foreach { nodeId => lcaWriter.writeRow(List(readId, nodeId)) }
        bbh foreach { nodeId => bbhWriter.writeRow(List(readId, nodeId)) }
      }

      lcaWriter.close
      bbhWriter.close

      success(
        s"Results are written to [${lcaFile.path}] and [${bbhFile.path}]",
        lcaCSV.inFile(lcaFile) :~:
        bbhCSV.inFile(bbhFile) :~:
        ∅
      )
    }
  }

}
