package test.fasterparser
import fasterparser.Parse._
import fasterparser._
import test.fasterparser.Expr.Member.Visibility


class FasterParserParser{
  val parseCache = collection.mutable.Map.empty[String, fastparse.all.Parsed[Expr]]

  val precedenceTable = Seq(
    Seq("*", "/", "%"),
    Seq("+", "-"),
    Seq("<<", ">>"),
    Seq("<", ">", "<=", ">=", "in"),
    Seq("==", "!="),
    Seq("&"),
    Seq("^"),
    Seq("|"),
    Seq("&&"),
    Seq("||"),
  )

  val precedence = precedenceTable
    .reverse
    .zipWithIndex
    .flatMap{case (ops, idx) => ops.map(_ -> idx)}
    .toMap

  implicit def whitespace(cfg: Ctx[_]): Parsed[Unit] = {
    implicit val cfg0 = cfg
    P{

      def rec(current: Int, state: Int): Parsed.Success[Unit] = {
        if (current >= cfg.input.length) cfg.prepareSuccess((), current, false)
        else state match{
          case 0 =>
            cfg.input(current) match{
              case ' ' | '\t' | '\n' | '\r' => rec(current + 1, state)
              case '#' => rec(current + 1, state = 1)
              case '/' => rec(current + 1, state = 2)
              case _ => cfg.prepareSuccess((), current, false)
            }
          case 1 =>
            cfg.input(current) match{
              case '\n' => rec(current + 1, state = 0)
              case _ => rec(current + 1, state)
            }
          case 2 =>
            cfg.input(current) match{
              case '/' => rec(current + 1, state = 1)
              case '*' => rec(current + 1, state = 3)
              case _ => cfg.prepareSuccess((), current - 1, false)
            }
          case 3 =>
            cfg.input(current) match{
              case '*' => rec(current + 1, state = 4)
              case _ => rec(current + 1, state)
            }
          case 4 =>
            cfg.input(current) match{
              case '/' => rec(current + 1, state = 0)
              case _ => rec(current + 1, state = 3)
            }
        }
      }
      rec(current = cfg.success.index, state = 0)
    }
  }

  val keywords = Set(
    "assert", "else", "error", "false", "for", "function", "if", "import", "importstr",
    "in", "local", "null", "tailstrict", "then", "self", "super", "true"
  )

  val digitChar = fastparse.utils.MacroUtils.preCompute(c =>
    ('0' to '9').contains(c)
  )
  val idStartChar = fastparse.utils.MacroUtils.preCompute(c =>
    ("_" ++ ('a' to 'z') ++ ('A' to 'Z')).contains(c)
  )
  val idChar = fastparse.utils.MacroUtils.preCompute(c =>
    ("_" ++ ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).contains(c)
  )
  def id[_: Ctx] = P(
    CharPred(idStartChar) ~~
    CharsWhile(idChar, min = 0)
  ).!.filter(s => !keywords.contains(s))

  def break[_: Ctx] = P(!CharPred(idChar))
  def number[_: Ctx]: P[Expr.Num] = P(
    Index ~~ (
      CharsWhile(digitChar) ~~
        ("." ~ CharsWhile(digitChar)).? ~~
        (("e" | "E") ~ ("+" | "-").? ~~ CharsWhile(digitChar)).?
      ).!
  ).map(s => Expr.Num(s._1, s._2.toDouble))

