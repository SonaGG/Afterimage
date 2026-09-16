package gg.sona.afterimage.mc189.protocol

import gg.sona.afterimage.world.EntityKind
import gg.sona.afterimage.world.GameNames

object EntityNames {
    val MOBS = mapOf(
        48 to "mob", 49 to "monster", 50 to "creeper", 51 to "skeleton", 52 to "spider", 53 to "giant",
        54 to "zombie", 55 to "slime", 56 to "ghast", 57 to "zombie pigman", 58 to "enderman", 59 to "cave spider",
        60 to "silverfish", 61 to "blaze", 62 to "magma cube", 63 to "ender dragon", 64 to "wither", 65 to "bat",
        66 to "witch", 67 to "endermite", 68 to "guardian", 90 to "pig", 91 to "sheep", 92 to "cow", 93 to "chicken",
        94 to "squid", 95 to "wolf", 96 to "mooshroom", 97 to "snow golem", 98 to "ocelot", 99 to "iron golem",
        100 to "horse", 101 to "rabbit", 120 to "villager",
    )

    val OBJECTS = mapOf(
        1 to "boat", 2 to "item", 10 to "minecart", 50 to "tnt", 51 to "ender crystal", 60 to "arrow",
        61 to "snowball", 62 to "egg", 63 to "fireball", 64 to "small fireball", 65 to "ender pearl",
        66 to "wither skull", 70 to "falling block", 71 to "item frame", 72 to "eye of ender", 73 to "potion",
        75 to "exp bottle", 76 to "firework", 77 to "leash knot", 78 to "armor stand", 90 to "fishing hook",
    )

    val PROJECTILES = setOf(60, 61, 62, 63, 64, 65, 66, 73, 75, 90)

}
