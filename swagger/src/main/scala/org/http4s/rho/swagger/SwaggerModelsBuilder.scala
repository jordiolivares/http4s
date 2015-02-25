package org.http4s
package rho
package swagger

import org.http4s.rho.bits.HeaderAST.HeaderRule
import org.http4s.rho.bits.PathAST._
import org.http4s.rho.bits.QueryAST.{QueryCapture, QueryRule}
import org.http4s.rho.bits.ResponseGenerator.EmptyRe
import org.http4s.rho.bits._

import scala.reflect.runtime.universe.Type

import scalaz._, Scalaz._

class SwaggerModelsBuilder(formats: SwaggerFormats) {
  import models._

  def mkSwagger(info: Info, ra: RhoAction[_, _])(os: Option[Swagger]): Swagger =
    Swagger(
      info        = info,
      paths       = collectPaths(ra)(os),
      definitions = collectDefinitions(ra)(os))

  def collectPaths(ra: RhoAction[_, _])(os: Option[Swagger]): Map[String, Path] = {
    val paths = os.map(_.paths).getOrElse(Map.empty)
    val pairs = mkPathStrs(ra).map { ps =>
      val o = mkOperation(ps, ra)
      val p0 = paths.get(ps).getOrElse(Path())
      val p1 = ra.method.name.toLowerCase match {
        case "get"     => p0.copy(get = o.some)
        case "put"     => p0.copy(put = o.some)
        case "post"    => p0.copy(post = o.some)
        case "delete"  => p0.copy(delete = o.some)
        case "patch"   => p0.copy(patch = o.some)
        case "options" => p0.copy(options = o.some)
      }
      ps -> p1
    }
    pairs.foldLeft(paths) { case (paths, (s, p)) => paths.alter(s)(_ => p.some) }
  }

  def collectDefinitions(ra: RhoAction[_, _])(os: Option[Swagger]): Map[String, Model] = {
    val initial: Set[Model] = os.map(_.definitions.values.toSet).getOrElse(Set.empty[Model])
    (collectResultTypes(ra) ++ collectCodecTypes(ra))
      .foldLeft(initial)((s, tpe) => s ++ TypeBuilder.collectModels(tpe, s, formats))
      .map(m => m.id.split("\\.").last -> m)
      .toMap
  }

  def collectResultTypes(ra: RhoAction[_, _]): Set[Type] =
    ra.resultInfo.collect {
      case TypeOnly(tpe)         => tpe
      case StatusAndType(_, tpe) => tpe
    }

  def collectCodecTypes(ra: RhoAction[_, _]): Set[Type] =
    ra.router match {
      case r: CodecRouter[_, _] => Set(r.entityType)
      case _                    => Set.empty
    }

  def mkPathStrs(ra: RhoAction[_, _]): List[String] = {

    def go(stack: List[PathOperation], pathStr: String): String =
      stack match {
        case Nil                       => pathStr.isEmpty.fold("/", pathStr)
        case PathMatch("")::xs         => go(xs, pathStr)
        case PathMatch(s)::xs          => go(xs, pathStr + "/" + s)
        case MetaCons(_, _)::xs        => go(xs, pathStr)
        case PathCapture(id, p, _)::xs => go(xs, s"$pathStr/{$id}")
        case CaptureTail::xs           => pathStr + "/{tail...}"
      }

    linearizeStack(ra.path::Nil).map(go(_, ""))
  }

  def collectPathParams(ra: RhoAction[_, _]): List[PathParameter] = {

    def go(stack: List[PathOperation], pps: List[PathParameter]): List[PathParameter] =
      stack match {
        case Nil                       => pps
        case PathMatch("")::xs         => go(xs, pps)
        case PathMatch(s)::xs          => go(xs, pps)
        case MetaCons(_, _)::xs        => go(xs, pps)
        case PathCapture(id, p, _)::xs => go(xs, mkPathParam(id, p)::pps)
        case CaptureTail::xs           => PathParameter(`type` = "string", name = "tail...".some):: Nil
      }

    linearizeStack(ra.path::Nil).map(go(_, Nil)).flatten
  }

  def collectBodyParams(ra: RhoAction[_, _]): Option[BodyParameter] =
    ra.router match {
      case r: CodecRouter[_, _] => mkBodyParam(r).some
      case _                    => none
    }  

  def collectResponses(ra: RhoAction[_, _]): Map[String, Response] =
    ra.resultInfo.collect {
      case TypeOnly(tpe)         => mkResponse("200", "OK", tpe.some).some
      case StatusAndType(s, tpe) => mkResponse(s.code.toString, s.reason, tpe.some).some
      case StatusOnly(s)         => mkResponse(s.code.toString, s.reason, none).some
      case Empty                 => none
    }.flatten.toMap

  def collectSummary(ra: RhoAction[_, _]): Option[String] = {

    def go(stack: List[PathOperation], summary: Option[String]): Option[String] =
      stack match {
        case PathMatch("")::Nil             => go(Nil, summary)
        case PathMatch(s)::xs               => go(xs, summary)
        case PathCapture(id, parser, _)::xs => go(xs, summary)
        case CaptureTail::xs                => summary

        case MetaCons(_, meta)::xs =>
          meta match {
            case RouteDesc(meta) => meta.some
            case _               => go(xs, summary)
          }

        case Nil => summary
      }

    linearizeStack(ra.path::Nil).flatMap(go(_, None)).headOption
  }

  def collectOperationParams(ra: RhoAction[_, _]): List[Parameter] =
    collectPathParams(ra) ::: collectQueryParams(ra) ::: collectHeaderParams(ra) ::: collectBodyParams(ra).toList

