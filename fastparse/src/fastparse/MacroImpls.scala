package fastparse


import scala.annotation.tailrec
import scala.reflect.macros.blackbox.Context

object MacroImpls {
  def filterMacro[T: c.WeakTypeTag](c: Context)
                                   (f: c.Expr[T => Boolean])
                                   (ctx: c.Expr[ParsingRun[_]]) = {
    import c.universe._
    val lhs = c.prefix.asInstanceOf[c.Expr[EagerOps[T]]]
    reify{
      val ctx1 = ctx.splice
      lhs.splice
      val res =
        if (!ctx1.isSuccess) ctx1.asInstanceOf[ParsingRun[T]]
        else if (f.splice(ctx1.successValue.asInstanceOf[T])) ctx1.asInstanceOf[ParsingRun[T]]
        else {
          val prevCut = ctx1.cut

          val res = ctx1.freshFailure().asInstanceOf[ParsingRun[T]]
          res.cut = prevCut
          res
        }
      if (ctx1.tracingEnabled) ctx1.aggregateMsg(() => "filter")
      res
    }
  }
  def pMacro[T: c.WeakTypeTag](c: Context)
                              (t: c.Expr[ParsingRun[T]])
                              (name: c.Expr[sourcecode.Name],
                               ctx: c.Expr[ParsingRun[_]]): c.Expr[ParsingRun[T]] = {

    import c.universe._
    reify[ParsingRun[T]]{

      val startIndex = ctx.splice.index
      if (ctx.splice.instrument != null) {
        ctx.splice.instrument.beforeParse(name.splice.value, startIndex)
      }
      t.splice match{case ctx0 =>
        if (ctx.splice.instrument != null) {
          ctx.splice.instrument.afterParse(name.splice.value, ctx0.index, ctx0.isSuccess)
        }
        if ((ctx0.tracingEnabled | ctx0.logDepth != 0) && !ctx0.isSuccess) {
          ctx0.setMsg(() => name.splice.value)
          ctx0.failureStack = (name.splice.value -> startIndex) :: ctx0.failureStack
        }
        ctx0
      }
    }
  }
  /**
    * Workaround https://github.com/scala-js/scala-js/issues/1603
    * by implementing startsWith myself
    */
  def startsWith(src: ParserInput, prefix: String, offset: Int) = {
    @tailrec def rec(i: Int): Boolean = {
      if (i >= prefix.length) true
      else if (!src.isReachable(i + offset)) false
      else if (src(i + offset) != prefix.charAt(i)) false
      else rec(i + 1)
    }
    rec(0)
  }
  def literalStrMacro(c: Context)(s: c.Expr[String])(ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[Unit]] = {
    import c.universe._
    s.actualType match{
      case ConstantType(Constant(x: String)) =>
        val literalized = c.Expr[String](Literal(Constant(Util.literalize(x))))
        if (x.length == 0) reify{ ctx.splice.freshSuccess(()) }
        else if (x.length == 1){
          val charLiteral = c.Expr[Char](Literal(Constant(x.charAt(0))))
          reify {

            val res = ctx.splice match{ case ctx1 =>
              if (ctx1.input.isReachable(ctx1.index) && ctx1.input(ctx1.index) == charLiteral.splice){
                ctx1.freshSuccess((), ctx1.index + 1)
              }else{
                ctx1.freshFailure().asInstanceOf[ParsingRun[Unit]]
              }
            }
            if (ctx.splice.tracingEnabled) ctx.splice.aggregateMsg(() => literalized.splice)
            res
          }
        }else{
          val xLength = c.Expr[Int](Literal(Constant(x.length)))
          val checker = c.Expr[(ParserInput, Int) => Boolean]{
            val stringSym = TermName(c.freshName("string"))
            val offsetSym = TermName(c.freshName("offset"))
            val checks = x
              .zipWithIndex
              .map { case (char, i) => q"""$stringSym.apply($offsetSym + $i) == $char""" }
              .reduce[Tree]{case (l, r) => q"$l && $r"}

            q"($stringSym: fastparse.ParserInput, $offsetSym: scala.Int) => $checks"
          }
          reify {

            ctx.splice match{ case ctx1 =>

              val end = ctx1.index + xLength.splice
              val res =
                if (ctx1.input.isReachable(end - 1)  && checker.splice(ctx1.input, ctx1.index) ) {
                  ctx1.freshSuccess((), end)
                }else {
                  ctx1.freshFailure().asInstanceOf[ParsingRun[Unit]]
                }
              if (ctx.splice.tracingEnabled) {
                ctx.splice.aggregateMsg(() => literalized.splice)
              }
              res

            }
          }
        }
      case _ =>
        reify{
          val s1 = s.splice
          ctx.splice match{ case ctx1 =>
            val res =
              if (startsWith(ctx1.input, s1, ctx1.index)) ctx1.freshSuccess((), ctx1.index + s1.length)
              else ctx1.freshFailure().asInstanceOf[ParsingRun[Unit]]
            if (ctx.splice.tracingEnabled) ctx.splice.aggregateMsg(() => Util.literalize(s1))
            res
          }
        }
    }

  }

