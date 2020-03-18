package com.github.slugger.emby.sweeper.commands

import com.github.slugger.emby.sweeper.App
import groovy.util.logging.Slf4j
import picocli.CommandLine

import java.time.ZonedDateTime

@Slf4j
@CommandLine.Command(name = 'audit', description = 'audit a file; determine why it will or won\'t be deleted')
class Audit implements Runnable {

    @CommandLine.ParentCommand
    private App app

    @CommandLine.Option(names = ['-h', '--help'], usageHelp = true)
    private boolean help

    @CommandLine.Option(names = ['-f', '--file'], required = true)
    private String[] files

    @Override
    void run() {
        files.each { file ->
            log.info "Checking if $file is a valid media file..."
            if(fileExists(file))
                log.info 'Media file exists in Emby server... onto the next check'
            else {
                log.error 'Media file does not exist in Emby server!  Check the path given and try again.'
                return
            }

            log.info 'Checking if file is excluded by cleanup users...'
            def visibleBy = visibleByCleanupUsers(file)
            if(visibleBy)
                log.info "This file is visible by one or more users ${visibleBy.collect { it.Name }}... onto the next check"
            else {
                log.error "This file was not deleted because it is not visible by any of the specified cleanup users $app.cleanupUsers"
                return
            }

            log.info 'Checking if the file is excluded by excluded libraries...'
            def visibleUser = foundInIncludedLibrary(file, visibleBy)
            if(visibleUser)
                log.info 'The file is still visible by at least one user... onto the next check'
            else {
                log.error "This file was not deleted because it only exists in excluded libraries [$app.excludedLibraries]"
                return
            }

            log.info 'Checking if any of the given filters have excluded the file...'
            def excludingFilters = app.itemFilters.findAll { k, v ->
                log.info "Checking '$k=$v' filter..."
                visibleBy.findAll { user ->
                    app.http.get(path: "/Users/$user.Id/Items", query: [recursive: true, Path: file, "$k": v]).data.TotalRecordCount > 0
                }.size() == 0
            }
            if(excludingFilters) {
                log.error "This file was not deleted because it was excluded by the following filters: $excludingFilters"
                return
            } else
                log.info 'None of the filters excluded this file from being deleted... onto the next check'

            log.info 'Checking if the --ignore-fav-series flag was set...'
            if(app.ignoreFavSeries) {
                log.info 'It is... checking if any visible user has not marked the series as a fav...'
                def nonFav = visibleBy.find { user ->
                    app.http.get(path: "/Users/$user.Id/Items", query: [recursive: true, Path: file]).data.Items[0].UserData.IsFavorite
                } == null
                if(nonFav)
                    log.info 'There is at least one user who has not marked this as a fav, it can still be deleted... onto next check'
                else {
                    log.error 'This file was not delete because every user has marked this series as a fav'
                    return
                }
            } else
                log.info 'It is not... onto the next check'

            log.info 'Last check... check if the item is not old enough yet...'
            log.info "To be deleted the item must have been watched more than $app.minAgeDays days ago..."
            def tooOld = visibleBy.find { user ->
                isItemTooOld(app.http.get(path: "/Users/$user.Id/Items", query: [recursive: true, Path: file]).data.Items[0].UserData.LastPlayedDate)
            }
            if(!tooOld)
                log.error "This file will not be deleted because it was last watched by someone within the last $app.minAgeDays days"
            else
                log.info 'This file should be deleted by the DELETE action!'
        }
    }

    private boolean isFavSeries(def user, String seriesId) {
        log.debug "Checking series fav status for $seriesId"
        def series = app.http.get(path: "/Users/$user.Id/Items/", query: [Ids: seriesId]).data.Items[0]
        series.UserData.IsFavorite
    }

    private def foundInIncludedLibrary(String file, def visibleUsers) {
        visibleUsers.find { user ->
            getUserViews(user).find {
                app.http.get(path: "/Users/$user.Id/Items", query: [parentId: it, recursive: true, Path: file]).data.TotalRecordCount > 0
            }
        }
    }

    private List visibleByCleanupUsers(String file) {
        getUsers().findAll {
            app.http.get(path: "/Users/$it.Id/Items", query: [recursive: true, Path: file]).data.TotalRecordCount > 0
        }
    }

    private boolean fileExists(String file) {
        app.http.get(path: '/Items', query: [recursive: true, Path: files[0]]).data.TotalRecordCount > 0
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

    private def getUserViews(def user) {
        app.http.get(path: "/Users/$user.Id/Views").data.Items.findAll { !app.excludedLibraries.contains(it.Name) }.collect { it.Id }
    }

    private boolean isItemTooOld(String lastPlayed) {
        if(!lastPlayed)
            return true // items that don't have a last played value are always eligible for removal
        ZonedDateTime.parse(lastPlayed).isBefore(app.watchedBefore)
    }
}

