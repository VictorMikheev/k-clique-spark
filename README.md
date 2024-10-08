# Прототип реализации распределённого алгоритма k-clique enumeration.

Дан граф $G=(V,E)$ и целое число $k>0$.  Нужно подсчитать количество $k$-клик в графе $G$ (только общее количество; сами
клики возвращать в качестве ответа не нужно).
- https://en.wikipedia.org/wiki/Clique_problem 

## Условия

Есть вычислительный кластер из N машин.  Все машины одинаковые по железу, размеру RAM и num cpu cores.  Ориентир:
- количество серверов N = 10
- num_cpu_cores = 100 ядер на каждой машине
- RAM size ~300-600GB на каждой машине

Размеры данных.  Размер структуры графа (множества вершин и рёбер) настолько большой, что он не помещается в RAM одного сервера.  
Однако, помещается в сумму оперативных памятей всех N машин кластера.

Структура графа разбита на фрагменты, которые партиционированы по N машинам.
Все вычисления на графе должны быть распределёнными (multi-node multi-core).  

Ожидается горизонтальное масштабирование и близкое к идеалу распараллеливание (т.е. вычисление на N серверах 
будет почти в N раз быстрее вычисления на одном сервере).

## Задача

Предложить дизайн распределённого алгоритма на графе, который будет хорошо масштабироваться горизонтально.

## Введение

Данный прототип реализован для Apаche Spark и основан на алгоритме описанном в статье [1]. 

Для вершин графа имеющих степень не менее $k-1$ необходимо построить их окрестности. Далее, для каждой найденной 
окрестности локально запускается алгоритм поиска $k-1$ клик. Результат суммируется. 

Каждая окрестность как структура данных не связана с другими окрестностями и является достаточной для поиска $k-1$ клик
в ней. Так же предполагается, что любая найденная окрестность помещается в оперативной памяти любой машины кластера. За счет 
этого достигается высокий параллелизм алгоритма. 

Для исключения дубликатов окрестностей, а также с целью оптимизации, вводится порядок вершин графа, такой, что вершины 
с меньшей степенью следует ранее вершин с большей степенью [2]. Каждое ребро исходного графа преобразуется в соответствии 
с этим порядком.  


##  Описание алгоритма

На входе ``RDD[(Int, Int)]`` кортежей ``(Int, Int)`` ребер графа. Каждое ребро представлено только в одном направлении.
То есть, для графа из двух вершин $A$, $B$ и одним ребром между $A$ и $B$ в *RDD* будет присутствовать только один 
кортеж - либо ``(A,B)``, либо ``(B,A)``

Алгоритм работает в 5 этапов

### Этап 1 - определение степеней вершин графа

Для каждого картежа ``(A,B)`` исходного *RDD* добавляем еще один перевернутый кортеж ``(B, A)``. Полученный RDD 
группируем по первому значению кортежа, подсчитываем кол-во значений в группе - это будет степень вершины. 
Далее делаем то же самое для второго значения кортежа исходного RDD. 

В результате получаем RDD ребер заданных двумя вершинами графа с их степенями  ``RDD[(Node, Node)]``. 
Где ``Node(id: Int, degree: Int)`` представляет вершину графа с идентификатором ``id`` и  степенью ``degree``  

Код этапа 1:
```scala
val edgesWithNodeDegree: RDD[(Node, Node)] =
  edges
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
```


### Этап 2 - фильтрация графа
Остаются ребра у которых вершины имеют степень большую либо равную $k-1$ (каждая вершина клика 
связана со всеми остальными вершинами клика, то есть степень каждой вершины клика не может быть меньше $k-1$). 
Так же исключаются дубликаты кортежей ребер графа у которых степень первой вершины больше степени второй

Код этапа 2:
```scala
val  filteredEdges: RDD[(Node, Node)] =
  edgesWithNodeDegree
    .filter { case (u,v) =>  u.degree >= k - 1 && v.degree >= k - 1 && u < v }
```

###  Этап 3 - определение смежных вершин
Для каждой вершины графа определяется список смежных с ней вершин.

Код этапа 3:
```scala
val adjacencyList: RDD[(Node, Iterable[Node])] =
  filteredEdges
    .groupByKey()
    
```

### Этап 4 - определение окрестностей вершин графа
Для полученных списков смежных вершин находим их комбинации $(xi, xj)$.
К каждой такой комбинации добавляем вершину $u$. Получаем ``RDD[((Node, Node), Node)]`` кортежей $((xi, xj), u)$.
Далее объединяем получившиеся комбинации с отфильтрованными ребрами графа на этапе 2.
При этом к ребрам графа (u,v) добавляем метку MARK. Получаем ``RDD[((Node, Node), Node)]`` кортежей $((u, v), MARK)$.

Группируем по первым двум значениям кортежа и проверяем, есть ли в получившейся группе метка *MARK*. Если метка 
присутствует, значит ребро в графе определено. Получаем ``RDD[(Node,Node), Iterable[Node]]`` со значениями $((xi, xj),
List(u))$. 

Разворачиваем полученный RDD в плоскую структуру кортежей $(u, (xi,xj))$ из вершины $u$ и ребра окрестности $(xi, xj)$
этой вершины. Группируем по $u$, получаем ``RDD[Node, Interable[(Node, Node)]]`` с множеством окрестностей графа.