  def escape[_: Ctx] = P( escape0 | escape1 )
  def escape0[_: Ctx] = P("\\" ~~ !"u" ~~ AnyChar.!).map{
    case "\"" => "\""
    case "'" => "\'"
    case "\\" => "\\"
    case "/" => "/"
    case "b" => "\b"
    case "f" => "\f"
    case "n" => "\n"
    case "r" => "\r"
    case "t" => "\t"
  }
  def escape1[_: Ctx] = P( "\\u" ~~ CharPred(digitChar).repX(min=4, max=4).! ).map{
    s => Integer.parseInt(s, 16).toChar.toString
  }
  def string[_: Ctx]: P[String] = P(
    "\""./ ~~ (CharsWhile(x => x != '"' && x != '\\').! | escape).repX ~~ "\"" |
      "'"./ ~~ (CharsWhile(x => x != '\'' && x != '\\').! | escape).repX ~~ "'" |
      "@\""./ ~~ (CharsWhile(_ != '"').! | "\"\"".!.map(_ => "\"")).repX ~~ "\"" |
      "@'"./ ~~ (CharsWhile(_ != '\'').! | "''".!.map(_ => "'")).repX ~~ "'" |
      "|||"./ ~~ CharsWhile(c => c == ' ' || c == '\t', 0) ~~ "\n" ~~ tripleBarStringHead.flatMap { case (pre, w, head) =>
        tripleBarStringBody(w).map(pre ++ Seq(head, "\n") ++ _)
      } ~~ "\n" ~~ CharsWhile(c => c == ' ' || c == '\t') ~~ "|||"
  ).map(_.mkString)

  def tripleBarStringHead[_: Ctx] = P(
    (CharsWhile(c => c == ' ' || c == '\t', min=0) ~~ "\n".!).repX ~~
      CharsWhile(c => c == ' ' || c == '\t', min=1).! ~~
      CharsWhile(_ != '\n').!
  )
  def tripleBarBlank[_: Ctx] = P( "\n" ~~ CharsWhile(c => c == ' ' || c == '\t', min=0) ~~ &("\n").map(_ => "\n") )

  def tripleBarStringBody[_: Ctx](w: String) = P (
    (tripleBarBlank | "\n" ~~ w ~~ CharsWhile(_ != '\n').!.map(_ + "\n")).repX
  )

  def `null`[_: Ctx] = P(Index ~~ "null" ~~ break).map(Expr.Null)
  def `true`[_: Ctx] = P(Index ~~ "true" ~~ break).map(Expr.True)
  def `false`[_: Ctx] = P(Index ~~ "false" ~~ break).map(Expr.False)
  def `self`[_: Ctx] = P(Index ~~ "self" ~~ break).map(Expr.Self)
  def $[_: Ctx] = P(Index ~~ "$").map(Expr.$)
  def `super`[_: Ctx] = P(Index ~~ "super" ~~ break).map(Expr.Super)

  def `}`[_: Ctx] = P( "}" )
  def obj[_: Ctx]: P[Expr] = P( "{" ~/ (Index ~~ objinside).map(Expr.Obj.tupled) ~ `}` )
  def arr[_: Ctx]: P[Expr] = P(
    "[" ~/ ((Index ~~ "]").map(Expr.Arr(_, Nil)) | arrBody ~ "]")
  )
  def compSuffix[_: Ctx] = P( forspec ~ compspec ).map(Left(_))
  def arrBody[_: Ctx]: P[Expr] = P(
    Index ~~ expr ~ (compSuffix | "," ~/ (compSuffix | (expr.rep(0, sep = ",") ~ ",".?).map(Right(_)))).?
  ).map{
    case (offset, first, None) => Expr.Arr(offset, Seq(first))
    case (offset, first, Some(Left(comp))) => Expr.Comp(offset, first, comp._1, comp._2)
    case (offset, first, Some(Right(rest))) => Expr.Arr(offset, Seq(first) ++ rest)
  }
  def assertExpr[_: Ctx]: P[Expr] = P( Index ~~ assertStmt ~/ ";" ~ expr ).map(Expr.AssertExpr.tupled)
  def function[_: Ctx]: P[Expr] = P( Index ~~ "function" ~ "(" ~/ params ~ ")" ~ expr ).map(Expr.Function.tupled)
  def ifElse[_: Ctx]: P[Expr] = P( Index ~~ expr ~ "then" ~~ break ~ expr ~ ("else" ~~ break ~ expr).? ).map(Expr.IfElse.tupled)
  def localExpr[_: Ctx]: P[Expr] = P( Index ~~ bind.rep(min=1, sep = ","./) ~ ";" ~ expr ).map(Expr.LocalExpr.tupled)

