package pro.drsdgdbye.api

import zio.http.codec.PathCodec
import zio.http.{Handler, Method, Middleware, Path, Response, Route, Routes}

/** Serves the frontend: the entry page at `/` plus the bundled static resources. */
object StaticRoutes:

  private val indexRoute: Route[Any, Response] =
    Method.GET / PathCodec.empty -> Handler.fromResource("static/index.html").mapError(_ => Response.notFound)

  val routes: Routes[Any, Response] =
    Routes(indexRoute) @@ Middleware.serveResources(Path.empty, "static")
