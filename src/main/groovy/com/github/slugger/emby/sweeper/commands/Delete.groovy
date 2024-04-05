package com.github.slugger.emby.sweeper.commands

import com.github.slugger.emby.sweeper.App
import groovy.util.logging.Slf4j
import picocli.CommandLine

import java.time.ZonedDateTime

@Slf4j
@CommandLine.Command(name = 'delete', description = 'delete items discovered by parent options & filters')
class Delete implements Runnable {

    @CommandLine.ParentCommand
    protected App app

    @CommandLine.Option(names = ['-d', '--really-delete'], description = 'don\'t just print what would be deleted, actually delete the items that are found', required = true, defaultValue = 'false')
    protected boolean reallyDelete

    @CommandLine.Option(names = ['-h', '--help'], usageHelp = true, description = 'display help and exit')
    private boolean usageHelp

    private Map seriesStatus = [:]
    private Map tagStatus = [:]

    @Override
    final void run() {
        def app = this.app
        getUsers().each { user ->
            getUserItemsForRemoval(user).each {
                def logLevel = 'info'
                def msg = "${basicItemDetails(it, user)}\n\tDeleted: "
                def ex = null
                if(reallyDelete) {
                    logLevel = 'warn'
                    try {
                        app.http.delete(path: "/Items/$it.Id")
                        msg += 'YES!'
                    } catch(Throwable t) {
                        logLevel = 'error'
                        msg += 'NO'
                        ex = t
                        log.error "Failed to delete $it.Id\n$it"
                    }
                } else
                    msg += 'NO'
                log."$logLevel"(msg, ex)
            }
        }
    }

    protected def getFilteredUserViews(def user) {
        def app = this.app
        def views = app.http.get(path: "/Users/$user.Id/Views").data.Items
        def filteredLibs = app.libraries.excludedLibraries != null ?
                views.findAll { !app.libraries.excludedLibraries.contains(it.Name) } :
                views.findAll { app.libraries.includedLibraries.contains(it.Name) }
        log.debug "Filtered libraries for user '$user.Name': ${filteredLibs.collect { it.Name }}"
        filteredLibs
    }

    protected def getUserItemsForRemoval(def user) {
        def items = []
        def libs = getFilteredUserViews(user)
        libs.each {
            def queryParams = [parentId: it.Id, recursive: true, Fields: 'Path,UserDataLastPlayedDate']
            app.itemFilters.each { k, v -> queryParams[k] = v }
            log.debug "View params: $queryParams"
            def allItems = app.http.get(path: "/Users/$user.Id/Items", query: queryParams).data?.Items
            log.debug "Found ${allItems.size()} total items for $user.Name in library '$it.Name'"
            log.trace "All items:\n${allItems.collect { basicItemDetails(it, user) }.join('\n')}"
            items.addAll(allItems.findAll {
                (!app.ignoreFavSeries || !isFavSeries(user, it.SeriesId)) \
                    && (!app.ignoreSeriesTag || !isTaggedSeries(user, it.SeriesId, app.ignoreSeriesTag)) \
                    && isItemTooOld(it?.UserData?.LastPlayedDate)
            })
        }
        log.debug "Found ${items.size()} items for $user.Name"
        log.trace "Filtered items:\n${items.collect { basicItemDetails(it, user) }.join('\n')}"
        items
    }

    protected boolean isItemTooOld(String lastPlayed) {
        if(!lastPlayed)
            return true // items that don't have a last played value are always eligible for removal
        ZonedDateTime.parse(lastPlayed).isBefore(app.watchedBefore)
    }

    private boolean isTaggedSeries(def user, String seriesId, String tag) {
        def status = tagStatus[tag]
        if(status == null)
            tagStatus[tag] = [:]
        else
            status = tagStatus[tag][seriesId]

        if(status == null) {
            log.debug "Checking series tag status for $seriesId/$tag"
            def series = app.http.get(path: "/Users/$user.Id/Items", query: [Ids: seriesId, Tags: tag]).data
            status = series.Items.size() == 1
            log.debug "Series tag status for '$series.Name'/$tag: $status"
            tagStatus[tag][seriesId] = status
        } else
            log.trace "Series tag status for $seriesId/$tag: $status (cached)"
        status
    }

    private boolean isFavSeries(def user, String seriesId) {
        def status = seriesStatus[seriesId]
        if(status == null) {
            log.debug "Checking series fav status for $seriesId"
            def series = app.http.get(path: "/Users/$user.Id/Items", query: [Ids: seriesId]).data.Items[0]
            status = series?.UserData?.IsFavorite ?: false
            log.debug "Series fav status for '$series.Name': $status"
            seriesStatus[seriesId] = status
        } else
            log.trace "Series fav status for $seriesId: $status (cached)"
        status
    }

    final protected def getUsers() {
        def app = this.app
        def userObjs = app.http.get(path: '/Users').data.findAll { app.cleanupUsers.contains(it.Name) }
        if(log.isDebugEnabled()) {
            userObjs.each {
                log.debug("User '$it.Name': $it.Id")
            }
        }
        userObjs
    }

    protected String basicItemDetails(def item, def user = null) {
        def tooOld = isItemTooOld(item?.UserData?.LastPlayedDate)
        def isFavSeries = user ? isFavSeries(user, item.SeriesId) : false
        "$item.Path\n\tLast Watched: ${item?.UserData?.LastPlayedDate} (${tooOld ? 'O' : 'N'})\n\tIs Fav Series: $isFavSeries"
    }
}