  def expr[_: Ctx]: P[Expr] = P("" ~ expr1 ~ (Index ~~ binaryop ~/ expr1).rep ~ "").map{ case (pre, fs) =>
    var remaining = fs
    def climb(minPrec: Int, current: Expr): Expr = {
      var result = current
      while(
        remaining.headOption match{
          case None => false
          case Some((offset, op, next)) =>
            val prec: Int = precedence(op)
            if (prec < minPrec) false
            else{
              remaining = remaining.tail
              val rhs = climb(prec + 1, next)
              val op1 = op match{
                case "*" => Expr.BinaryOp.`*`
                case "/" => Expr.BinaryOp.`/`
                case "%" => Expr.BinaryOp.`%`
                case "+" => Expr.BinaryOp.`+`
                case "-" => Expr.BinaryOp.`-`
                case "<<" => Expr.BinaryOp.`<<`
                case ">>" => Expr.BinaryOp.`>>`
                case "<" => Expr.BinaryOp.`<`
                case ">" => Expr.BinaryOp.`>`
                case "<=" => Expr.BinaryOp.`<=`
                case ">=" => Expr.BinaryOp.`>=`
                case "in" => Expr.BinaryOp.`in`
                case "==" => Expr.BinaryOp.`==`
                case "!=" => Expr.BinaryOp.`!=`
                case "&" => Expr.BinaryOp.`&`
                case "^" => Expr.BinaryOp.`^`
                case "|" => Expr.BinaryOp.`|`
                case "&&" => Expr.BinaryOp.`&&`
                case "||" => Expr.BinaryOp.`||`
              }
              result = Expr.BinaryOp(offset, result, op1, rhs)
              true
            }
        }
      )()
      result
    }

    climb(0, pre)
  }

  def expr1[_: Ctx]: P[Expr] = P(expr2 ~ exprSuffix2.rep).map{
    case (pre, fs) => fs.foldLeft(pre){case (p, f) => f(p) }
  }

  def exprSuffix2[_: Ctx]: P[Expr => Expr] = P(
    (Index ~~ "." ~/ id).map(x => Expr.Select(x._1, _: Expr, x._2)) |
      (Index ~~ "[" ~/ expr.? ~ (":" ~ expr.?).rep ~ "]").map{
        case (offset, Some(tree), Seq()) => Expr.Lookup(offset, _: Expr, tree)
        case (offset, start, ins) => Expr.Slice(offset, _: Expr, start, ins.lift(0).flatten, ins.lift(1).flatten)
      } |
      (Index ~~ "(" ~/ args ~ ")").map(x => Expr.Apply(x._1, _: Expr, x._2)) |
      (Index ~~ "{" ~/ objinside ~ `}`).map(x => Expr.ObjExtend(x._1, _: Expr, x._2))
  )

  // Any `expr` that isn't naively left-recursive
  def expr2[_: Ctx] = P(
    `null` | `true` | `false` | `self` | $ | number |
      (Index ~~ string).map(Expr.Str.tupled) | obj | arr | `super`
      | (Index ~~ id).map(Expr.Id.tupled)
      | ("local" ~~ break  ~/ localExpr)
      | ("(" ~/ (Index ~~ expr).map(Expr.Parened.tupled) ~ ")")
      | ("if" ~~ break ~/ ifElse)
      | function
      | (Index ~~ "importstr" ~/ string).map(Expr.ImportStr.tupled)
      | (Index ~~ "import" ~/ string).map(Expr.Import.tupled)
      | (Index ~~ "error" ~~ break ~/ expr).map(Expr.Error.tupled)
      | assertExpr
      | (Index ~~ unaryop ~/ expr1).map{ case (i, k, e) =>
      def k2 = k match{
        case "+" => Expr.UnaryOp.`+`
        case "-" => Expr.UnaryOp.`-`
        case "~" => Expr.UnaryOp.`~`
        case "!" => Expr.UnaryOp.`!`
      }
      Expr.UnaryOp(i, k2, e)
    }
  )

