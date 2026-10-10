package com.gala.motetv.launcher

data class AppDefinition(
    val id: String,
    val name: String,
    val packageId: String,
    val primaryDeepLink: String,
    val alternativeDeepLinks: List<String> = emptyList(),
    val accentColorHex: Long = 0xFF5B6CFF,
    val isFavorite: Boolean = false,
    val category: AppCategory = AppCategory.STREAMING,
    val isCustom: Boolean = false
) {
    /**
     * All launch URIs to try in order of priority:
     * 1. Primary Deep Link (e.g. native HTTPS / registered scheme)
     * 2. Alternative Deep Links
     * 3. Package launch intent format (market://launch?id=<packageId>)
     */
    fun getAllLaunchUris(): List<String> {
        val uris = mutableListOf<String>()
        if (primaryDeepLink.isNotBlank()) {
            uris.add(primaryDeepLink)
        }
        uris.addAll(alternativeDeepLinks)
        if (packageId.isNotBlank()) {
            val packageUri = "market://launch?id=$packageId"
            if (!uris.contains(packageUri)) {
                uris.add(packageUri)
            }
        }
        return uris
    }
}
