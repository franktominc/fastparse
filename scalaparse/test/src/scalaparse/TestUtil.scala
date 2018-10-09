package scalaparse

import utest.assert
import fasterparser._, Parsing._



/**
 * Created by haoyi on 5/3/15.
 */
object TestUtil {
  def checkNeg[T](input: String, expected: String = "ADA???D", found: String = "ADQW??") = {
//    println("Checking Neg...\n" )
//    println(input)
    Parse(input).read(Scala.CompilationUnit(_)) match{
      case f: Result.Failure =>

        println("TRACING")
        val traced = f.extra.traced
        val index = f.index
        val parsedExpected = traced.stack.head._1
        val parsedFound = input.slice(f.index, f.index + 10)
        val stack = traced.trace
        assert(
        { implicitly(input)
          implicitly(stack)
          implicitly(index)
          implicitly(parsedFound)
          expected.trim == parsedExpected.trim && parsedFound.startsWith(found)
        }
        )
      case _: Result.Success[_] => assert({implicitly(input); false})
    }
//    for(chunkSize <- Seq(1, 4, 16, 64, 256, 1024)){
//      val res = Scala.CompilationUnit.parseIterator(input.grouped(chunkSize))
//      res match{
//        case f: Result.Failure =>
//
//          val parsedExpected = f.lastParser.toString
//          val parsedFound = input.slice(f.index, f.index + 10)
//          // Note, here we check `expected.contains` rather than `expected ==`!
//          // This is because when parsing an `Iterator`, the `.extra.traced` that
//          // we normally use to get the stack trace doesn't work, so instead we
//          // do an approximate check to make sure the parser is somewhere in the
//          // expected output. OTOH, the `parsedFound` check can still be the same
//          // since that just depends on the `index`
//          assert(
//            { implicitly(input)
//              expected.trim.contains(parsedExpected.trim) && parsedFound.startsWith(found)
//            }
//          )
//        case s: Result.Success[_] => assert{implicitly(input); false}
//      }
//    }
  }

  def check[T](input: String, tag: String = "", skipIterator: Boolean = false) = {
//    println("Checking...\n" )
//    println(input)
    val normalRes = Parse(input).read(Scala.CompilationUnit(_))
    val iteratorRes = Nil
//    val iteratorRes =
//      if (skipIterator) Nil
//      else
//        for(chunkSize <- Seq(1, 4, 16, 64, 256, 1024))
//        yield Scala.CompilationUnit.parseIterator(input.grouped(chunkSize))

    for(res <- normalRes +: iteratorRes){
      res match{
        case f: Result.Failure =>
          //        println(f.formatExpectedAsString)
          //        println(f.formatTraces)
          throw new Exception(tag + "\n" + input + "\n" + f.extra.traced.trace)
        case s: Result.Success[_] =>
          //        println(parsed)
          val inputLength = input.length
          assert(s.index == inputLength)
      }
    }
  }
}
