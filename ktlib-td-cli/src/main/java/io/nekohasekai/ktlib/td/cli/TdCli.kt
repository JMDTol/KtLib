@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.nekohasekai.ktlib.td.cli

import cn.hutool.core.codec.Base64
import cn.hutool.core.date.SystemClock
import cn.hutool.core.io.FileUtil
import cn.hutool.core.lang.Console
import cn.hutool.core.util.StrUtil
import cn.hutool.log.level.Level
import io.nekohasekai.ktlib.core.*
import io.nekohasekai.ktlib.db.*
import io.nekohasekai.ktlib.td.core.*
import io.nekohasekai.ktlib.td.core.raw.*
import io.nekohasekai.ktlib.td.extensions.*
import io.nekohasekai.ktlib.td.i18n.*
import io.nekohasekai.ktlib.td.utils.deleteDelay
import io.nekohasekai.ktlib.td.utils.make
import io.nekohasekai.ktlib.td.utils.removeKeyboard
import kotlinx.coroutines.*
import org.apache.commons.lang3.SystemUtils
import org.yaml.snakeyaml.Yaml
import td.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.timerTask
import kotlin.system.exitProcess

open class TdCli(tag: String = "", name: String = tag) : TdClient(tag, name), DatabaseInterface {

    lateinit var arguments: Array<String>

    fun launch(args: Array<String>) {

        arguments = args

        var ctn = false

        for (index in arguments.indices) {

            var argument = arguments[index]

            if (ctn) continue

            if (!argument.startsWith("--")) {

                onArgument(argument, arguments.shift().joinToString(" "))

            } else {

                argument = argument.substringAfter("--")

                var param: String? = null

                if (argument.contains("=")) {

                    param = argument.substringAfter("=")
                    argument = argument.substringBefore("=")

                } else if (arguments.size - 1 > index) {

                    ctn = true
                    param = arguments[index + 1]

                }

                onArgument(argument, param)

            }

        }

        requireTdLoad()

    }

    val config = HashMap<String, Any>()

    open val readConfigFromEnvironment = true

    fun stringConfig(key: String) = config[key]?.toString() ?: if (readConfigFromEnvironment) {
        SystemUtils.getEnvironmentVariable(key, null)?.takeIf { it.isNotBlank() }
    } else null

    fun boolConfig(key: String, default: Boolean = false) = ((config[key]?.toString()
        ?: if (readConfigFromEnvironment) {
            SystemUtils.getEnvironmentVariable(key, null)
        } else null)?.toLowerCase() == "true") xor default

    fun listConfig(key: String) = (config[key] as? List<*>)?.map { it.toString() } ?: if (readConfigFromEnvironment) {
        SystemUtils.getEnvironmentVariable(key, null)?.split(" ")
    } else null

    fun stringConfig(key: String, default: Boolean = false) = (config[key]?.toString()
        ?: if (readConfigFromEnvironment) {
            SystemUtils.getEnvironmentVariable(key, "$default")
        } else null)?.toUpperCase() == "true"

    fun intConfig(key: String) = runCatching {
        config[key]?.toString()?.toInt() ?: if (readConfigFromEnvironment) {
            SystemUtils.getEnvironmentVariable(key, null)?.toInt()
        } else null
    }.onFailure {
        defaultLog.warn(">> Invalid integer config item $key")
    }.getOrNull()

    fun longConfig(key: String) = runCatching {
        config[key]?.toString()?.toLong() ?: if (readConfigFromEnvironment) {
            SystemUtils.getEnvironmentVariable(key, null)?.toLong()
        } else null
    }.onFailure {
        defaultLog.warn(">> Invalid long config item $key")
    }.getOrNull()

    private val botTokenInEnv get() = stringConfig("BOT_TOKEN")
    private val binlogInEnv get() = stringConfig("BINLONG")
    override val defaultLang get() = stringConfig("BOT_LANG")

    open var dataDir = File("data").canonicalFile
    open var cacheDir = File("cache").canonicalFile

    fun requireTdLoad() {

        if (!TdLoader.loaded) TdLoader.tryLoad(cacheDir)

    }

