package com.ea.gatling.example

import io.gatling.core.scenario.Simulation
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class ColdspikeTest extends Simulation {

  val scn = scenario("Gatling AWS Maven plugin example loadtest")
    .repeat(10, "value") {
      exec(http("ping?key=${value}")
        .get("/ping")
        .check(status.is(200)))
        .pause(1, 10)
    }

  setUp(scn.inject(atOnceUsers(100))
    .protocols(http.baseURL("https://load-test-me.appspot.com")
      .acceptEncodingHeader("gzip, deflate")
      .userAgentHeader("Gatling Soak Test")
      .shareConnections))

}