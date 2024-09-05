import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

import scala.collection.AbstractIterator

object KCliqueSpark {

  case class Node(id: Int, degree: Int) extends Ordered[Node] {

    override def compare(that: Node): Int = {
      if (this.degree != that.degree) {
        this.degree.compare(that.degree)
      } else {
        this.id.compareTo(that.id)
      }
    }
  }

  private val MARK = Node(-1, 0)


  def main(args: Array[String]): Unit = {

    val sparkConf = new SparkConf().setAppName("K-Clique")

    val sc = SparkContext.getOrCreate(sparkConf)

    val k = args(0).toInt
    val src = sc.textFile(args(1))

    val edges = src
      .filter(!_.startsWith("#"))
      .map { str =>
        str.split("\\s+") match {
          case Array(u,v) =>
            val ut = u.trim.toInt
            val vt = v.trim.toInt
            if (ut < vt) (ut, vt) else (vt, ut)
        }
      }.repartition(sc.defaultParallelism)


    val edgesWithNodeDegree = edges
      .flatMap { case (u,v) => Array((u,v), (v,u)) }
      .groupByKey()
      .flatMap { case (u, vs) =>
        val d = vs.size
        val node = Node(u, d)
        vs.map(v => (v, node))
      }.groupByKey()
      .flatMap { case (v, us) =>
        val d = us.size
        val node = Node(v, d)
        us.map( u => (u, node))
      }



    val  filteredEdges = edgesWithNodeDegree
      .filter { case (u,v) =>  u.degree >= k - 1 && v.degree >= k - 1 && u < v }

    val adjacencyList =  filteredEdges
      .groupByKey()

    val combinations = adjacencyList.mapPartitions { it =>
      it.flatMap {
        case (u, x) =>
          combinationIterator(x).map(c => (c, u))
      }
    }

    val union =  filteredEdges
      .map{ case (u,v) => ((u,v), MARK)}
      .union(combinations)
      .persist(StorageLevel.MEMORY_AND_DISK)

    val neighborhoods = union
      .groupByKey()
      .collect { case ((xi, xj), us) if us.iterator.contains(MARK) => ((xi, xj), us.filterNot(_ == MARK))}
      .flatMap { case ((xi, xj), us) =>
        us.map( u => (u, (xi, xj)))
      }
      .groupByKey()


    val kCliques = neighborhoods
      .map { case (u, xs) =>
         val g = new SimpleGraph[Int, DefaultEdge](classOf[DefaultEdge])
         xs.foreach { case (xi, xj) =>
           g.addVertex(xi.id)
           g.addVertex(xj.id)
           g.addEdge(xi.id, xj.id)
         }
         KCliqueLocal(g).countKCliques(k - 1).toLong
      }
    val numOfCliques = kCliques.reduce(_ + _)
    println(s"Number of $k-cliques is ${numOfCliques}")
  }

  private def combinationIterator(ls: Iterable[Node]): Iterator[(Node, Node)] = {
    val a = ls.toArray

    new AbstractIterator[(Node, Node)] {
      var i = 0
      var j = 0

      override def hasNext: Boolean = {
        while (i < a.length && a(i) >= a(j)) {
          next()
        }
        i < a.length && j < a.length
      }

      override def next(): (Node, Node) = {
        val r = (a(i), a(j))
        j += 1
        if (j >= a.length) {
          j = 0
          i += 1
        }
        r
      }
    }
  }
}
