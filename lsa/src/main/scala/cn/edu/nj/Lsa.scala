package cn.edu.nj


import java.io.{FileOutputStream, PrintStream}
import java.util.Properties

import breeze.linalg.{DenseMatrix => BDenseMatrix, DenseVector => BDenseVector, SparseVector => BSparseVector}
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.pipeline.StanfordCoreNLP
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.mllib.linalg.{SingularValueDecomposition, Vector, Vectors}
//import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.rdd.{MapPartitionsRDD, RDD}
import edu.stanford.nlp.ling.CoreAnnotations._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.linalg.Matrix

import scala.collection.JavaConverters._
import scala.collection.Map
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.{ArrayBuffer, HashMap}
import org.apache.spark.rdd.RDD

import scala.collection.JavaConverters._
import scala.collection.Map
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap

/**
  * Created by apple on 17/7/7
  */
object Lsa {


  def main(args: Array[String]) = {
    val conf = new SparkConf().setAppName("lsa")
    val sc = new SparkContext(conf)

    val k = 10
    val numTerms = 500
    val sampleSize = 0.1

    //wrong forward reference ---  missing a "}"
    val (termDocMatrix, termIdss, docIdss, idfss) = preproccessing(sampleSize, numTerms, sc)

    termDocMatrix.cache()

    val mat = new RowMatrix(termDocMatrix)

    val svd = mat.computeSVD(k, computeU = true)

    println("Singular values:" + svd.s)
    val topConceptTrems = topTermsInTopConcepts(svd, 10, 10, termIdss)

    val topConceptDocs = topDocsInTopConcepts(svd, 10, 10, docIdss)
    for ((terms, docs) <- topConceptTrems.zip(topConceptDocs)) {
      println("Concept terms: " + terms.map(_._1).mkString(", "))
      println("Concept docs: " + docs.map(_._1).mkString(", "))
      println()
    }
  }

  def topTermsInTopConcepts(svd: SingularValueDecomposition[RowMatrix, Matrix], numConcepts: Int,
                            numTerms: Int, termIds1: Map[Int, String]): Seq[Seq[(String, Double)]] = {
    val v = svd.V
    val topTerms = new ArrayBuffer[Seq[(String, Double)]]()
    val arr = v.toArray
    for (i <- 0 until numConcepts) {
      val offs = i * v.numRows
      val termWeights = arr.slice(offs, offs + v.numRows).zipWithIndex
      val sorted = termWeights.sortBy(-_._1)
      //topTerms += sorted.take(numTerms).map { case (score, id) => (termIds1(id), score) }
      topTerms += sorted.take(numTerms).map {
        case (score, id) => (termIds1(id), score)
      }
    }
    topTerms
  }

  /**
    *
    * @param numTerms
    * @param sc
    * @return
    */
  def preproccessing(samplesizeo: Double, numTerms: Int, sc: SparkContext)
  : (RDD[Vector], Map[Int, String], Map[Long, String], Map[String, Double]) = {

    val pages = readFile("/Users/apple/Documents/inputPath/nlp_input", sc).sample(false, samplesizeo, 1L);

    val plainText = pages.filter(_ != null).flatMap(x => Some("pages", x))
    //Some((plainText.getTitle, plainText.getContent))

    //stopWord: 干扰词
    val stopWords = sc.broadcast(loadStopWords("stopwords.txt")).value

    val lemmatized = plainText.mapPartitions(iter => {
      val pipeline = createNLPPieline()
      iter.map { case (title, contents) => (title, plainTextToLemmas(contents, stopWords, pipeline)) }
    }
    )
    val filtered = lemmatized.filter(_._2.size > 1)
    //      val filtered = lemmatized.filter(_._2.size > 1).map(x => (x, x))

    documentTermMatrix(filtered, stopWords, numTerms, sc)
  }

  def readFile(path: String, sc: SparkContext): RDD[String] = {

    val conf = new Configuration()
    //目录下有多个文件
    val textFiles = sc.parallelize(path)
    System.out.println(textFiles.collect())
    textFiles.map(p => p.toString)
  }

