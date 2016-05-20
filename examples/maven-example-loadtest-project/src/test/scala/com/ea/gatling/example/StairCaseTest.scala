/**
 * Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
 */
package com.ea.gatling.example

import io.gatling.core.scenario.Simulation
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.collection.mutable.MutableList
import io.gatling.core.controller.inject.InjectionStep

class StairCaseTest extends Simulation {

  val scn = scenario("Gatling AWS Maven plugin example loadtest")
    .repeat(30) {
      exec(http("ping")
        .get("/ping")
        .check(status.is(200)))
        .pause(1, 10)
    }

  var steps = MutableList[InjectionStep]()

  val duration = (10 minutes);
  val stages = 10;
  val ratePerStage = 0.1;

  for (s <- 1 to stages) {
    val rate = s * ratePerStage
    steps += (constantUsersPerSec(rate) during (duration))
  }

  setUp(scn.inject(steps.toList)
    .protocols(http.baseURL("https://load-test-me.appspot.com")
      .acceptEncodingHeader("gzip, deflate")
      .userAgentHeader("Gatling Test")
      .shareConnections))
}