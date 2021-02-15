package ldbc.snb.datagen.generation.serializer

import ldbc.snb.datagen.DatagenContext
import ldbc.snb.datagen.entities.dynamic.person.Person
import ldbc.snb.datagen.serializer.{DynamicPersonSerializer, PersonExporter}
import ldbc.snb.datagen.util.{GeneratorConfiguration, SerializableConfiguration}
import ldbc.snb.datagen.syntax._
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

object SparkPersonSerializer {

  def apply(
    persons: RDD[Person],
    conf: GeneratorConfiguration,
    partitions: Option[Int] = None
  )(implicit spark: SparkSession): Unit = {
    val serializableHadoopConf = new SerializableConfiguration(spark.sparkContext.hadoopConfiguration)

    persons
      .pipeFoldLeft(partitions)((rdd: RDD[Person], p: Int) => rdd.coalesce(p))
      .foreachPartition(persons => {
        val dynamicPersonSerializer = new DynamicPersonSerializer
        val hadoopConf = serializableHadoopConf.value
        val partitionId = TaskContext.getPartitionId()
        val buildDir = conf.getOutputDir

        val fs = FileSystem.get(hadoopConf)
        fs.mkdirs(new Path(buildDir))

        dynamicPersonSerializer.initialize(
          hadoopConf,
          conf.getOutputDir,
          partitionId,
          false
        )

        val personExporter = new PersonExporter(dynamicPersonSerializer)

        personExporter use { pe =>
          DatagenContext.initialize(conf)
          for {p <- persons} {
            pe.export(p)
          }
        }
      })
  }
}
