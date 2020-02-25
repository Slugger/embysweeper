package com.github.slugger.emby.sweeper

import picocli.CommandLine

class VersionInfo implements CommandLine.IVersionProvider {
    @Delegate
    private Properties properties

    VersionInfo() {
        properties = new Properties()
        properties.load(VersionInfo.class.getResourceAsStream('/version.properties'))
    }

    VersionInfo(def src) {
        properties.load(src)
    }

    @Override
    String[] getVersion() throws Exception {
        [
            'EmbySweeper',
            "Build $properties.VERSION_DISPLAY",
            'https://github.com/Slugger/embysweeper'
        ] as String[]
    }
}
