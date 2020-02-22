package com.github.slugger.emby.sweeper

import groovyx.net.http.RESTClient
import spock.lang.Specification
import spock.lang.Unroll

import java.time.ZonedDateTime

class AppTest extends Specification {

    @Unroll
    def 'any non negative value (#input) for --min-age-days is accepted'() {
        given:
            def now = ZonedDateTime.now()
            def app = new App(minAgeDays: input)
        expect:
            Math.abs(app.getComputedWatchedBefore().toEpochSecond() - now.minusDays(input).toEpochSecond()) <= 1
        where:
            input << [0, 1, 7, 14, 30, 31, 60, 90, 180, 360, 365, 366, 1800, 3600, 36000]
    }

    @Unroll
    def 'a negative value (#input) for --min-age-days is an error'() {
        given:
            def now = ZonedDateTime.now()
            def app = new App(minAgeDays: input)
        when:
            app.getComputedWatchedBefore()
        then:
            thrown(IllegalArgumentException)
        where:
            input << [-1, -10, -100, -1000000]
    }

    @Unroll
    def 'when https is #desc, the emby url uses #value'() {
        given:
            def app = new App(useHttps: input)
        expect:
            app.getEmbyUrl().startsWith("$protocol:")
        where:
            desc    | protocol  | input
            'set'   | 'https'   | true
            'unset' | 'http'    | false
    }

    def 'getUsers() returns an empty list when the specified user does not exist'() {
        given:
            def http = Mock(RESTClient) {
                1 * get(path: '/Users') >> [data: [[Name: 'user1', Id: '1']]]
                0 * _
            }
        expect:
            new App(http: http, cleanupUsers: ['foo']).getUsers() == []
    }

    def 'getUsers() returns the proper subset of specified users'() {
        given:
            def findme = [Name: 'findme', Id: '2']
            def http = Mock(RESTClient) {
                1 * get(path: '/Users') >> [data: [[Name: 'user1', Id: '1'], findme, [Name: 'user2', Id: '3']]]
                0 * _
            }
        expect:
            new App(http: http, cleanupUsers: ['findme']).getUsers() == [findme]
    }

    @Unroll
    def 'a user\'s views are filtered based on excluded lib options [#desc]'() {
        given:
            def user = [Id: '1']
            def http = Mock(RESTClient) {
                1 * get(path: "/Users/$user.Id/Views") >> [data: [Items: views]]
            }
        expect:
            new App(http: http, excludedLibraries: excludes).getFilteredUserViews(user) == result
        where:
            desc | excludes    | views                                                     | result
            1    | ['lib1']    | [[Name: 'lib1', Id: '10']]                                | []
            2    | ['lib1']    | [[Name: 'lib2', Id: '20'], [Name: 'lib1', Id: '10']]      | ['20']
            3    | []          | [[Name: 'lib2', Id: '20'], [Name: 'lib1', Id: '10']]      | ['20', '10']
            4    | []          | []                                                        | []
    }
}
