/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling.example

import io.gatling.core.scenario.Simulation
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class Loadtest extends Simulation {

  // Insert your actual load test here
  val scn = scenario("Gatling AWS Maven plugin example loadtest")
    .repeat(2) {
      exec(http("ping")
        .get("/ping")
        .check(status.is(200)))
        .pause(1, 3)
    }

  setUp(scn.inject(rampUsers(10) over (1 minute))
    .protocols(http.baseURL("https://load-test-me.appspot.com")
      .acceptEncodingHeader("gzip, deflate")
      .userAgentHeader("Gatling")
      .shareConnections))
}
