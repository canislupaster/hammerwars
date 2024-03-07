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
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URL
import java.nio.file.Path

@Serializable
class TeamData(val name: String, val pts: Int, val verdict: Verdict?)
@Serializable
class Leaderboard(val teams: List<TeamData>, val curRound: Int?, val running: Boolean)

enum class TeamAction {
    Accept, Reject, RemoveFromGame
}

suspend fun main(args: Array<String>) = coroutineScope {
    fun exErr(ex: Throwable) = when (ex) {
        is WebError -> ex
        is NotFoundException, StatusCodeException(StatusCode.NOT_FOUND) ->
            WebError(WebErrorType.NotFound, "Maybe, in time, new ground will be broken. But not for a long while.")
        else -> WebError(WebErrorType.Other, ex.message)
    }

    val game = Game()
    launch { game.start() }

    val teamId = mutableMapOf<String, Team>()
    val teamIdLock = Mutex()

    val listeners = mutableSetOf<WebSocket>()
    val listenersLock = Mutex()

    runApp(args) {
        install(NettyServer())

        val isDev = environment.getProperty("dev")?.lowercase()=="true"
        val isolateArgs = if (isDev) listOf() else environment.config.getStringList("isolateArgs")

        val dir = System.getProperty("user.dir")!!
        val codeResolver = DirectoryCodeResolver(Path.of(dir, "src/main/templates"))

        val jte = JteModule(
            if (isDev) TemplateEngine.create(codeResolver, Path.of(dir, "jte-classes-dev"), gg.jte.ContentType.Html)
            else TemplateEngine.createPrecompiled(Path.of(dir, "jte-classes"), gg.jte.ContentType.Html))

        install(jte)

        val db = DB(dir, environment)

        val root = environment.getProperty("root")!!
        val auth = Auth(root, environment.getProperty("msalClientId")!!,
            environment.getProperty("msalClientSecret")!!, db)

        val wsUrl =
            URI(if (isDev) "ws" else "wss", URI(root).authority,
                "/game/ws", null,null).toString()

        suspend fun getSession(ctx: Context, admin: Boolean=false): DB.SessionDB {
            val (a,b) = runCatching {
                ctx.cookie("SESSION").value() to ctx.cookie("TOKEN").value()
            }.getOrElse { WebErrorType.Unauthorized.err("No session cookie found") }

            val ses = db.auth(a,b)
            if (admin) ses.checkAdmin()
            return ses
        }

        suspend fun getTeamId(ctx: Context): String? =
            when (val ses = getSession(ctx)) {
                null -> if (db.inProgress()) null
                    else WebErrorType.NotFound.err("Game not in progress")
                else -> {
                    if (!db.inProgress()) ses.checkAdmin()
                    runCatching {ses.getTeam()}.getOrNull()
                }
            }

        suspend fun getTeamIdAssert(ctx: Context): String =
            getTeamId(ctx) ?: WebErrorType.Unauthorized.err("You're not in a team")

        suspend fun getLeaderboard(): Leaderboard = game.lock.withLock {
            val teams = teamIdLock.withLock {
                teamId.mapNotNull { (id, t) ->
                    game.teams[t.id]?.let {
                        TeamData(db.getTeamName(id), it.pts, t.verdict()?.v)
                    }
                }
            }

            Leaderboard(teams, game.curRound, game.running)
        }

        assets("/**", AssetSource.create(Path.of(dir, "src/main/static")), {
              throw NotFoundException("Asset not found")
        })

        coroutine {
            launch {
                while (true) {
                    game.roundFinished.receive()
                    val s = Json.encodeToString(getLeaderboard())
                    listenersLock.withLock {
                        for (l in listeners) launch(Dispatchers.IO) { l.send(s) }
                    }
                }
            }

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
                val cookies = mutableMapOf<String, String>()
                runCatching { getSession(ctx).remove() }

                val makeSes = db.makeSession()

                cookies["SESSION"] = makeSes.id
                cookies["TOKEN"] = makeSes.key
                cookies["NONCE"] = nonce

                for (cookie in cookies)
                    ctx.setResponseCookie(Cookie(cookie.key, cookie.value).apply {
                        isHttpOnly = true
                        isSecure = true
                    })

                ctx.sendRedirect(auth.redir(makeSes.state, nonce))
            }

            if (isDev) get("/testauth") {
                val ses = getSession(ctx)
                ses.withEmail(ctx.query("email").value(), "Test User")
                ctx.sendRedirect("/register")
            }

            post("/auth") {
                ctx.setResponseCookie(Cookie("NONCE", null))

                val ses = getSession(ctx)

                val data = ctx.formMap()
                val code = data["code"] ?: WebErrorType.BadRequest.err("No code")
                val state = data["state"] ?: WebErrorType.BadRequest.err("No state")
                val nonce = ctx.cookieMap()["NONCE"] ?: WebErrorType.BadRequest.err("No nonce")

                auth.auth(code, ses, nonce, state)
                ctx.sendRedirect("/register")
            }

            fun renderRegister(sd: DB.SavedData, err: WebError?=null, success: String?=null) =
                ModelAndView("register.kte", mapOf(
                    "email" to sd.email,
                    "data" to sd.saved,
                    "defaultGrid" to DEFAULT_GRID,
                    "submitted" to sd.submitted, "teamCode" to sd.teamCode,
                    "teamName" to sd.teamName,
                    "teamWith" to sd.teamWith, "accepted" to sd.accepted,
                    "err" to err, "success" to success
                ))

            get("/logout") {
                getSession(ctx).remove()

                listOf("SESSION", "TOKEN", "NONCE").forEach {
                    ctx.setResponseCookie(Cookie(it, null))
                }

                ctx.sendRedirect("/")
            }

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
                    WebErrorType.FormError.err("Please fill out required fields before submitting.")

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

            post("/join", wrap { ses ->
                ses.joinTeam(ctx.form("teamCode").value())
                "Joined"
            })

            post("/leave", wrap(proceed=true) { ses ->
                ses.leaveTeam()
                "Left team"
            })

            post("/changename", wrap { ses ->
                ses.setTeamName(ctx.form("name").value())
                "Team name changed"
            })

            path("/game") {
                suspend fun renderGame(team: Team?, tid: String?) =
                    ModelAndView("game.kte", mapOf(
                        "team" to team, "teamName" to tid?.let {db.getTeamName(it)},
                        "verdict" to team?.verdict(),
                        "wsUrl" to wsUrl
                    ))

                get("/") {
                    val tid = getTeamId(ctx)
                    renderGame(tid?.let {teamIdLock.withLock { teamId[it] }}, tid)
                }

                post("/submit") {
                    val t = getTeamIdAssert(ctx)

                    val lang = Language.fromExt[ctx.form("language").value()]
                        ?: WebErrorType.BadRequest.err("Invalid language")
                    val code = ctx.form("code").value()

                    val newt = game.addTeam(lang,code,isDev,isolateArgs)
                    teamIdLock.withLock { teamId.put(t, newt) }?.let {game.removeTeam(it)}
                    renderGame(newt, t)
                }

                post("/unsubmit") {
                    val t = getTeamIdAssert(ctx)

                    when (val x=teamIdLock.withLock { teamId.remove(t) }) {
                        null -> WebErrorType.NotFound.err("Your team hasn't submitted anything")
                        else -> {
                            game.removeTeam(x)
                            renderGame(null, t)
                        }
                    }
                }

                ws("/ws") {
                    configurer.onConnect {
                        runBlocking {
                            listenersLock.withLock { listeners.add(it) }
                        }

                        launch {
                            Json.encodeToString(getLeaderboard()).let { s->
                                launch(Dispatchers.IO) { it.send(s) }
                            }
                        }
                    }

                    configurer.onClose { ws, _ ->
                        runBlocking {
                            listenersLock.withLock {
                                listeners.remove(ws)
                            }
                        }
                    }
                }
            }

            path("/dashboard") {
                get("/") {
                    getSession(ctx, true)

                    ModelAndView("dash.kte", mapOf(
                        "dash" to db.getDashboard()
                    ))
                }

                fun prop(name: String, value: String): suspend HandlerContext.() -> Any = {
                    getSession(ctx, true)
                    db.setProp(name,value)
                    ctx.sendRedirect("/dashboard")
                }

                post("/close", prop("closed", "true"))
                post("/open", prop("closed", "false"))
                post("/lock", prop("inProgress", "true"))
                post("/unlock", prop("inProgress", "false"))

                fun setTeam(act: TeamAction): suspend HandlerContext.() -> Any = {
                    getSession(ctx, true)
                    val teams = ctx.form("teams").value().trim()
                        .split("\n").map {it.trim()}

                    when (act) {
                        TeamAction.Accept -> db.setTeamsAccepted(teams, true)
                        TeamAction.Reject -> db.setTeamsAccepted(teams, false)
                        TeamAction.RemoveFromGame -> {
                            for (t in teams){
                                teamIdLock.withLock { teamId.remove(t) }?.let {
                                    game.removeTeam(it)
                                }
                            }
                        }
                    }

                    ctx.sendRedirect("/dashboard")
                }

                post("/accept", setTeam(TeamAction.Accept))
                post("/reject", setTeam(TeamAction.Reject))
                post("/removefromgame", setTeam(TeamAction.RemoveFromGame))

                post("/run") {
                    getSession(ctx, true)
                    game.changes.send(RunGame)
                    ctx.sendRedirect("/dashboard")
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
                ctx.cookie(s).valueOrNull()?.let {
                    ctx.setResponseCookie(Cookie(s, it).apply {
                        isHttpOnly = true
                        isSecure = true
                    })
                }
            }
        }
    }
}