```scala
val combinations: RDD[((Node, Node), Node)] =
  adjacencyList.mapPartitions { it =>
    it.flatMap {
      case (u, x) =>
        combinationIterator(x).map(c => (c, u))
    }
  }

val union: RDD[((Node, Node), Node)] =
  filteredEdges
    .map{ case (u,v) => ((u,v), MARK)}
    .union(combinations)
    .persist(StorageLevel.MEMORY_AND_DISK)

val neighborhoods: RDD[(Node, Iterable[(Node, Node)])] =
  union
    .groupByKey()
    .collect { case ((xi, xj), us) if us.iterator.contains(MARK) => ((xi, xj), us.filterNot(_ == MARK))}
    .flatMap { case ((xi, xj), us) =>
      us.map( u => (u, (xi, xj)))
    }
    .groupByKey()
```

### Этап 5 - подсчет *k-1* кликов для каждой окрестности и суммирование результатов

Для каждой окрестности графа подсчитываем количество $k-1$ клик. Далее суммируем. Полученный результат - это 
кол-во $k$ клик в графе

```scala
val kCliques: RDD[Long] =
  neighborhoods
    .map { case (u, xs) =>
      val g = new SimpleGraph[Int, DefaultEdge](classOf[DefaultEdge])
      xs.foreach { case (xi, xj) =>
        g.addVertex(xi.id)
        g.addVertex(xj.id)
        g.addEdge(xi.id, xj.id)
      }
      KCliqueLocal(g).countKCliques(k - 1).toLong
    }

val numOfCliques: Long = kCliques.reduce(_ + _)
```


## Сложность алгоритма

#### Этап 1 - определение степеней вершин графа

Для подсчета степени вершины требуется $O(N)$ времени и памяти. Для всех вершин суммарно $O(m)$ по времени.
На коммуникационные расходы потребуется $O(M)$.


#### Этап 2 - фильтрация графа

Суммарно по времени $O(M)$.

#### Этап 3 - определение смежных вершин

Для каждого списка смежных вершин сложность по времени $O(\sqrt m)$ и по памяти $O(\sqrt m)$.
Суммарно по времени $O(M)$.
Суммарно на передачу данных требуется $O(M)$.

#### Этап 4 - определение окрестностей вершин графа

На каждую окрестность требуется $O(\sqrt m)$ памяти и $(O(m))$ времени.
На передачу данных на каждую окрестность $(O(m))$, суммарно $O(M^{3/2})$.

#### Этап 5 - подсчет *k-1* кликов для каждой окрестности и суммирование результатов

Каждая окрестность содержит не более $\sqrt{m}$ вершин и на подсчет k-1 клик для каждой окрестности требуется
$O( m ^ { (k-1) / 2} )$ времени. Суммарно требуется $O( m ^ {k / 2})$ времени.

## Эксперименты

Запуск прототипа

 - установить JDK 1.7 и выше
 - скачать и установить ApacheSpark (https://spark.apache.org/downloads.html)
 - установить SBT (https://www.scala-sbt.org/)
 - собрать проект sbt assemply
 - заменить пути на свои в скрипте запуска приложения run_local.sh
   ```
    #  путь где установлен Apache spark
    SPARK_HOME="/Users/victormikheev/opt/spark-3.5.1"
    #  Толстый  JAR с приложением  находится в папке target/scala-2.13 проекта
    JARFILE="/Users/victormikheev/projects/k-clique-spark/target/scala-2.13/k-clique-spark-assembly-0.1.0-SNAPSHOT.jar"
   ```
 - запустить приложение  ``./run_local.sh 3 test_graph.txt``
      первый аргумент $k$ искомых кликов, второй путь до файла с ребрами графа

Тестовый стенд

- MacBook M1 Max 32 Gb RAM 10 Cores (8 быстрых и 2 энергоэффективных)

Набор данных 1 [3]
- источник https://snap.stanford.edu/data/com-Youtube.html
- количество вершин - 1 134 890
- количество ребер - 2 987 624
- количество треугольников - 3 056 386 

Набор данных 2 [3]
- источник https://snap.stanford.edu/data/soc-LiveJournal1.html
- количество вершин - 4 847 571
- количество ребер - 68 993 773
- количество треугольников - 285 730 264

Запуск алгоритма производился для поиска 3-кликов (треугольников) с параллелизмом p от 1 до 6

Время работы в мин:сек

| p              | 1      | 2     | 3     | 4     | 5     | 6     |
|----------------|--------|-------|-------|-------|-------|-------|
| Набор данных 1 | 00:42  | 00:24 | 00:18 | 00:15 | 00:14 | 00:12 |
| Набор данных 2 | 113:02 | 65:34 |       | 40:21 |       | 31:06 |


 На результаты испытаний, возможно, существенное влияние оказывает использование экзекуторами 
 разделяемой оперативной памяти компьютера.


 ## Ссылки

[1] Irene Finocchi, Marco Finocchi, Emanuele G. Fusco. Clique counting in MapReduce: theory and experiments. In ACM 
Journal of Experimental Algorithmics 20: 1.7:1-1.7:20 (2015)

[2] S. Suri and S. Vassilvitskii. Counting triangles and the curse of the last reducer. In Proc. 20th International
Conference on World Wide Web, WWW ’11, pages 607–614, 2011

[3] SNAP graph library. http://snap.stanford.edu/.