  def mapMacro[T: c.WeakTypeTag, V: c.WeakTypeTag]
              (c: Context)
              (f: c.Expr[T => V]): c.Expr[ParsingRun[V]] = {
    import c.universe._

    val lhs0 = c.prefix.asInstanceOf[c.Expr[EagerOps[T]]]
    reify {
      lhs0.splice.parse0 match{ case lhs =>
        if (!lhs.isSuccess) lhs.asInstanceOf[ParsingRun[V]]
        else {
          val this2 = lhs.asInstanceOf[ParsingRun[V]]
          this2.successValue = f.splice(this2.successValue.asInstanceOf[T])
          this2
        }
      }
    }
  }


  def flatMapMacro[T: c.WeakTypeTag, V: c.WeakTypeTag]
                  (c: Context)
                  (f: c.Expr[T => ParsingRun[V]]): c.Expr[ParsingRun[V]] = {
    import c.universe._

    val lhs0 = c.prefix.asInstanceOf[c.Expr[EagerOps[T]]]
    reify {
      val lhs = lhs0.splice.parse0
      if (!lhs.isSuccess) lhs.asInstanceOf[ParsingRun[V]]
      else {
        val sCut = lhs.cut
        val res = f.splice(lhs.successValue.asInstanceOf[T])
        res.cut |= sCut
        res
      }
    }
  }

  def eitherMacro[T: c.WeakTypeTag, V >: T: c.WeakTypeTag]
                  (c: Context)
                  (other: c.Expr[ParsingRun[V]])
                  (ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[V]] = {
    import c.universe._

    val lhs0 = c.prefix.asInstanceOf[c.Expr[EagerOps[T]]]
    reify {
      val ctx5 = ctx.splice.asInstanceOf[ParsingRun[V]]
      val oldCut = ctx5.cut
      ctx5.cut = false
      val startPos = ctx5.index

      lhs0.splice
      val lhsMsg = ctx5.shortFailureMsg
      if (ctx5.isSuccess) {
        ctx5.cut |= oldCut
        ctx5
      }
      else if(ctx5.cut) ctx5
      else {
        ctx5.index = startPos
        ctx5.cut = false
        other.splice
        val rhsMsg = ctx5.shortFailureMsg
        val res =
          if (ctx5.isSuccess) {
            ctx5.cut |= oldCut
            ctx5
          }else if (ctx5.cut) ctx5
          else {
            val res = ctx5.freshFailure(startPos)
            ctx5.cut |= oldCut
            ctx5.failureStack = Nil
            res
          }
        if (ctx.splice.tracingEnabled) ctx5.setMsg(() => lhsMsg() + " | " + rhsMsg())
        res
      }

    }
  }

  def captureMacro(c: Context)
                  (ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[String]] = {
    import c.universe._

    val lhs0 = c.prefix.asInstanceOf[c.Expr[EagerOps[_]]]

    reify {
      val ctx6 = ctx.splice
      val startPos = ctx6.index
      val oldCapturing = ctx6.noDropBuffer
      ctx6.noDropBuffer = true
      lhs0.splice
      ctx6.noDropBuffer = oldCapturing
      val msg = ctx6.shortFailureMsg
      val res =
        if (!ctx6.isSuccess) ctx6.asInstanceOf[ParsingRun[String]]
        else ctx6.freshSuccess(ctx6.input.slice(startPos, ctx6.index))
      if (ctx6.tracingEnabled) ctx6.setMsg(() => msg() + ".!")
      res
    }
  }


  def eagerOpsStrMacro(c: Context)
                      (parse0: c.Expr[String])
                      (ctx: c.Expr[ParsingRun[Any]]): c.Expr[fastparse.EagerOps[Unit]] = {
    import c.universe._
    val literal = literalStrMacro(c)(parse0)(ctx)
    reify{ fastparse.EagerOps[Unit](literal.splice)}
  }


