package site.uoucampus.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 웹(Client/src/routing)과 같은 길이 나오는지. 기대값은 웹 코드로 뽑았다 — campus.json 이 바뀌면 다시 뽑는다. */
class RoutingTest {
  private val graph = CampusGraph.parse(File("../../Client/src/data/campus.json").readText())

  @Test
  fun routesMatchWeb() {
    val library = findRoute(graph, "b15", "b3", RouteOptions())!!
    assertEquals(176.192, library.meters, 0.001)
    assertEquals(135.532, library.seconds, 0.001)
    assertEquals(listOf("문수관에서 출발, 130m", "오른쪽으로 50m — 행정본관"), Directions.steps(graph, library).map { it.text })

    val stairs = findRoute(graph, "b20", "b28", RouteOptions())!!
    assertEquals(listOf("s2", "e87", "e86", "e399"), stairs.legs.map { it.link.edge.id })
    assertEquals(
      listOf("기초과학실험동에서 출발, 40m", "음악대학 앞에서 직진 계단으로 30m", "오른쪽으로 살짝 꺾어 65m — 조형관"),
      Directions.steps(graph, stairs).map { it.text },
    )
  }

  @Test
  fun roomRepair() {
    assertEquals("19-509", Room.normalize("l9 − 5O9"))
    /* 1-9509 는 95층이라 버리고 19-509 만 남는다. */
    assertEquals("19-509", Room.repair("19509", setOf(1, 19)))
    assertEquals(6, Room.floor("7-615"))
  }
}
