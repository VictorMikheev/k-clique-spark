import org.jgrapht.graph.{DefaultEdge, SimpleGraph}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class KCliqueLocalTest extends AnyFlatSpec with Matchers {

  "KCliqueLocal" should "count 3-cliques in simple graph" in  {
    val g = makeGraph(
      (1,2),
      (2,3),
      (3,1)
    )

    KCliqueLocal(g).countKCliques(3) should be (1)
  }

  it should "count k-cliques" in {
    val g = makeGraph(
      (1,2),
      (2,3),
      (3,1),
      (1,4),
      (4,2),
      (4,5),
      (3,4)
    )

    KCliqueLocal(g).countKCliques(3) should be (4)
    KCliqueLocal(g).countKCliques(4) should be (1)

  }

  def makeGraph(edges: (Int, Int)*): SimpleGraph[Int, DefaultEdge] = {
    val g = new SimpleGraph[Int, DefaultEdge](classOf[DefaultEdge])

    edges.foreach { case (u, v) =>
      g.addVertex(u)
      g.addVertex(v)
      g.addEdge(u, v)
    }

    g
  }
}