    open fun onArgument(argument: String, value: String?) {

        if (configFile != null && argument == "config") {

            if (value == null) {

                defaultLog.warn(">> Missing config path")

                exitProcess(100)

            }

            val configFile = File(value).canonicalFile

            if (!configFile.isFile) {

                defaultLog.error(">> Config file ${configFile.path} not exists!")

                exitProcess(100)

            }

            this.configFile = configFile

        } else if (argument == "export") {

            requireTdLoad()

            val fileName = value ?: "binlog.txt"

            val binlogFile = File(options.databaseDirectory, if (options.useTestDc) "td_test.binlog" else "td.binlog")

            val authorized = binlogFile.isFile && runBlocking {

                onLoad()

                val exportClient = object : TdClient() {

                    override suspend fun onAuthorizationState(authorizationState: TdApi.AuthorizationState) {

                        super.onAuthorizationState(authorizationState)

                        if (authorizationState is TdApi.AuthorizationStateWaitPhoneNumber) {

                            authing = false

                        }

                    }

                }

                exportClient.options = options

                try {

                    exportClient.waitForAuth()

                } finally {

                    exportClient.waitForClose()

                }

            }

            if (!authorized) {

                clientLog.error("Unauthorized.")

                exitProcess(100)

            }

            var binlog = "-----BEGIN TDLIB BINLOG BLOCK-----\n"

            binlog += StrUtil.utf8Str(Base64.encode(binlogFile.readBytes(), true))

            binlog += "\n-----END TDLIB BINLOG BLOCK-----"

            val file = File(fileName).apply { parentFile.mkdirs() }

            file.writeText(binlog)

            clientLog.info(">> Saved to $fileName")

            exitProcess(0)

        }

    }

    open var configFile = File("config.yml")

    fun loadConfig() {

        val configFile = configFile

            if (configFile.isFile && configFile.canRead()) {

                try {

                    val pmConfig = Yaml().loadAs(configFile.readText(), MutableMap::class.java)

                    config.putAll(mapOf(* pmConfig.map { it.key!!.toString() to (it.value ?: "") }.toTypedArray()))

                } catch (e: Exception) {

                    clientLog.error(e, "Parse config failed : ${configFile.path}")

                    exitProcess(100)

                }

            }

        onLoadConfig()

    }

    fun loadLogLevel() {

        val logLevel = stringConfig("LOG_LEVEL")?.toUpperCase() ?: "INFO"

        runCatching {

            LOG_LEVEL = Level.valueOf(logLevel)

        }.onFailure {

            LOG_LEVEL = Level.INFO

            defaultLog.error("Invalid log level $logLevel, fallback to INFO.")

        }

        setLogVerbosityLevel(
            when (LOG_LEVEL) {

                Level.ALL -> 5
                Level.TRACE -> 1
                Level.DEBUG -> 1
                Level.INFO -> 1
                Level.WARN -> 1
                Level.ERROR -> 0
                Level.FATAL -> 0
                Level.OFF -> 0

            }
        )

        if (LOG_LEVEL == Level.ALL) {
            setLogStream(TdApi.LogStreamFile("$cacheDir/tdlib.log", 100 * 1024 * 1024, true))
        } else {
            setLogTagVerbosityLevel("td_requests", 1023)
        }

    }

    fun loadDataDir() {

        val dataDirStr = stringConfig("DATA_DIR")

        if (!dataDirStr.isNullOrBlank()) {

            dataDir = File(dataDirStr)

            if (!dataDir.isDirectory && !dataDir.mkdirs()) {

                clientLog.error("Unable to create data directory: $dataDir")

                exitProcess(100)

            }

        }

        val cacheDirStr = stringConfig("CACHE_DIR")

        if (!cacheDirStr.isNullOrBlank()) {

            cacheDir = File(cacheDirStr)

            if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {

                clientLog.error("Unable to create cache directory: $cacheDir")

                exitProcess(100)

            }

        }

    }

    companion object {

        var reportCount by AtomicInteger()

    }

