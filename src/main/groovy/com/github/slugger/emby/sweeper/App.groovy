package com.github.slugger.emby.sweeper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import groovy.util.logging.Slf4j
import groovyx.net.http.RESTClient
import org.slf4j.LoggerFactory
import picocli.CommandLine

import java.time.ZonedDateTime

@Slf4j
@CommandLine.Command(versionProvider = VersionInfo.class, name = 'embysweeper')
class App implements Runnable {
    static void main(String[] args) {
        def cmd = new CommandLine(new App(
                args: args,
                http: new RESTClient(),
                versionInfo: new VersionInfo()))
        cmd.caseInsensitiveEnumValuesAllowed = true
        cmd.execute(args)
    }

    static private enum LogLevel {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        FATAL
    }

    static private enum Action {
        PRINT,
        DELETE
    }

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

    @CommandLine.Option(names = ['-c', '--action'], description = 'action to perform (${COMPLETION-CANDIDATES}) [${DEFAULT-VALUE}]', required = true, defaultValue = 'print')
    private Action action

    @CommandLine.Option(names = ['--https'], description = 'connect to Emby via https', required = true, defaultValue = 'false')
    private boolean useHttps

    @CommandLine.Option(names = ['--min-age-days'], description = 'minimum days since an item was watched before it can be deleted [${DEFAULT-VALUE}]', required = true, defaultValue = '7')
    private int minAgeDays

    @CommandLine.Option(names = ['-e', '--cleanup-user'], description = 'user name to cleanup; multiple allowed', required = true)
    private String[] cleanupUsers

    @CommandLine.Option(names = ['-b', '--excluded-library'], description = 'library name to never delete from; multiple allowed')
    private String[] excludedLibraries

    @CommandLine.Option(names = ['--filter'], description = 'filter to apply to discovered items; multiple allowed; these are filters to apply to the /User/{id}/Items api call')
    private Map<String, String> itemFilters

    @CommandLine.Option(names = ['--ignore-fav-series'], description = 'always ignore episodes if the series is a favorite', required = true, defaultValue = 'false')
    private boolean ignoreFavSeries

    @CommandLine.Option(names = ['--version'], description = 'print version info and exit', versionHelp = true)
    private boolean showVersionInfo

    private String[] args
    private RESTClient http
    private Map seriesStatus = [:]
    private ZonedDateTime watchedBefore
    private VersionInfo versionInfo

    @Override
    void run() {
        LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).level = Level.toLevel(libLogLevel.toString())
        log.level = Level.toLevel(logLevel.toString())
        log.debug('Processing command: ' + args.collect { "\"$it\""}.join(' '))
        watchedBefore = getComputedWatchedBefore()
        log.debug "Only items last watched before $watchedBefore will be deleted"
        if(excludedLibraries == null)
            excludedLibraries = []
        if(itemFilters == null)
            itemFilters = [:]

        try {
            http.uri = getEmbyUrl()
            def key = getApiKey()
            http.defaultRequestHeaders['X-Emby-Token'] = key
            getUsers().each { user ->
                getUserItemsForRemoval(user).each {
                    def logLevel = 'info'
                    def msg = "$it.Path\n\tLast Watched: $it.UserData.LastPlayedDate\n\tDeleted: "
                    if(action == Action.DELETE) {
                        msg += 'YES!'
                        http.delete(path: "/Items/$it.Id")
                        logLevel = 'warn'
                    } else
                        msg += 'NO'
                    log."$logLevel"(msg)
                }
            }
        } catch(Exception e) {
            log.error 'Error', e
        }
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

    private def getFilteredUserViews(def user) {
        http.get(path: "/Users/$user.Id/Views").data.Items.findAll { !excludedLibraries.contains(it.Name) }.collect { it.Id }
    }

    private def getUserItemsForRemoval(def user) {
        def items = []
        def libs = getFilteredUserViews(user)
        libs.each {
            def queryParams = [parentId: it, recursive: true, Fields: 'Path']
            itemFilters.each { k, v -> queryParams[k] = v }
            log.debug "Views params: $queryParams"
            def allItems = http.get(path: "/Users/$user.Id/Items", query: queryParams).data?.Items
            log.debug "Found ${allItems.size()} total items for $user.Name"
            log.trace "All items:\n${allItems.collect { it.Path }.join('\n')}"
            items.addAll(allItems.findAll {
                (!ignoreFavSeries || !isFavSeries(user, it.SeriesId)) && isItemTooOld(it.UserData.LastPlayedDate)
            })
        }
        log.debug "Found ${items.size()} items for $user.Name"
        log.trace "Filtered items:\n${items.collect { it.Path }.join('\n')}"
        items
    }

    private boolean isItemTooOld(String lastPlayed) {
        if(!lastPlayed)
            return true // items that don't have a last played value are always eligible for removal
        ZonedDateTime.parse(lastPlayed).isBefore(watchedBefore)
    }

    private boolean isFavSeries(def user, String seriesId) {
        def status = seriesStatus[seriesId]
        if(status == null) {
            log.debug "Checking series fav status for $seriesId"
            def series = http.get(path: "/Users/$user.Id/Items/", query: [Ids: seriesId]).data.Items[0]
            status = series.UserData.IsFavorite
            log.debug "Series fav status for '$series.Name': $status"
            seriesStatus[seriesId] = status
        } else
            log.debug "Series fav status for $seriesId: $status (cached)"
        status
    }

    private def getUsers() {
        def userObjs = http.get(path: '/Users').data.findAll { cleanupUsers.contains(it.Name) }
        if(log.isDebugEnabled()) {
            userObjs.each {
                log.debug("User '$it.Name': $it.Id")
            }
        }
        userObjs
    }
}