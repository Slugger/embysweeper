package com.github.slugger.emby.sweeper

import picocli.CommandLine
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

    def 'getApiKeyRequestHeaders() includes proper version info'() {
        given:
            def verInfo = Stub(VersionInfo) {
                getProperty('VERSION_DISPLAY') >> '0.0'
            }
        expect:
            new App(versionInfo: verInfo).getApiKeyRequestHeaders()['Authorization'].contains("Version=\"$verInfo.VERSION_DISPLAY\"")
    }

    @Unroll
    def 'when #opt is set, init() is not executed'() {
        given:
            def app = new App(args: [opt])
            def cmd = new CommandLine(app)
            cmd.executionStrategy = app
            cmd.caseInsensitiveEnumValuesAllowed = true
        expect:
            cmd.execute([opt, 'delete'] as String[]) == 0
        where:
            opt << ['--help', '--version']
    }
}
