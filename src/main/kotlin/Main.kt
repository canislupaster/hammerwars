package win.hammerwars

import gg.jte.TemplateEngine
import gg.jte.resolve.DirectoryCodeResolver
import io.jooby.*
import io.jooby.exception.NotFoundException
import io.jooby.exception.StatusCodeException
import io.jooby.handler.Asset
import io.jooby.handler.AssetSource
import io.jooby.jte.JteModule
import io.jooby.kt.*
import io.jooby.netty.NettyServer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
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

    val gameListeners = mutableSetOf<WebSocket>()
    val scoreboardListeners = mutableSetOf<WebSocket>()
    val listenersLock = Mutex()

    val httpClient = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) }
    }

    runApp(args) {
        install(NettyServer())

        val isDev = environment.getProperty("dev")?.lowercase()=="true"
        val isolateArgs = if (isDev) emptyList() else environment.config.getStringList("isolateArgs")

        val dir = System.getProperty("user.dir")!!
        val codeResolver = DirectoryCodeResolver(Path.of(dir, "src/main/templates"))

        val jte = JteModule(
            if (isDev) TemplateEngine.create(codeResolver, Path.of(dir, "jte-classes-dev"), gg.jte.ContentType.Html)
            else TemplateEngine.createPrecompiled(Path.of(dir, "jte-classes"), gg.jte.ContentType.Html))

        install(jte)

        val db = DB(dir, environment)

        val scoreboard = Scoreboard(db, game, environment, httpClient, log)

        val root = environment.getProperty("root")!!
        val (p1,p2,p3,p4) = listOf("msalClientId", "msalClientSecret", "discordClientId", "discordClientSecret")
            .map {environment.getProperty(it) ?: throw IllegalArgumentException("Missing $it")}
        val auth = Auth(root,db,log,httpClient,p1,p2,p3,p4)

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

        suspend fun getTeamId(ctx: Context): Int? =
            runCatching {
                getSession(ctx)
            }.map {ses->
                if (!db.inProgress()) ses.checkAdmin()
                runCatching {ses.getTeam()}.getOrNull()
            }.getOrElse {
                if (db.inProgress()) null
                else WebErrorType.NotFound.err("Game not in progress")
            }

        suspend fun getTeamIdAssert(ctx: Context): Int =
            getTeamId(ctx) ?: WebErrorType.Unauthorized.err("You're not in a team")

        suspend fun getLeaderboard(): Leaderboard = game.lock.withLock {
            val teams = game.teams.mapNotNull { (id, x) ->
                TeamData(db.getTeamName(id), x.pts, x.t.verdict()?.v)
            }

            Leaderboard(teams, game.curRound, game.running)
        }

        fun Router.mountDir(path: String) {
            assets("/**", AssetSource.create(Path.of(dir, path)), {
                throw NotFoundException("Asset not found")
            })
        }

        mountDir("src/main/static")

        coroutine {
            launch {
                scoreboard.start(this)
            }

            launch {
                game.flow.collect {
                    val s = Json.encodeToString(getLeaderboard())
                    listenersLock.withLock {
                        for (l in gameListeners) launch(Dispatchers.IO) { l.send(s) }
                    }
                }
            }

            launch {
                scoreboard.flow.collect {
                    val s = Json { classDiscriminatorMode=ClassDiscriminatorMode.NONE }
                        .encodeToString(it)
                    listenersLock.withLock {
                        for (l in scoreboardListeners) launch(Dispatchers.IO) { l.send(s) }
                    }
                }
            }

            fun wsInit(listeners: MutableSet<WebSocket>, init: suspend WebSocketInitContext.() -> String): WebSocketInitContext.() -> Unit = {
                configurer.onConnect {sock->
                    launch {
                        runCatching {
                            listenersLock.withLock {
                                init().let { s-> launch(Dispatchers.IO) { sock.send(s) } }
                                listeners.add(sock)
                            }
                        }.onFailure {
                            val x = Json.encodeToString(buildJsonObject {
                                put("type", "error")
                                put("message", it.message)
                            })

                            launch(Dispatchers.IO) { sock.send(x) }
                        }
                    }
                }

                configurer.onClose { ws, _ ->
                    runBlocking {
                        listenersLock.withLock { listeners.remove(ws) }
                    }
                }

            }

            get("/") {
                val loggedIn = runCatching { getSession(ctx).suid }.getOrNull()!=null
                ModelAndView("index.kte", mapOf(
                    "loggedIn" to loggedIn,
                    "open" to db.hasOpened(),
                    "opens" to db.open
                ))
            }

            suspend fun HandlerContext.initLogin(discord: Boolean): Pair<DB.MakeSession,String> {
                val nonce = db.genKey()
                val cookies = mutableMapOf<String, String>()
                runCatching { getSession(ctx).remove() }

                val makeSes = db.makeSession(discord)

                cookies["SESSION"] = makeSes.id
                cookies["TOKEN"] = makeSes.key
                cookies["NONCE"] = nonce

                for (cookie in cookies)
                    ctx.setResponseCookie(Cookie(cookie.key, cookie.value).apply {
                        isHttpOnly = true
                        isSecure = true
                    })

                return makeSes to nonce
            }

            post("/login") {
                initLogin(false).let { (makeSes, nonce) ->
                    ctx.sendRedirect(auth.redir(makeSes.state, nonce))
                }
            }

            post("/logindiscord") {
                initLogin(true).let { (makeSes, nonce) -> //ignore nonce :clown:
                    ctx.sendRedirect(auth.redirDiscord(makeSes.state))
                }
            }

            if (isDev) get("/testauth") {
                //uh i didnt design for this lmfao
                val ses = initLogin(false).first
                db.auth(ses.id, ses.key)
                    .withEmail(ctx.query("email").value(), "Test User", null, null)
                ctx.sendRedirect("/register")
            }

            suspend fun HandlerContext.sescodestate(form: Boolean): Triple<DB.SessionDB, String, String> {
                ctx.setResponseCookie(Cookie("NONCE", null))

                val ses = getSession(ctx)
                val data = if (form) ctx.formMap() else ctx.queryMap()
                val code = data["code"] ?: WebErrorType.BadRequest.err("No code")
                val state = data["state"] ?: WebErrorType.BadRequest.err("No state")
                return Triple(ses, code, state)
            }

            post("/auth") {
                val (ses,code,state) = sescodestate(true)
                val nonce = ctx.cookieMap()["NONCE"] ?: WebErrorType.BadRequest.err("No nonce")

                auth.auth(code, ses, nonce, state)
                ctx.sendRedirect("/register")
            }

            get("/authdiscord") {
                val (ses,code,state) = sescodestate(false)
                auth.discordAuth(ses, code, state)
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

            fun wrap(proceed: Boolean=false, f: suspend HandlerContext.(DB.SessionDB) -> String?)
            : suspend HandlerContext.() -> Any = lambda@{
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
                ses.setData(dataFromForm(ctx.formMap()))
                "Saved"
            })

            post("/unsubmit", wrap(proceed=true) { ses ->
                ses.setData(dataFromForm(ctx.formMap()), unsubmit=true)
                "Unsubmitted"
            })

            post("/join", wrap { ses ->
                ses.setData(dataFromForm(ctx.formMap()))
                ses.joinTeam(ctx.form("teamCode").value())
                "Joined"
            })

            post("/leave", wrap(proceed=true) { ses ->
                ses.setData(dataFromForm(ctx.formMap()))
                ses.leaveTeam()
                "Left team"
            })

            post("/changename", wrap { ses ->
                ses.setData(dataFromForm(ctx.formMap()))
                ses.setTeamName(ctx.form("teamName").value())
                "Team name changed"
            })

            path("/game") {
                suspend fun renderGame(team: Team?, tid: Int?) =
                    ModelAndView("game.kte", mapOf(
                        "team" to team, "teamName" to tid?.let {db.getTeamName(it)},
                        "verdict" to team?.verdict(),
                        "wsUrl" to wsUrl
                    ))

                get("/") {
                    val tid = getTeamId(ctx)
                    renderGame(tid?.let {game.lock.withLock { game.teams[it]?.t }}, tid)
                }

                post("/submit") {
                    val t = getTeamIdAssert(ctx)

                    val lang = Language.fromExt[ctx.form("language").value()]
                        ?: WebErrorType.BadRequest.err("Invalid language")
                    val code = ctx.form("code").value()

                    val newt = game.addTeam(t, lang,code,isDev,isolateArgs)
                    renderGame(newt, t)
                }

                post("/unsubmit") {
                    val t = getTeamIdAssert(ctx)

                    when (val x = game.lock.withLock { game.teams[t]?.t }) {
                        null -> WebErrorType.NotFound.err("Your team hasn't submitted anything")
                        else -> {
                            game.removeTeam(x)
                            renderGame(null, t)
                        }
                    }
                }

                ws("/ws", wsInit(gameListeners) {
                    Json.encodeToString(getLeaderboard())
                })
            }

            path("/scoreboard") {
                assets("/", Path.of(dir, "scoreboard/dist/index.html"))
                mountDir("scoreboard/dist")

                ws("/ws", wsInit(scoreboardListeners) {
                    if (!db.inProgress()) getSession(ctx).checkAdmin()
                    Json.encodeToString(scoreboard.initData())
                })
            }

            path("/dashboard") {
                get("/") {
                    getSession(ctx, true)

                    ModelAndView("dash.kte", mapOf(
                        "dash" to db.getDashboard()
                    ))
                }

                post("/clear") {
                    getSession(ctx, true)
                    scoreboard.clear()
                    ctx.sendRedirect("/dashboard")
                }

                //basically the same thing...
                post("/unfreeze") {
                    getSession(ctx, true)
                    scoreboard.unfreeze()
                    ctx.sendRedirect("/dashboard")
                }

                post("/props") {
                    getSession(ctx, true)

                    db.setProps(listOf("locked", "inProgress", "closed").associateWith {
                        if (ctx.form(it).valueOrNull()=="on") "true" else "false"
                    } + (ctx.form("scoreboard").valueOrNull()
                        ?.let {
                            if (it.isEmpty()) null else {
                                runCatching {Json.decodeFromString<ScoreboardConfig>(it) }
                                    .getOrElse { ex->
                                        WebErrorType.BadRequest.err("Malformed scoreboard config: ${ex.message}")
                                    }

                                mapOf("scoreboard" to it)
                            }
                        } ?: mapOf()))

                    ctx.sendRedirect("/dashboard")
                }

                fun setTeam(act: TeamAction): suspend HandlerContext.() -> Any = {
                    getSession(ctx, true)
                    val teams = ctx.form("teams").value().trim()
                        .split("\n").map {it.trim().toInt()}

                    when (act) {
                        TeamAction.Accept -> db.setTeamsAccepted(teams, true)
                        TeamAction.Reject -> db.setTeamsAccepted(teams, false)
                        TeamAction.RemoveFromGame -> {
                            for (t in teams){
                                game.lock.withLock { game.teams[t]?.t }?.let {
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

                get("/users") {
                    getSession(ctx, true)
                    FileDownload(FileDownload.Mode.INLINE, Json.encodeToString(db.getUsers()).byteInputStream(), "users.json")
                }

                get("/teams") {
                    getSession(ctx, true)
                    FileDownload(FileDownload.Mode.INLINE, Json.encodeToString(db.getTeams()).byteInputStream(), "teams.json")
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