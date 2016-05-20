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

class SoakTest extends Simulation {

  // Insert your actual load test here
  val scn = scenario("Gatling AWS Maven plugin example loadtest")
    .repeat(30) {
      exec(http("ping")
        .get("/ping")
        .check(status.is(200)))
        .pause(1, 10)
    }

  val numberOfDays = 1
  val duration = (1 minute);
  val stagesPerDay = 24;
  val relativeRateIncreasePerDay = 0.2;
  val stretchFactor = 10
  var steps = MutableList[InjectionStep]()

  for (day <- 1 to numberOfDays) {
    for (s <- 1 to stagesPerDay) {
      val x = 2 * math.Pi * s / stagesPerDay
      val rate = stretchFactor * math.sin(x) * (1 + day * relativeRateIncreasePerDay) 
      if (rate > 0.1) {
        println(rate);
        steps += (constantUsersPerSec(rate) during (duration))                
      }
    }
  }

  setUp(scn.inject(steps.toList)
    .protocols(http.baseURL("https://load-test-me.appspot.com")
      .acceptEncodingHeader("gzip, deflate")
      .userAgentHeader("Gatling Soak Test")
      .shareConnections))
}