  def stringInMacro(c: Context)
                   (s: c.Expr[String]*)
                   (ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[Unit]] = {
    stringInMacro0(c)(false, s:_*)(ctx)
  }
  def stringInIgnoreCaseMacro(c: Context)
                             (s: c.Expr[String]*)
                             (ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[Unit]] = {
    stringInMacro0(c)(true, s:_*)(ctx)
  }
  def stringInMacro0(c: Context)
                    (ignoreCase: Boolean, s: c.Expr[String]*)
                    (ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[Unit]] = {
    import c.universe._

    val literals = s.map(_.actualType match{
      case ConstantType(Constant(x: String)) => x
      case _ => c.abort(c.enclosingPosition, "Function can only accept constant singleton type")
    })
    val trie = new TrieNode(if (ignoreCase) literals.map(_.toLowerCase) else literals)
    val ctx1 = TermName(c.freshName("ctx"))
    val output = TermName(c.freshName("output"))
    val index = TermName(c.freshName("index"))
    val input = TermName(c.freshName("input"))
    val n = TermName(c.freshName("n"))
    def rec(depth: Int, t: TrieNode): c.Expr[Unit] = {
      val charAt = if (ignoreCase) q"$input.apply($n).toLower" else q"$input.apply($n)"
      val children = if (t.children.size == 0) q"()"
      else if (t.children.size > 1){
        q"""
        $charAt match {
          case ..${t.children.map { case (k, v) => cq"$k => ${rec(depth + 1, v)}" }}
          case _ =>
        }
        """
      }else{
        q"""
        if($charAt == ${t.children.keys.head}) ${rec(depth + 1, t.children.values.head)}
        """
      }
      val run = q"""
         val $n = $index + $depth
         ..${if (t.word) Seq(q"$output = $n") else Nil}
         if ($input.isReachable($n)) $children
      """
      val wrap = TermName(c.freshName("wrap"))
      c.Expr[Unit](
        // Best effort attempt to break up the huge methods that tend to be
        // created by StringsIn. Not exact, but hopefully will result in
        // multiple smaller methods being created
        if (!t.break) run
        else q"""
          def $wrap() = $run
          $wrap()
        """
      )
    }

    val bracketed = "StringIn(" + literals.map(Util.literalize(_)).mkString(", ") + ")"

    val res = q"""
      val $ctx1 = $ctx
      val $index = $ctx1.index
      val $input = $ctx1.input

      var $output: Int = -1
      ${rec(0, trie)}
      val res =
        if ($output != -1) $ctx1.freshSuccess((), index = $output)
        else $ctx1.freshFailure()
      if ($ctx1.tracingEnabled) $ctx1.aggregateMsg(() => $bracketed)
      res
    """

    c.Expr[ParsingRun[Unit]](res)

  }
  final class TrieNode(strings: Seq[String], ignoreCase: Boolean = false) {

    val ignoreCaseStrings = if (ignoreCase) strings.map(_.map(_.toLower)) else strings
    val children = ignoreCaseStrings.filter(!_.isEmpty)
      .groupBy(_(0))
      .map { case (k,ss) => k -> new TrieNode(ss.map(_.tail), ignoreCase) }

    val rawSize = children.values.map(_.size).sum + children.size

    val break = rawSize >= 8
    val size: Int = if (break) 1 else rawSize
    val word: Boolean = strings.exists(_.isEmpty) || children.isEmpty
  }

  def parseCharCls(c: Context)(char: c.Expr[Char], ss: Seq[String]) = {
    import c.universe._

    val snippets = for(s <- ss) yield{
      val output = collection.mutable.Buffer.empty[Either[Char, (Char, Char)]]
      var i = 0
      while(i < s.length){
        s(i) match{
          case '\\' =>
            i += 1
            output.append(Left(s(i)))
          case '-' =>
            i += 1
            val Left(last) = output.remove(output.length - 1)
            output.append(Right((last, s(i))))
          case c => output.append(Left(c))
        }
        i += 1
      }

      (
        output.collect{case Left(char) => cq"$char => true"},
        output.collect{case Right((l, h)) => q"$l <= charIn && charIn <= $h"}
      )
    }

    val (literals, ranges) = snippets.unzip
    c.Expr[Boolean](q"""$char match{
      case ..${literals.flatten}
      case charIn => ${ranges.flatten.reduceOption{ (l, r) => q"$l || $r"}.getOrElse(q"false")}
    }""")
  }

