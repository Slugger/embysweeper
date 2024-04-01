package com.github.slugger.emby.sweeper.commands

import groovy.util.logging.Slf4j
import picocli.CommandLine

import java.time.ZonedDateTime

@Slf4j
@CommandLine.Command(name = 'keep', description = 'keep a certain number of items, deleting the oldest ones')
class KeepAtMost extends Delete {

    @CommandLine.Option(names = ['-k', '--keep'], description = 'number of items to keep, older ones are deleted first', required = true)
    private int keepCount

    protected def getUserItemsForRemoval(def user) {
        def items = []
        def libs = getFilteredUserViews(user)
        def app = super.@app
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
            items.addAll(allItems.findAll { isItemTooOld(it?.DateCreated) })
        }
        log.debug "Found ${items.size()} items for $user.Name"
        log.trace "Filtered items:\n${items.collect { basicItemDetails(it, user) }.join('\n')}"
        items
    }

    protected boolean isItemTooOld(String created) {
        if(!created)
            throw new IllegalStateException('created date cannot be empty')
        ZonedDateTime.parse(created).isBefore(ZonedDateTime.now().minusHours(24 * super.@app.minAgeDays))
    }

    protected String basicItemDetails(def item, def user = null) {
        def tooOld = isItemTooOld(item?.DateCreated)
        "$item.Path\n\tCreated: ${item?.DateCreated} (${tooOld ? 'O' : 'N'})"
    }
}
