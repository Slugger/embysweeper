package com.github.slugger.emby.sweeper.commands

import com.github.slugger.emby.sweeper.App
import groovy.util.logging.Slf4j
import picocli.CommandLine

import java.time.ZonedDateTime

@Slf4j
@CommandLine.Command(name = 'keep', description = 'keep a certain number of results, deleting the oldest ones')
class KeepAtMost implements Runnable {

    @CommandLine.ParentCommand
    private App app

    @CommandLine.Option(names = ['-d', '--really-delete'], description = 'don\'t just print what would be deleted, actually delete the items that are found', required = true, defaultValue = 'false')
    private boolean reallyDelete

    @CommandLine.Option(names = ['-k', '--keep'], description = 'number of items to keep, older ones are deleted first', required = true)
    private int keepCount

    @CommandLine.Option(names = ['-h', '--help'], usageHelp = true, description = 'display help and exit')
    private boolean usageHelp

    private Map seriesStatus = [:]

    @Override
    void run() {
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

    private def getFilteredUserViews(def user) {
        def views = app.http.get(path: "/Users/$user.Id/Views").data.Items
        def filteredLibs = app.libraries.excludedLibraries != null ?
                views.findAll { !app.libraries.excludedLibraries.contains(it.Name) } :
                views.findAll { app.libraries.includedLibraries.contains(it.Name) }
        log.debug "Filtered libraries for user '$user.Name': ${filteredLibs.collect { it.Name }}"
        filteredLibs
    }

    private def getUserItemsForRemoval(def user) {
        def items = []
        def libs = getFilteredUserViews(user)
        libs.each {
            def queryParams = [parentId: it.Id, recursive: true, Fields: 'Path,DateCreated', SortOrder: 'Ascending', SortBy: 'DateCreated']
            app.itemFilters.each { k, v -> queryParams[k] = v }
            log.debug "View params: $queryParams"
            def allItems = app.http.get(path: "/Users/$user.Id/Items", query: queryParams).data?.Items
            def idx = allItems.size() - keepCount
            if(idx < 1)
                allItems.clear()
            else
                allItems = allItems.subList(0, idx)
            log.debug "Found ${allItems.size()} total items for $user.Name in library '$it.Name'"
            log.trace "All items:\n${allItems.collect { basicItemDetails(it, user) }.join('\n')}"
            items.addAll(allItems.findAll {
                (!app.ignoreFavSeries || !isFavSeries(user, it.SeriesId)) \
                    && isItemTooOld(it?.DateCreated)
            })
        }
        log.debug "Found ${items.size()} items for $user.Name"
        log.trace "Filtered items:\n${items.collect { basicItemDetails(it, user) }.join('\n')}"
        items
    }

    private boolean isItemTooOld(String created) {
        if(!created)
            throw new IllegalStateException('created date cannot be empty')
        ZonedDateTime.parse(created).isBefore(ZonedDateTime.now().minusHours(24 * app.minAgeDays))
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
        def tooOld = isItemTooOld(item?.DateCreated)
        def isFavSeries = user ? isFavSeries(user, item.SeriesId) : false
        "$item.Path\n\tCreated: ${item?.DateCreated} (${tooOld ? 'O' : 'N'})"
    }
}
