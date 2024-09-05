import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

import scala.reflect.ClassTag

case class KCliqueLocal[T: ClassTag](graph: SimpleGraph[T, DefaultEdge]) {

  import scala.jdk.CollectionConverters._

  private val MAX = 100
  private val candidates: Array[T] = Array.ofDim[T](MAX)
  private val nodes = graph.vertexSet().asScala.toArray

  def isClique(size: Int): Boolean = {

    var i = 0
    var j = i + 1

    while (i < size) {
      while (j < size) {
        if (!graph.containsEdge(candidates(i), candidates(j))) {
          return false
        }
        j += 1
      }
      i += 1
    }
    true
  }

  def countKCliques(k: Int): Int = {
    countKCliques(0, 0, k)
  }

  private def countKCliques(start: Int, level: Int, k: Int): Int = {
    var count = 0

    var j = start
    while (j < nodes.length) {

      if (graph.degreeOf(nodes(j)) >= k - 1) {
        candidates(level) = nodes(j)

        if (isClique(level + 1)) {
          if (level < k - 1) {
            count += countKCliques(j + 1, level + 1, k)
          } else {
            count += 1
          }
        }
      }
      j += 1
    }
    count
  }

}