  def objinside[_: Ctx]: P[Expr.ObjBody] = P(
    Index ~~ member.rep(sep = ",") ~ ",".? ~ (forspec ~ compspec).?
  ).map{
    case (offset, exprs, None) => Expr.ObjBody.MemberList(exprs)
    case (offset, exprs, Some(comps)) =>
      val preLocals = exprs.takeWhile(_.isInstanceOf[Expr.Member.BindStmt]).map(_.asInstanceOf[Expr.Member.BindStmt])
      val Expr.Member.Field(offset, Expr.FieldName.Dyn(lhs), false, None, Visibility.Normal, rhs) =
        exprs(preLocals.length)
      val postLocals = exprs.drop(preLocals.length+1).takeWhile(_.isInstanceOf[Expr.Member.BindStmt])
        .map(_.asInstanceOf[Expr.Member.BindStmt])
      Expr.ObjBody.ObjComp(preLocals, lhs, rhs, postLocals, comps._1, comps._2)
  }

  def member[_: Ctx]: P[Expr.Member] = P( objlocal | assertStmt | field )
  def field[_: Ctx] = P(
    (Index ~~ fieldname ~/ "+".!.? ~ ("(" ~ params ~ ")").? ~ fieldKeySep ~/ expr).map{
      case (offset, name, plus, p, h2, e) =>
        Expr.Member.Field(offset, name, plus.nonEmpty, p, h2, e)
    }
  )
  def fieldKeySep[_: Ctx] = P( ":::" | "::" | ":" ).!.map{
    case ":" => Visibility.Normal
    case "::" => Visibility.Hidden
    case ":::" => Visibility.Unhide
  }
  def objlocal[_: Ctx] = P( "local" ~~ break ~/ bind ).map(Expr.Member.BindStmt)
  def compspec[_: Ctx]: P[Seq[Expr.CompSpec]] = P( (forspec | ifspec).rep )
  def forspec[_: Ctx] = P( Index ~~ "for" ~~ break ~/ id ~ "in" ~~ break ~ expr ).map(Expr.ForSpec.tupled)
  def ifspec[_: Ctx] = P( Index ~~ "if" ~~ break  ~/ expr ).map(Expr.IfSpec.tupled)
  def fieldname[_: Ctx] = P( id.map(Expr.FieldName.Fixed) | string.map(Expr.FieldName.Fixed) | "[" ~ expr.map(Expr.FieldName.Dyn) ~ "]" )
  def assertStmt[_: Ctx] = P( "assert" ~~ break  ~/ expr ~ (":" ~ expr).? ).map(Expr.Member.AssertStmt.tupled)
  def bind[_: Ctx] = P( Index ~~ id ~ ("(" ~/ params.? ~ ")").?.map(_.flatten) ~ "=" ~ expr ).map(Expr.Bind.tupled)
  def args[_: Ctx] = P( ((id ~ "=").? ~ expr).rep(sep = ",") ~ ",".? ).flatMap{x =>
    if (x.sliding(2).exists{case Seq(l, r) => l._1.isDefined && r._1.isEmpty case _ => false}) {
      Fail
    } else Pass.map(_ => Expr.Args(x))


  }

  def params[_: Ctx]: P[Expr.Params] = P( (id ~ ("=" ~ expr).?).rep(sep = ",") ~ ",".? ).flatMap{x =>
    val seen = collection.mutable.Set.empty[String]
    var overlap: String = null
    for((k, v) <- x){
      if (seen(k)) overlap = k
      else seen.add(k)
    }
    if (overlap == null) Pass.map(_ => Expr.Params(x))
    else Fail

  }

  def binaryop[_: Ctx] = P(
    "<<" | ">>" | "<=" | ">=" | "in" | "==" | "!=" | "&&" | "||" |
    "*" | "/" | "%" | "+" | "-" | "<" | ">" | "&" | "^" | "|"
  ).!

  def unaryop[_: Ctx]	= P( "-" | "+" | "!" | "~").!


  def document[_: Ctx] = P( expr ~ End )
}