  def charInMacro(c: Context)
                 (s: c.Expr[String]*)
                 (ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[Unit]] = {
    import c.universe._

    val literals = s.map(_.actualType match{
      case ConstantType(Constant(x: String)) => x
      case _ => c.abort(c.enclosingPosition, "Function can only accept constant singleton type")
    })

    val parsed = parseCharCls(c)(reify(ctx.splice.input(ctx.splice.index)), literals)
    val bracketed = c.Expr[String](Literal(Constant(literals.map("[" + _ + "]").mkString)))
    reify {
      val res =
        if (!ctx.splice.input.isReachable(ctx.splice.index)) {
          ctx.splice.freshFailure().asInstanceOf[ParsingRun[Unit]]
        } else parsed.splice match {
          case true => ctx.splice.freshSuccess((), ctx.splice.index + 1)
          case false => ctx.splice.freshFailure().asInstanceOf[ParsingRun[Unit]]
        }
      if (ctx.splice.tracingEnabled) ctx.splice.aggregateMsg(() => bracketed.splice)
      res
    }
  }

  def parsedSequence0[T: c.WeakTypeTag, V: c.WeakTypeTag, R: c.WeakTypeTag]
                     (c: Context)
                     (other: c.Expr[ParsingRun[V]], cut: Boolean)
                     (s: c.Expr[Implicits.Sequencer[T, V, R]],
                      whitespace: Option[c.Expr[ParsingRun[Any] => ParsingRun[Unit]]],
                      ctx: c.Expr[ParsingRun[_]]): c.Expr[ParsingRun[R]] = {
    import c.universe._

    val lhs = c.prefix.asInstanceOf[Expr[EagerOps[T]]]
    val cut1 = c.Expr[Boolean](if(cut) q"true" else q"false")
    val consumeWhitespace = whitespace match{
      case None => reify{(c: ParsingRun[Any]) => true}
      case Some(ws) =>
        if (ws.tree.tpe =:= typeOf[fastparse.NoWhitespace.noWhitespaceImplicit.type]){
          reify{(c: ParsingRun[Any]) => true}
        }else{
          reify{(c: ParsingRun[Any]) =>
            val oldCapturing = c.noDropBuffer // completely disallow dropBuffer
            c.noDropBuffer = true
            ws.splice(c)
            c.noDropBuffer = oldCapturing
            c.isSuccess
          }
        }
    }

    reify {
      {
        val startIndex = ctx.splice.index
        lhs.splice.parse0 match{ case ctx3 =>
          if (!ctx3.isSuccess) ctx3
          else {
            val lhsMsg = ctx3.shortFailureMsg
            ctx3.cut |= cut1.splice
            if (ctx3.index > startIndex && ctx3.checkForDrop()) ctx3.input.dropBuffer(ctx3.index)

            val pValue = ctx3.successValue
            val preWsIndex = ctx3.index
            if (!consumeWhitespace.splice(ctx3)) ctx3
            else {
              val preOtherIndex = ctx3.index
              other.splice
              val postOtherIndex = ctx3.index

              val rhsNewCut = cut1.splice | ctx3.cut
              val msg = ctx3.shortFailureMsg
              val res =
                if (!ctx3.isSuccess) ctx3.augmentFailure(
                  ctx3.index,
                  cut = rhsNewCut
                ) else {
                  val rhsMadeProgress = postOtherIndex > preOtherIndex
                  val nextIndex =
                    if (!rhsMadeProgress && ctx3.input.isReachable(postOtherIndex)) preWsIndex
                    else ctx3.index

                  if (rhsMadeProgress && ctx3.checkForDrop()) ctx3.input.dropBuffer(ctx3.index)

                  ctx3.freshSuccess(
                    s.splice.apply(pValue.asInstanceOf[T], ctx3.successValue.asInstanceOf[V]),
                    nextIndex,
                    cut = rhsNewCut
                  )
                }
              if (ctx3.tracingEnabled) ctx3.setMsg(() => lhsMsg() + " ~ " + msg())
              res
            }
          }

        }
      }.asInstanceOf[ParsingRun[R]]
    }
  }

