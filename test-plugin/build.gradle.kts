paperPluginYaml {
    name = "ReconfigurationReproducer"
    version = "1.0.0"
    main = "dev.slne.surf.testplugin.ReconfigurationReproducer"
    authors = listOf("SLNE Development")
    foliaSupported = false
}

tasks.jar {
    archiveBaseName = "reconfiguration-reproducer"
    archiveVersion = "1.0.0"
}