  /**
    * 返回一个document-term矩阵，矩阵中每个元素为TF-IDF行文档和列。
    *
    * @param docs
    * @param stopWords
    * @param numTerms
    * @param sc
    * @return
    */
  def documentTermMatrix(docs: RDD[(String, Seq[String])], stopWords: Set[String], numTerms: Int,
                         sc: SparkContext): (RDD[Vector], Map[Int, String], Map[Long, String],
    Map[String, Double]) = {

    val docTermFreqs = docs.mapValues(terms => {
      val termFreqsInDoc = terms.foldLeft(new HashMap[String, Int]()) {
        //getOrElse  两个分支类型相同返回值类型为int，不然为Any类型
        (map, term) => map += term -> (map.getOrElse(term, 0) + 1)
        //?
      }
      termFreqsInDoc
    })

    docTermFreqs.cache()

    val docIds = docTermFreqs.map(_._1).zipWithUniqueId().map(_.swap).collectAsMap()

    val docFreqs = documentFrequenciesDistributed(docTermFreqs.map(_._2), numTerms)

    println("Number of terms: " + docFreqs.size)
    saveDocFreqs("docfreqs.tsv", docFreqs)

    val numDocs = docIds.size

    val idfs = inverseDocumentFrequencies(docFreqs, numDocs)

    // Maps terms to their indices in the vector
    val idTerms = idfs.keys.zipWithIndex.toMap
    val termIds = idTerms.map(_.swap)

    val bIdfs = sc.broadcast(idfs).value
    val bIdTerms = sc.broadcast(idTerms).value

    val vecs = docTermFreqs.map(_._2).map(termFreqs => {

      val docTotalTerms = termFreqs.values.sum
      val termScores = termFreqs.filter {
        case (term, freq) => bIdTerms.contains(term)
      }.map {
        case (term, freq) => (bIdTerms(term), bIdfs(term) * termFreqs(term) / docTotalTerms)
      }.toSeq
      Vectors.sparse(bIdTerms.size, termScores)
    })

    (vecs, termIds, docIds, idfs)
  }


  def documentFrequenciesDistributed(docTermFreqs: RDD[HashMap[String, Int]], numTerms: Int)
  : Array[(String, Int)] = {
    //15?
    val docFreqs = docTermFreqs.flatMap(_.keySet).map((_, 1)).reduceByKey(_ + _, 15)
    val ordering = Ordering.by[(String, Int), Int](_._2)
    docFreqs.top(numTerms)(ordering)
  }

  def saveDocFreqs(path: String, docFreqs: Array[(String, Int)]) {
    val ps = new PrintStream(new FileOutputStream(path))
    for ((doc, freq) <- docFreqs) {
      ps.println(s"$doc\t$freq")
    }
    ps.close()
  }

  def inverseDocumentFrequencies(docFreqs: Array[(String, Int)], numDocs: Int)
  : Map[String, Double] = {
    docFreqs.map { case (term, count) => (term, math.log(numDocs.toDouble / count)) }.toMap
  }

  def createNLPPieline(): StanfordCoreNLP = {
    val props = new Properties()
    props.put("annotators", "tokenize,ssplit,pos,lemma")
    new StanfordCoreNLP(props)
  }

  /**
    * 判断是否为字母
    *
    * @param str
    * @return
    */
  def isOnlyLetters(str: String): Boolean = {
    str.forall(c => Character.isLetter(c))
  }


  def plainTextToLemmas(text: String, stopWords: Set[String], pipline: StanfordCoreNLP)
  : Seq[String] = {
    val doc = new Annotation(text)
    pipline.annotate(doc)

    val lemmas = new ArrayBuffer[String]()
    val sentences = doc.get(classOf[SentencesAnnotation])
    //import scala.collection.JavaConverters._ 需要倒入这个包
    for (sentence <- sentences.asScala;
         token <- sentence.get(classOf[TokensAnnotation]).asScala) {
      val lemma = token.get(classOf[LemmaAnnotation])
      //只考虑单纯长度大于2，并且不是干扰词，并且符号要是字母
      if (lemma.length > 2 && !stopWords.contains(lemma) && isOnlyLetters(lemma)) {
        lemmas += lemma.toLowerCase
      }
    }
    lemmas
  }


  /**
    * 将路径下的文件按行转化成一个set集合
    *
    * @param path
    * @return
    */
  def loadStopWords(path: String) = scala.io.Source.fromFile(path).getLines.toSet


  def topDocsInTopConcepts(svd: SingularValueDecomposition[RowMatrix, Matrix], numConcepts: Int,
                           numDocs: Int, docIds: Map[Long, String]): Seq[Seq[(String, Double)]] = {
    val u = svd.U
    val topDocs = new ArrayBuffer[Seq[(String, Double)]]()
    for (i <- 0 until numConcepts) {
      val docWeights = u.rows.map(_.toArray(i)).zipWithUniqueId
      topDocs += docWeights.top(numDocs).map { case (score, id) => (docIds(id), score) }
    }
    topDocs
  }


}