  def cutMacro[T: c.WeakTypeTag](c: Context)(ctx: c.Expr[ParsingRun[_]]): c.Expr[ParsingRun[T]] = {
    import c.universe._
    val lhs = c.prefix.asInstanceOf[c.Expr[EagerOps[_]]]
    reify{

      val startIndex = ctx.splice.index
      val ctx1 = lhs.splice.parse0
      if (!ctx1.isSuccess) ctx1.augmentFailure(ctx1.index)
      else {
        val progress = ctx1.index > startIndex
        if (progress && ctx1.checkForDrop()) ctx1.input.dropBuffer(ctx1.index)
        val msg = ctx1.shortFailureMsg

        val res =
          ctx1.freshSuccess(
            ctx1.successValue,
            cut = ctx1.cut | progress
          ).asInstanceOf[ParsingRun[T]]
        if (ctx1.tracingEnabled) ctx1.aggregateMsg(() => msg() + "./")
        res
      }

    }
  }


  def charsWhileInMacro1(c: Context)
                        (s: c.Expr[String])
                        (ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[Unit]] = {
    import c.universe._
    charsWhileInMacro(c)(s, reify(1))(ctx)
  }
  def charsWhileInMacro(c: Context)
                       (s: c.Expr[String], min: c.Expr[Int])
                       (ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[Unit]] = {
    import c.universe._

    val literal = s.actualType match{
      case ConstantType(Constant(x: String)) => x
      case _ => c.abort(c.enclosingPosition, "Function can only accept constant singleton type")
    }

    val bracketed = c.Expr[String](Literal(Constant("[" + literal + "]")))

    val ctx1 = TermName(c.freshName("ctx"))
    val index = TermName(c.freshName("index"))
    val input = TermName(c.freshName("input"))
    val start = TermName(c.freshName("start"))
    val res = q"""
      val $ctx1 = $ctx
      var $index = $ctx1.index
      val $input = $ctx1.input

      val $start = $index
      while(
        $ctx1.input.isReachable($index) &&
        ${parseCharCls(c)(c.Expr[Char](q"$input($index)"), Seq(literal))}
      ) $index += 1
      val res =
        if ($index - $start >= $min) $ctx1.freshSuccess((), index = $index)
        else {
          $ctx1.isSuccess = false
          $ctx1.asInstanceOf[fastparse.P[Unit]]
        }
      if ($ctx1.tracingEnabled) $ctx1.aggregateMsg(() => $bracketed)
      res
    """
    c.Expr[ParsingRun[Unit]](res)
  }


  def byNameOpsStrMacro(c: Context)
                       (parse0: c.Expr[String])
                       (ctx: c.Expr[ParsingRun[Any]]): c.Expr[fastparse.ByNameOps[Unit]] = {
    import c.universe._
    val literal = MacroImpls.literalStrMacro(c)(parse0)(ctx)
    reify{ new fastparse.ByNameOps[Unit](() => literal.splice)}
  }

  def logOpsStrMacro(c: Context)
                       (parse0: c.Expr[String])
                       (ctx: c.Expr[ParsingRun[Any]]): c.Expr[fastparse.LogByNameOps[Unit]] = {
    import c.universe._
    val literal = MacroImpls.literalStrMacro(c)(parse0)(ctx)
    reify{ new fastparse.LogByNameOps[Unit](literal.splice)(ctx.splice)}
  }

  def optionMacro[T: c.WeakTypeTag, V: c.WeakTypeTag](c: Context)
                 (optioner: c.Expr[Implicits.Optioner[T, V]],
                  ctx: c.Expr[ParsingRun[Any]]): c.Expr[ParsingRun[V]] = {
    import c.universe._
    val lhs0 = c.prefix.asInstanceOf[c.Expr[EagerOps[_]]]
    reify{
      ctx.splice match{ case ctx1 =>
        val startPos = ctx1.index
        val startCut = ctx1.cut
        ctx1.cut = false
        lhs0.splice
        val msg = ctx1.shortFailureMsg
        val res =
          if (ctx1.isSuccess) {
            val res = ctx1.freshSuccess(optioner.splice.some(ctx1.successValue.asInstanceOf[T]))
            res.cut |= startCut
            res
          }
          else if (ctx1.cut) ctx1.asInstanceOf[ParsingRun[V]]
          else {
            val res = ctx1.freshSuccess(optioner.splice.none, startPos)
            res.cut |= startCut
            res
          }
        if (ctx1.tracingEnabled) ctx1.setMsg(() => msg() + ".?")
        res

      }
    }
  }
}