  def collectQueryParams(ra: RhoAction[_, _]): List[QueryParameter] = {
    import bits.QueryAST._

    def go(stack: List[QueryRule]): List[QueryParameter] =
      stack match {
        case QueryAnd(a, b)::xs => go(a::b::xs)
        case EmptyQuery::xs     => go(xs)

        case QueryOr(a, b)::xs =>
          val as = go(a::xs)
          val bs = go(b::xs)
          val set: (QueryParameter, String) => QueryParameter =
            (p, s) => p.copy(description = p.description.map(_ + s).orElse(s.some))

          addOrDescriptions(set)(as, bs, "params") :::
          addOrDescriptions(set)(bs, as, "params")

        case (q @ QueryCapture(_, _, _, _))::xs => mkQueryParam(q)::go(xs)

        case MetaCons(q @ QueryCapture(_, _, _, _), meta)::xs =>
          meta match {
            case m: TextMetaData => mkQueryParam(q).copy(description = m.msg.some) :: go(xs)
            case _               => go(q::xs)
          }

        case MetaCons(a, _)::xs => go(a::xs)

        case Nil => Nil
      }

    go(ra.query::Nil)
  }

  def collectHeaderParams(ra: RhoAction[_, _]): List[HeaderParameter] = {
    import bits.HeaderAST._

    def go(stack: List[HeaderRule]): List[HeaderParameter] =
      stack match {
        case HeaderAnd(a, b)::xs        => go(a::b::xs)
        case MetaCons(a, _)::xs         => go(a::xs)
        case EmptyHeaderRule::xs        => go(xs)
        case HeaderCapture(key)::xs     => mkHeaderParam(key)::go(xs)
        case HeaderMapper(key, _)::xs   => mkHeaderParam(key)::go(xs)
        case HeaderRequire(key, _)::xs  => mkHeaderParam(key)::go(xs)

        case HeaderOr(a, b)::xs         =>
          val as = go(a::xs)
          val bs = go(b::xs)
          val set: (HeaderParameter, String) => HeaderParameter =
            (p, s) => p.copy(description = p.description.map(_ + s).orElse(s.some))
          addOrDescriptions(set)(as, bs, "headers") :::
          addOrDescriptions(set)(bs, as, "headers")

        case Nil                        => Nil
      }

    go(ra.headers::Nil)
  }

  def mkOperation(pathStr: String, ra: RhoAction[_, _]): Operation =
    Operation(
      tags        = pathStr.split("/").filterNot(_ == "").headOption.getOrElse("/") :: Nil,
      summary     = collectSummary(ra),
      consumes    = ra.validMedia.toList.map(_.renderString),
      produces    = ra.responseEncodings.toList.map(_.renderString),
      operationId = mkOperationId(pathStr, ra.method).some,
      parameters  = collectOperationParams(ra),
      responses   = collectResponses(ra))

  def mkOperationId(path: String, method: Method): String = {
    method.toString.toLowerCase +
    path.split("/")
      .filter(s => !s.isEmpty && !(s.startsWith("{") && s.endsWith("}")))
      .map(_.capitalize)
      .mkString
  }

  def mkBodyParam(r: CodecRouter[_, _]): BodyParameter = {
    val tpe = r.entityType
    BodyParameter(
      schema      = RefModel(tpe.fullName, tpe.simpleName).some,
      name        = "body".some,
      description = tpe.simpleName.some)
  }

  def mkPathParam(name: String, parser: StringParser[_]): PathParameter = {
    val tpe = parser.typeTag.map(tag => getType(tag.tpe)).getOrElse("string")
    PathParameter(`type` = tpe, name = name.some, required = true)
  }

  def mkResponse(code: String, descr: String, tpe: Option[Type]): (String, Response) =
    code -> Response(description = descr, schema = tpe.map(t => RefProperty(ref = t.simpleName)))

  def mkQueryParam(rule: QueryCapture[_]): QueryParameter =
    QueryParameter(
      `type`       = getType(rule.m.tpe),
      name         = rule.name.some,
      required     = rule.default.isEmpty,
      defaultValue = rule.default.map(_.toString))

  def mkHeaderParam(key: HeaderKey.Extractable): HeaderParameter =
    HeaderParameter(
      `type`   = "string",
      name     = key.name.toString.some,
      required = true)

  def linearizeStack(stack: List[PathRule]): List[List[PathOperation]] = {

    def go(stack: List[PathRule], acc: List[PathOperation]): List[List[PathOperation]] =
      stack match {
        case PathOr(a, b)::xs           => go(a::xs, acc):::go(b::xs, acc)
        case PathAnd(a, b) :: xs        => go(a::b::xs, acc)
        case (m@ MetaCons(a, meta))::xs => go(a::xs, m::acc)
        case (op: PathOperation)::xs    => go(xs, op::acc)
        case Nil                        => acc::Nil
      }

    go(stack, Nil).map(_.reverse)
  }

  def addParamToPath(path: Path, param: Parameter): Path =
    path.copy(parameters = param :: path.parameters)

  def addOrDescriptions[A <: Parameter](set: (A, String) => A)(as: List[A], bs: List[A], tpe: String): List[A] =
    if (bs.isEmpty) as
    else if (as.isEmpty) bs
    else
      as.map(set(_, s"Optional if the following $tpe are satisfied: " + bs.flatMap(_.name).mkString("[",", ", "]")))

  def getType(m: Type): String =
    TypeBuilder.DataType(m).name
}
