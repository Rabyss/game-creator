package me.reminisce.fetching.config

import com.github.nscala_time.time.Imports._
import me.reminisce.fetching.FBParameters
import org.scalatest.FunSuite

class FBParametersSuite extends FunSuite {

  test("FBSimpleParameters getSince should return the right timestamp.") {
    //default value is DateTime.now minus one year so we don't want to test with the same value
    val since = new DateTime(123l)
    assert(FBParameters(since = since).getSince == (since.getMillis / 1000).toString)
    // checks that until and since are not mixed up
    assert(FBParameters(since = since).getUntil != (since.getMillis / 1000).toString)
  }

  test("FBSimpleParameters getUntil should return the right timestamp.") {
    //default value is DateTime.now so we don't want to test with the same value
    val until = new DateTime(123l)
    assert(FBParameters(until = until).getUntil == (until.getMillis / 1000).toString)
    // checks that until and since are not mixed up
    assert(FBParameters(until = until).getSince != (until.getMillis / 1000).toString)
  }

}
