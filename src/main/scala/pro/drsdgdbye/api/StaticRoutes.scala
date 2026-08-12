package pro.drsdgdbye.api

import java.io.FileNotFoundException

import zio.http.codec.PathCodec
import zio.http.{Handler, Headers, Method, Middleware, Path, Request, Response, Route, Routes}
import zio.{Cause, ZIO}

/** Serves the frontend: the entry page at `/` plus the bundled static resources. */
object StaticRoutes:

  private val indexRoute: Route[Any, Response] =
    Method.GET / PathCodec.empty -> Handler.fromResource("static/index.html").mapError(_ => Response.notFound)

  /** Static responses are served with `Cache-Control: no-cache` so browsers always revalidate the frontend bundles. */
  private val noCache: Middleware[Any] =
    Middleware.updateResponse(_.updateHeaders(_ ++ Headers("Cache-Control", "no-cache")))

  /** Mirrors zio-http's own `serveResources` guard against path-traversal segments. */
  private def isFishy(path: Path): Boolean =
    path.segments.exists(segment => segment.contains('/') || segment.contains('\\') || segment == "..")

  /** Serves one static resource: a missing file becomes a plain 404, real errors stay observable and logged. */
  private def fromStaticResource(file: String): Handler[Any, Response, Request, Response] =
    Handler.fromResource(s"static/$file").catchAll {
      case _: FileNotFoundException => Handler.notFound
      case other =>
        Handler.fromZIO(
          ZIO.logErrorCause("Failed to serve static resource", Cause.fail(other)) *> ZIO.succeed(
            Response.internalServerError
          )
        )
    }

  private val filesRoute: Route[Any, Response] =
    Method.GET / PathCodec.trailing -> Handler.identity[Request].flatMap { request =>
      val path = request.path
      if isFishy(path) then Handler.badRequest
      else fromStaticResource(path.dropLeadingSlash.encode)
    }

  val routes: Routes[Any, Response] =
    Routes(indexRoute, filesRoute) @@ noCache
