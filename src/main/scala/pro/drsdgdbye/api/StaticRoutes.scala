package pro.drsdgdbye.api

import zio.http.codec.PathCodec
import zio.http.{Handler, Headers, Method, Middleware, Path, Response, Route, Routes}

/** Serves the frontend: the entry page at `/` plus the bundled static resources. */
object StaticRoutes:

  private val indexRoute: Route[Any, Response] =
    Method.GET / PathCodec.empty -> Handler.fromResource("static/index.html").mapError(_ => Response.notFound)

  /** Static responses are served with `Cache-Control: no-cache` so browsers always revalidate the frontend bundles. */
  private val noCache: Middleware[Any] =
    Middleware.updateResponse(_.updateHeaders(_ ++ Headers("Cache-Control", "no-cache")))

  val routes: Routes[Any, Response] =
    Routes(indexRoute) @@ Middleware.serveResources(Path.empty, "static") @@ noCache
