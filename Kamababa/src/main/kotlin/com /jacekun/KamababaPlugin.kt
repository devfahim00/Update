package com.jacekun

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudStreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudStreamPlugin
class KamababaPlugin : Plugin() {
    override fun load(context: Context) {
        // Register main providers
        registerMainAPI(Kamababa())
        
        // Register additional providers if needed
        // registerMainAPI(AnotherProvider())
        
        // Register extractors if needed
        // registerExtractorAPI(Extractor())
    }
}
