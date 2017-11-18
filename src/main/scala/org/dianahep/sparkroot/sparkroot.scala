package org.dianahep

// spark related
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.DataFrameReader
import org.apache.spark.sql.Row
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.sources.PrunedFilteredScan
import org.apache.spark.sql.sources.RelationProvider
import org.apache.spark.sql.execution.FileRelation
import org.apache.spark.sql.types._

// hadoop hdfs 
import org.apache.hadoop.fs.{Path, FileSystem, PathFilter}

// sparkroot or root4j
import org.dianahep.root4j.core.RootInput
import org.dianahep.root4j._
import org.dianahep.root4j.interfaces._
import sparkroot.core._

// logging
import org.apache.log4j.{Level, LogManager, PropertyConfigurator}

package object sparkroot {
  @transient lazy val logger = LogManager.getLogger("SparkRoot")

  sealed trait NoTTreeThrowable {
    self: Throwable =>
    val optTreeName: Option[String]
  }

  case class NoTTreeException(
      override val optTreeName: Option[String] = None) 
      extends Exception(optTreeName match {
        case Some(treeName) => s"No TTree ${treeName} found"
        case None => s"No TTree found"
      }) with NoTTreeThrowable;

  /**
   * An impolicit DataFrame Reader
   */
  implicit class RootDataFrameReader(reader: DataFrameReader) {
    def root(paths: String*) = reader.format("org.dianahep.sparkroot").load(paths: _*)
    def root(path: String) = reader.format("org.dianahep.sparkroot").load(path)
  }

  /**
   *  ROOT TTree Iterator
   *  - iterates over the tree
   *  - pump the data from TTree into a spark Row
   */
  class RootTreeIterator(tree: TTree, // TTree
    streamers: Map[String, TStreamerInfo], // a map of streamers
    requiredColumns: Array[String], // columns that are required for a query
    filters: Array[Filter]) extends Iterator[Row] {

    //  Abstract Schema Tree
    private val att = buildATT(tree, streamers, requiredColumns)
    //  next exists
    def hasNext = containsNext(att)
    //  get the next Row
    def next() = readSparkRow(att)
  }

  /**
   * 1. Builds the Schema
   * 2. Maps execution of each file to a Tree iterator
   */
  class RootTableScan(path: String, optTreeName: Option[String])(@transient val sqlContext: SQLContext) extends BaseRelation with PrunedFilteredScan{
    // path is either a dir with files or a pathToFile
    private val inputPathFiles = {
      val pathFilter = new PathFilter() {
        def accept(ppp: Path): Boolean = 
          ppp.getName.endsWith(".root")
      }
      val hPath = new Path(path)
      val fs = hPath.getFileSystem(sqlContext.sparkContext.hadoopConfiguration)
      def iterate(p: Path): Seq[String] = 
        if (fs.isDirectory(p))
          fs.listStatus(p).flatMap(x => iterate(x.getPath))
        else 
          Seq(p.toString)

      iterate(hPath).filter(x => pathFilter.accept(new Path(x)))
    }
    
    // create the abstract tree

    private val att: core.SRType =
    {
      //logger.info("Building the Abstract Schema Tree...")
      logger.info(s"Building the Abstract Schema Tree... for treeName=${optTreeName}")
      val reader = new RootFileReader(inputPathFiles head)
      findTree(reader.getTopDir, optTreeName) match {
        case Some(tree) => buildATT(tree, arrangeStreamers(reader), null)
        case None => throw NoTTreeException(optTreeName)
      }
    }


    // define the schema from the AST
    def schema: StructType = {
      //logger.info("Building the Spark Schema")
      logger.info("Building the Spark Schema")
      val s = buildSparkSchema(att)
      //logger.info("Done")
      logger.info("Done")
      s
    }


    // builds a scan
    def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
      logger.info("Building Scan")
      println(requiredColumns.mkString(" "))
      println(filters)
      val localOptTreeName = optTreeName

      // parallelize over all the files
      val r = sqlContext.sparkContext.parallelize(inputPathFiles, inputPathFiles.size).
        flatMap({pathName =>
          logger.info(s"Opening file $pathName")
          val reader = new RootFileReader(pathName)
          findTree(reader, localOptTreeName) match {
            case Some(tree) => new RootTreeIterator(tree, arrangeStreamers(reader), 
              requiredColumns, filters)
            case None => throw NoTTreeException(localOptTreeName)
          }
        })

      logger.info("Done building Scan")
      r
    }
  }
}

/**
 *  Default Source - spark.sqlContext.read.root(filename) will be directed here
 *  DefaultSource is used if no registration of the Source has been explicitly made!!
 */
package sparkroot {
  class DefaultSource extends RelationProvider {
    def createRelation(sqlContext: SQLContext, parameters: Map[String, String]) = {
      println(parameters)
      new RootTableScan(parameters.getOrElse("path", sys.error("ROOT path must be specified")), parameters.get("tree"))(sqlContext)
    }
  }
}
