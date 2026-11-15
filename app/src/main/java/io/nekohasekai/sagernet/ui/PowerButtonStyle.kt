package app.rawline.mobile.ui

import app.rawline.mobile.database.DataStore

/** Visual-only preferences; these never alter routing, transports or the service state. */
object PowerButtonStyle {
    const val SKIN = "localPowerSkin"
    const val ARCS = "localPowerArcs"
    const val FURNACE = "localPowerFurnace"
    const val SNOW = "localPowerSnow"
    val courtyard get() = DataStore.configurationStore.getString(SKIN, "courtyard") != "hatch"
    val arcs get() = DataStore.configurationStore.getInt(ARCS, 100).coerceIn(0, 180) / 100f
    val furnace get() = DataStore.configurationStore.getInt(FURNACE, 100).coerceIn(0, 160) / 100f
    val snow get() = DataStore.configurationStore.getInt(SNOW, 75).coerceIn(0, 150) / 100f
}
