package com.ea.gatling.example

import io.gatling.core.scenario.Simulation
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class RamupTest extends Simulation {

  val scn = scenario("Gatling AWS Maven plugin example loadtest")
    .repeat(60, "value") {
      exec(http("ping").get("/ping")).pause(10, 20)
    }

  setUp(scn.inject(rampUsers(100000) over (30 minutes))
    .protocols(http.baseURL("https://load-test-me.appspot.com")
      .acceptEncodingHeader("gzip, deflate")
      .userAgentHeader("Gatling Ramup Test")
      .shareConnections))

}
