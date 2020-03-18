package com.github.slugger.emby.sweeper.commands

import com.github.slugger.emby.sweeper.App
import groovy.util.logging.Slf4j
import picocli.CommandLine

import java.time.ZonedDateTime

@Slf4j
@CommandLine.Command(name = 'delete', description = 'delete items discovered by parent options & filters')
class Delete implements Runnable {

    @CommandLine.ParentCommand
    private App app

    @CommandLine.Option(names = ['-d', '--really-delete'], description = 'don\'t just print what would be deleted, actually delete the items that are found', required = true, defaultValue = 'false')
    private boolean reallyDelete

    @CommandLine.Option(names = ['-h', '--help'], usageHelp = true, description = 'display help and exit')
    private boolean usageHelp

    private Map seriesStatus = [:]

    @Override
    void run() {
        getUsers().each { user ->
            getUserItemsForRemoval(user).each {
                def logLevel = 'info'
                def msg = "${basicItemDetails(it, user)}\n\tDeleted: "
                if(reallyDelete) {
                    msg += 'YES!'
                    app.http.delete(path: "/Items/$it.Id")
                    logLevel = 'warn'
                } else
                    msg += 'NO'
                log."$logLevel"(msg)
            }
        }
    }

    private def getFilteredUserViews(def user) {
        app.http.get(path: "/Users/$user.Id/Views").data.Items.findAll { !app.excludedLibraries.contains(it.Name) }.collect { it.Id }
    }

    private def getUserItemsForRemoval(def user) {
        def items = []
        def libs = getFilteredUserViews(user)
        libs.each {
            def queryParams = [parentId: it, recursive: true, Fields: 'Path']
            app.itemFilters.each { k, v -> queryParams[k] = v }
            log.debug "Views params: $queryParams"
            def allItems = app.http.get(path: "/Users/$user.Id/Items", query: queryParams).data?.Items
            log.debug "Found ${allItems.size()} total items for $user.Name"
            log.trace "All items:\n${allItems.collect { basicItemDetails(it, user) }.join('\n')}"
            items.addAll(allItems.findAll {
                (!app.ignoreFavSeries || !isFavSeries(user, it.SeriesId)) && isItemTooOld(it.UserData.LastPlayedDate)
            })
        }
        log.debug "Found ${items.size()} items for $user.Name"
        log.trace "Filtered items:\n${items.collect { basicItemDetails(it, user) }.join('\n')}"
        items
    }

    private boolean isItemTooOld(String lastPlayed) {
        if(!lastPlayed)
            return true // items that don't have a last played value are always eligible for removal
        ZonedDateTime.parse(lastPlayed).isBefore(app.watchedBefore)
    }

    private boolean isFavSeries(def user, String seriesId) {
        def status = seriesStatus[seriesId]
        if(status == null) {
            log.debug "Checking series fav status for $seriesId"
            def series = app.http.get(path: "/Users/$user.Id/Items/", query: [Ids: seriesId]).data.Items[0]
            status = series.UserData.IsFavorite
            log.debug "Series fav status for '$series.Name': $status"
            seriesStatus[seriesId] = status
        } else
            log.debug "Series fav status for $seriesId: $status (cached)"
        status
    }

    private def getUsers() {
        def userObjs = app.http.get(path: '/Users').data.findAll { app.cleanupUsers.contains(it.Name) }
        if(log.isDebugEnabled()) {
            userObjs.each {
                log.debug("User '$it.Name': $it.Id")
            }
        }
        userObjs
    }

    private String basicItemDetails(def item, def user = null) {
        def tooOld = isItemTooOld(item?.UserData?.LastPlayedDate)
        def isFavSeries = user ? isFavSeries(user, item.SeriesId) : false
        "$item.Path\n\tLast Watched: ${item?.UserData?.LastPlayedDate} (${tooOld ? 'O' : 'N'})\n\tIs Fav Series: $isFavSeries"
    }
}