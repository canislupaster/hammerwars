package win.hammerwars

import gg.jte.TemplateEngine
import gg.jte.resolve.DirectoryCodeResolver
import io.jooby.*
import io.jooby.exception.NotFoundException
import io.jooby.exception.StatusCodeException
import io.jooby.handler.AssetSource
import io.jooby.jte.JteModule
import io.jooby.kt.*
import io.jooby.netty.NettyServer
import java.nio.file.Path

fun main(args: Array<String>) {
    fun exErr(ex: Throwable) = when (ex) {
        is WebError -> ex
        is NotFoundException, StatusCodeException(StatusCode.NOT_FOUND) ->
            WebError(WebErrorType.NotFound, "Maybe, in time, new ground will be broken. But not for a long while.")
        else -> WebError(WebErrorType.Other, ex.message)
    }

    runApp(args) {
        install(NettyServer())

        val isDev = environment.getProperty("dev")?.lowercase()=="true"

        val dir = System.getProperty("user.dir")!!
        val codeResolver = DirectoryCodeResolver(Path.of(dir, "src/main/templates"), )

        val jte = JteModule(
            if (isDev) TemplateEngine.create(codeResolver, Path.of(dir, "jte-classes-dev"), gg.jte.ContentType.Html)
            else TemplateEngine.createPrecompiled(Path.of(dir, "jte-classes"), gg.jte.ContentType.Html))

        install(jte)

        val db = DB(dir, environment)

        val auth = Auth(environment.getProperty("root")!!,
            environment.getProperty("msalClientId")!!,
            environment.getProperty("msalClientSecret")!!, db)

        suspend fun getSession(ctx: Context, admin: Boolean=false): DB.SessionDB {
            val (a,b) = runCatching {
                ctx.cookie("SESSION").value() to ctx.cookie("TOKEN").value()
            }.getOrElse { throw WebErrorType.Unauthorized.err("No session cookie found") }

            val ses = db.auth(a,b)
            if (admin) ses.checkAdmin()
            return ses
        }

        assets("/**", AssetSource.create(Path.of(dir, "src/main/static")), {
              throw NotFoundException("Asset not found")
        })

        coroutine {
            get("/") {
                val loggedIn = runCatching { getSession(ctx) }.isSuccess
                ModelAndView("index.kte", mapOf(
                    "loggedIn" to loggedIn,
                    "open" to db.hasOpened(),
                    "opens" to db.open
                ))
            }

            post("/login") {
                val nonce = db.genKey()
                var cookies = mutableMapOf<String, String>()
                val state = runCatching {
                    getSession(ctx).apply {detachUser() }.state
                }.getOrElse {
                    val makeSes = db.makeSession()

                    cookies["SESSION"] = makeSes.id
                    cookies["TOKEN"] = makeSes.key

                    makeSes.state
                }

                cookies["NONCE"] = nonce
                for (cookie in cookies)
                    ctx.setResponseCookie(Cookie(cookie.key, cookie.value).apply {
                        isHttpOnly = true
                        isSecure = true
                    })

                ctx.sendRedirect(auth.redir(state, nonce))
            }

            post("/auth") {
                ctx.setResponseCookie(Cookie("NONCE", null))

                val ses = getSession(ctx)

                val data = ctx.formMap()
                val code = data["code"] ?: throw WebErrorType.BadRequest.err("No code")
                val state = data["state"] ?: throw WebErrorType.BadRequest.err("No state")
                val nonce = ctx.cookieMap()["NONCE"] ?: throw WebErrorType.BadRequest.err("No nonce")

                auth.auth(code, ses, nonce, state)
                ctx.sendRedirect("/register")
            }

            fun renderRegister(sd: DB.SavedData, err: WebError?=null, success: String?=null) =
                ModelAndView("register.kte", mapOf(
                    "email" to sd.email,
                    "data" to sd.saved,
                    "defaultGrid" to DEFAULT_GRID,
                    "submitted" to sd.submitted,
                    "err" to err, "success" to success
                ))

            get("/logout") {
                getSession(ctx).remove()

                listOf("SESSION", "TOKEN", "NONCE").forEach {
                    ctx.setResponseCookie(Cookie(it, null))
                }

                ctx.sendRedirect("/")
            }

            routes {
                fun wrap(proceed: Boolean=false, f: suspend HandlerContext.(DB.SessionDB) -> String?): suspend HandlerContext.() -> Any = lambda@{
                    val ses = getSession(ctx)

                    if (ses.isClosed()) {
                        val success = if (proceed) f(ses) else null
                        renderRegister(ses.getSavedData(), success=success,
                            err=WebError(WebErrorType.RegistrationClosed))
                    } else try {
                        val x = f(ses)
                        renderRegister(ses.getSavedData(), success=x)
                    } catch (e: Throwable) {
                        renderRegister(ses.getSavedData(), err=exErr(e))
                    }
                }

                get("/register", wrap { null })

                post("/register", wrap { ses ->
                    val dat = dataFromForm(ctx.formMap())
                    val complete = dat.isComplete()
                    ses.setData(dat, submit=complete)

                    if (!complete)
                        throw WebErrorType.FormError.err("Please fill out required fields before submitting.")

                    "Submitted"
                })

                post("/save", wrap(proceed=true) { ses ->
                    val dat = dataFromForm(ctx.formMap())
                    ses.setData(dat)
                    "Saved"
                })

                post("/unsubmit", wrap(proceed=true) { ses ->
                    val dat = dataFromForm(ctx.formMap())
                    ses.setData(dat, unsubmit=true)
                    "Unsubmitted"
                })

                path("/dashboard") {
                    get("/") {
                        getSession(ctx, true)

                        ModelAndView("dash.kte", mapOf(
                            "dash" to db.getDashboard()
                        ))
                    }

                    post("/close") {
                        getSession(ctx, true)
                        db.setProp("closed", "true")
                        ctx.sendRedirect("/dashboard")
                    }

                    post("/open") {
                        getSession(ctx, true)
                        db.setProp("closed", "false")
                        ctx.sendRedirect("/dashboard")
                    }
                }
            }
        }

        error { ctx, cause, code ->
            if (cause is WebError) ctx.setResponseCode(cause.ty.code())
            else ctx.setResponseCode(code)

            log.error("Request error", cause)

            ctx.render(ModelAndView("error.kte", mapOf("err" to exErr(cause))))
        }

        before {
            for (s in listOf("SESSION", "TOKEN")) {
                ctx.cookie(s)?.let {
                    ctx.setResponseCookie(Cookie(s, it.value()).apply {
                        isHttpOnly = true
                        isSecure = true
                    })
                }
            }
        }
    }
}