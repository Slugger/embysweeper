package com.github.slugger.emby.sweeper.commands

import com.github.slugger.emby.sweeper.App
import groovyx.net.http.RESTClient
import spock.lang.Specification
import spock.lang.Unroll

class DeleteTest extends Specification {

    @Unroll
    def 'http delete request is #desc when dry run mode is #dryMode'() {
        given:
            def http = Mock(RESTClient) {
                1 * get(path: '/Users') >> [data: [[Name: 'user1', Id: '1']]]
                1 * get(path: '/Users/1/Views') >> [data: [Items: [[Name: 'v1', Id: 'v1']]]]
                2 * get(path: '/Users/1/Items', query: _) >> [data: [Items: [[Id: 'i1']]]]
                expectedDeleteCalls * delete(*_)
                0 * _
            }
            def app = new App(http: http, cleanupUsers: ['user1'])
            app.excludedLibraries = []
        when:
            new Delete(app: app, reallyDelete: !dryMode).run()
        then:
            notThrown(Throwable)
        where:
            dryMode | desc          || expectedDeleteCalls
            true    | 'not sent'    || 0
            false   | 'sent'        || 1
    }

    def 'http delete request is sent when not in dry run mode'() {

    }

    def 'getUsers() returns the proper subset of specified users'() {
        given:
            def findme = [Name: 'findme', Id: '2']
            def http = Mock(RESTClient) {
                1 * get(path: '/Users') >> [data: [[Name: 'user1', Id: '1'], findme, [Name: 'user2', Id: '3']]]
                0 * _
            }
            def app = new App(http: http, cleanupUsers: ['findme'])
        expect:
            new Delete(app: app).getUsers() == [findme]
    }

    def 'getUsers() returns an empty list when the specified user does not exist'() {
        given:
            def http = Mock(RESTClient) {
                1 * get(path: '/Users') >> [data: [[Name: 'user1', Id: '1']]]
                0 * _
            }
            def app = new App(http: http, cleanupUsers: ['foo'])
        expect:
            new Delete(app: app).getUsers() == []
    }

    @Unroll
    def 'a user\'s views are filtered based on excluded lib options [#desc]'() {
        given:
            def user = [Id: '1']
            def http = Mock(RESTClient) {
                1 * get(path: "/Users/$user.Id/Views") >> [data: [Items: views]]
            }
            def app = new App(http: http, excludedLibraries: excludes)
        expect:
            new Delete(app: app).getFilteredUserViews(user) == result
        where:
            desc | excludes    | views                                                     | result
            1    | ['lib1']    | [[Name: 'lib1', Id: '10']]                                | []
            2    | ['lib1']    | [[Name: 'lib2', Id: '20'], [Name: 'lib1', Id: '10']]      | ['20']
            3    | []          | [[Name: 'lib2', Id: '20'], [Name: 'lib1', Id: '10']]      | ['20', '10']
            4    | []          | []                                                        | []
    }
}
