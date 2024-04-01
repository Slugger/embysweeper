package com.github.slugger.emby.sweeper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.slugger.emby.sweeper.commands.Audit
import com.github.slugger.emby.sweeper.commands.Delete
import com.github.slugger.emby.sweeper.commands.KeepAtMost
import groovy.util.logging.Slf4j
import groovyx.net.http.ContentEncoding
import groovyx.net.http.RESTClient
import org.slf4j.LoggerFactory
import picocli.CommandLine

import java.time.ZonedDateTime

@Slf4j
@CommandLine.Command(name = 'embysweeper',
        versionProvider = VersionInfo,
        subcommands = [Delete, KeepAtMost, Audit],
        showAtFileInUsageHelp = true)
class App implements Runnable, CommandLine.IExecutionStrategy {
    static void main(String[] args) {
        def app = new App(
                args: args,
                http: new RESTClient(),
                versionInfo: new VersionInfo()
        )
        def cmd = new CommandLine(app)
        cmd.caseInsensitiveEnumValuesAllowed = true
        cmd.executionStrategy = app
        System.exit(cmd.execute(args))
    }

    static private enum LogLevel {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        FATAL
    }

    static private class EmbyLibraries {
        @CommandLine.Option(names = ['-b', '--excluded-library'], description = 'a library not to scan in; multiple allowed')
        String[] excludedLibraries
        @CommandLine.Option(names = ['-i', '--included-library'], description = 'a library to scan in; multiple allowed')
        String[] includedLibraries
    }

    @CommandLine.ArgGroup(exclusive = true, multiplicity = '1')
    private EmbyLibraries libraries

    @CommandLine.Option(names = ['-l', '--log-level'], description = 'log level (${COMPLETION-CANDIDATES}) [${DEFAULT-VALUE}]', required = true, defaultValue = 'info')
    private LogLevel logLevel

    @CommandLine.Option(names = ['--lib-log-level'], description = 'log level for third party libs (${COMPLETION-CANDIDATES}) [${DEFAULT-VALULE}]', required = true, defaultValue = 'warn')
    private LogLevel libLogLevel

    @CommandLine.Option(names = ['-s', '--server'], description = 'Emby hostname [${DEFAULT-VALUE}]', required = true, defaultValue = 'localhost')
    private String embyHost

    @CommandLine.Option(names = ['-o', '--port'], description = 'Emby server port [${DEFAULT-VALUE}]', required = true, defaultValue = '8096')
    private int embyPort

    @CommandLine.Option(names = ['-u', '--user'], description = 'Emby user; must have admin permission to do deletes', required = true)
    private String user

    @CommandLine.Option(names = ['-p', '--password'], description = 'Emby password', required = true)
    private String password

    @CommandLine.Option(names = ['-h', '--help'], usageHelp = true, description = 'display help and exit')
    private boolean usageHelp

    @CommandLine.Option(names = ['--https'], description = 'connect to Emby via https', required = true, defaultValue = 'false')
    private boolean useHttps

    @CommandLine.Option(names = ['--min-age-days'], description = 'minimum days since an item was watched before it can be deleted [${DEFAULT-VALUE}]', required = true, defaultValue = '7')
    private int minAgeDays

    @CommandLine.Option(names = ['-e', '--cleanup-user'], description = 'user name to cleanup; multiple allowed', required = true)
    private String[] cleanupUsers

    @CommandLine.Option(names = ['--filter'], description = 'filter to apply to discovered items; multiple allowed; these are filters to apply to the /User/{id}/Items api call')
    private Map<String, String> itemFilters

    @CommandLine.Option(names = ['--ignore-fav-series'], description = 'always ignore episodes if the series is a favorite', required = true, defaultValue = 'false')
    private boolean ignoreFavSeries

    @CommandLine.Option(names = ['--version'], description = 'print version info and exit', versionHelp = true)
    private boolean showVersionInfo

    private String[] args
    private RESTClient http
    private ZonedDateTime watchedBefore
    private VersionInfo versionInfo

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec

    @Override
    void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), 'Missing required subcommand')
    }

    int execute(CommandLine.ParseResult parseResult) {
        def rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.level = Level.toLevel(libLogLevel.toString())
        LoggerFactory.getLogger('com.github.slugger.emby.sweeper').level = Level.toLevel(logLevel.toString())
        log.debug('Processing command: ' + args.collect { "\"$it\""}.join(' '))
        if(rootLogger.debugEnabled) { // if we're logging wire data, disable compression so we can read it
            http.client.removeRequestInterceptorByClass(ContentEncoding.RequestInterceptor)
            http.client.removeResponseInterceptorByClass(ContentEncoding.ResponseInterceptor)
        }

        if(!usageHelp && !showVersionInfo)
            init()
        new CommandLine.RunLast().execute(parseResult)
    }

    private void init() {
        watchedBefore = getComputedWatchedBefore()
        log.debug "Only items last watched before $watchedBefore will be deleted"
        if(itemFilters == null)
            itemFilters = [:]

        http.uri = getEmbyUrl()
        def key = getApiKey()
        http.defaultRequestHeaders['X-Emby-Token'] = key
    }

    private String getEmbyUrl() {
        def url = "http${useHttps ? 's' : ''}://$embyHost:$embyPort/"
        log.debug "Emby URL: $url"
        url
    }

    private ZonedDateTime getComputedWatchedBefore() {
        if(minAgeDays < 0)
            throw new IllegalArgumentException('--min-age-days can\'t be negative')
        ZonedDateTime.now().minusDays(minAgeDays)
    }

    private def getApiKeyRequestHeaders() {
        ['Authorization': "Emby UserId=\"$user\", Client=\"EmbySweeper\", Device=\"EmbySweeper\", DeviceId=\"EmbySweeper\", Version=\"$versionInfo.VERSION_DISPLAY\""]
    }

    private String getApiKey() {
        def headers = getApiKeyRequestHeaders()
        http.post(path: '/Users/AuthenticateByName',
                contentType: 'application/json',
                'headers': headers,
                body: [Username: user, Pw: password]).data.AccessToken
    }
}