    fun registerErrorReport(errorReportChatId: Long) {

        eventErrorHandler = { client: TdClient, error: Throwable, event: TdApi.Update ->
            client.clientLog.error(error, "TdError - Sync\n\nIn event$event")
            if (reportCount in 0..10) {
                runCatching {
                    runBlocking {
                        sudo make "Error in event $event\n\n${error.parse()}" syncTo errorReportChatId
                    }
                    reportCount++
                }.onFailure {
                    reportCount = -1
                }
            }
        }

        contextErrorHandler = { client: TdClient, error: Throwable, context: Any? ->
            client.clientLog.error(error, "TdError - Sync\n\nIn context $context")
            if (reportCount in 0..10) {
                runCatching {
                    runBlocking {
                        sudo make "Error in context $context\n\n${error.parse()}" syncTo errorReportChatId
                    }
                    reportCount++
                }.onFailure {
                    reportCount = -1
                }
            }
        }

        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            contextErrorHandler(this, exception, thread)
        }

    }

    private lateinit var _database: DatabaseDispatcher
    override val database by ::_database

    fun initDatabase(dbName: String = "td_data.db", directory: String = options.databaseDirectory): DatabaseDispatcher {

        synchronized(::database) {

            if (!::_database.isInitialized) {

                _database = openSqliteDatabase(File(directory, dbName))

            }

        }

        return database

    }

    override suspend fun gc() {

        if (::_database.isInitialized) database.saveAndGc()

        super.gc()

    }

    override suspend fun saveCache() {

        if (::_database.isInitialized) database.saveCache()

        super.saveCache()

    }

    fun scheduleGcAndOptimize(saveDelay: Long = 1L * Hours, gcDelay: Long = 1 * Days) {

        timer.scheduleAtFixedRate(timerTask {

            GlobalScope.launch(Dispatchers.Default) {

                saveCache()

            }

        }, SystemClock.now() + saveDelay, saveDelay)

        timer.scheduleAtFixedRate(timerTask {

            GlobalScope.launch(Dispatchers.Default) {

                gc()

            }

        }, SystemClock.now() + gcDelay, gcDelay)

    }

    open fun onLoadConfig() {

        System.setProperty("kotlinx.coroutines.stacktrace.recovery", "true")

        loadLogLevel()

        loadDataDir()

    }

    enum class LoginType { ALL, USER, BOT }

    open val loginType = LoginType.BOT

    override val skipSelfMessage by lazy { loginType == LoginType.BOT }

    override fun onLoad() {

        if (boolConfig("USE_TEST_DC")) options useTestDc true

        val apiId = intConfig("API_ID")

        if (apiId != null) {

            options apiId apiId

            val apiHash = stringConfig("API_HASH")

            if (apiHash.isNullOrBlank()) {

                clientLog.error("API_HASH undefined.")

                exitProcess(100)

            }

            options apiHash apiHash

        }

        options databaseDirectory dataDir.path
        options filesDirectory File(cacheDir, "files").path

    }

    override fun start() {

        val binlogInEnv = binlogInEnv

        if (binlogInEnv != null) {

            runCatching {

                val binlog = binlogInEnv
                    .substringAfter("-----BEGIN TDLIB BINLOG BLOCK-----")
                    .substringBefore("-----END TDLIB BINLOG BLOCK-----")
                    .let { Base64.decode(it) }

                val binlogFile = File(
                    options.databaseDirectory,
                    if (options.useTestDc) "td_test.binlog" else "td.binlog"
                )

                if (!binlogFile.isFile || !binlogFile.readBytes().contentEquals(binlog)) {

                    FileUtil.touch(binlogFile)

                    binlogFile.writeBytes(binlog)

                }

            }

        }

        super.start()

    }

    override suspend fun onConnectionState(state: TdApi.ConnectionState) {

        if (!auth) return

        clientLog.debug(state.javaClass.simpleName.substringAfter("ConnectionState"))

    }

    override suspend fun onAuthorizationState(authorizationState: TdApi.AuthorizationState) =
        withContext(Dispatchers.IO) {

            try {

                suspend fun inputPhone() {

                    while (!stop) {

                        when (loginType) {
                            LoginType.ALL -> print(clientLocale.INPUT_PHONE_NUMBER_OR_BOT_TOKEN)
                            LoginType.USER -> print(clientLocale.INPUT_PHONE_NUMBER)
                            LoginType.BOT -> print(clientLocale.INPUT_BOT_TOKEN)
                        }

                        val phoneNumberOrBotToken = Console.input()

                        if ((loginType == LoginType.ALL && phoneNumberOrBotToken.contains(":")) || loginType == LoginType.BOT) {

                            try {

                                checkAuthenticationBotToken(phoneNumberOrBotToken)

                                break

                            } catch (e: TdException) {

                                println(clientLocale.INVALID_BOT_TOKEN.input(e.message))

                            }

                        } else {

                            try {

                                setAuthenticationPhoneNumber(phoneNumberOrBotToken)

                                break

                            } catch (e: TdException) {

                                println(clientLocale.INVALID_PHONE_NUMBER.input(e.message))

                            }

                        }

                    }

                }

                if (authorizationState is TdApi.AuthorizationStateWaitPhoneNumber) {

                    val botTokenInEnv = botTokenInEnv

                    if (botTokenInEnv != null && loginType != LoginType.USER) {

                        try {

                            checkAuthenticationBotToken(botTokenInEnv)

                            return@withContext

                        } catch (e: TdException) {

                            println(clientLocale.INVALID_ENV_VARIABLE_BOT_TOKEN.input(e.message))

                        }

                    } else if (loginType != LoginType.BOT && options.apiId == 21724) {

                        clientLog.warn("No custom API_ID and HASH are specified, login with a new account may be banned!")

                    }

                    inputPhone()

                } else if (authorizationState is TdApi.AuthorizationStateWaitCode) {

                    while (!stop) {

                        print(clientLocale.INPUT_AUTH_CODE)

                        val authenticationCode = Console.input()

                        if (authenticationCode == "resend") {

                            try {

                                resendAuthenticationCode()

                                println(clientLocale.AUTH_CODE_RESENT)

                            } catch (e: TdException) {

                                println(clientLocale.RESEND_FAILED.input(e.message))

                            }

                        } else if (authenticationCode == "reset") {

                            inputPhone()

                        } else {

                            try {

                                checkAuthenticationCode(authenticationCode)

                                break

                            } catch (e: TdException) {

                                // TODO: ????????????

                                println("??????????????? (${e.message}), ?????? resend ????????????, reset ???????????????.")

                            }

                        }

                    }

                } else if (authorizationState is TdApi.AuthorizationStateWaitPassword) {

                    while (!stop) {

                        print("???????????? (?????? destroy ????????????.): ")

                        val authenticationPassword = Console.input()

                        if (authenticationPassword == "destroy") {

                            try {

                                deleteAccount("PASSWORD FORGOTTEN")

                                println("?????????")

                            } catch (e: TdException) {

                                println("??????????????????: ${e.message}")

                            }

                        } else if (authenticationPassword == "reset") {

                            inputPhone()

                        } else {

                            try {

                                checkAuthenticationPassword(authenticationPassword)

                                break

                            } catch (e: TdException) {

                                println("???????????? (${e.message}), ?????? destroy ????????????.")

                            }

                        }

                    }

                } else if (authorizationState is TdApi.AuthorizationStateWaitRegistration) {

                    while (!stop) {

                        print("?????????????????????: ")

                        val name = Console.input()

                        try {

                            registerUser(name)

                            break

                        } catch (e: TdException) {

                            println("????????????: $e")

                        }

                    }

                } else if (authorizationState is TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {

                    println("??????????????????????????????.")

                } else if (authorizationState is TdApi.AuthorizationStateReady) {

                    super.onAuthorizationState(authorizationState)

                    events.launch {

                        clientLog.info("Login User ${me.displayNameFormatted}")

                    }

                } else {

                    super.onAuthorizationState(authorizationState)

                }

            } catch (e: NoSuchElementException) {

                clientLog.error("Login Failed")

                waitForClose()

                exitProcess(100)

            }

        }

    override suspend fun onTermsOfService(termsOfServiceId: String, termsOfService: TdApi.TermsOfService) {

        acceptTermsOfServiceWith(termsOfServiceId) { onFailure = null }

    }

    override suspend fun onUndefinedFunction(
        userId: Int,
        chatId: Long,
        message: TdApi.Message,
        function: String,
        param: String,
        params: Array<String>
    ) {

        if (function == "cancel") {

            userCalled(userId, "nothing to cancel")

            sudo make localeFor(userId).NOTHING_TO_CANCEL withMarkup removeKeyboard() onSuccess deleteDelay(message) sendTo chatId

        } else if (message.fromPrivate) {

            userCalled(userId, "undefined function, transfer to launch")

            onLaunch(userId, chatId, message)

        }

        finishEvent()

    }

    override suspend fun onSendCanceledMessage(userId: Int, chatId: Long) {

        val L = localeFor(userId)

        sudo make L.CANCELED withMarkup removeKeyboard() syncTo userId

    }

}