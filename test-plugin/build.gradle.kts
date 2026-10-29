paperPluginYaml {
    name = "RegionSplitReproducer"
    version = "1.0.0"
    main = "dev.slne.surf.testplugin.RegionSplitReproducer"
    description = "Reproduces the Folia region split + PlayerChunkUnloadEvent 'Async chunk retrieval' crash"
    authors = listOf("SLNE Development")
    foliaSupported = true
}

tasks.jar {
    archiveBaseName = "regionsplit-reproducer"
    archiveVersion = "1.0.0"
}
