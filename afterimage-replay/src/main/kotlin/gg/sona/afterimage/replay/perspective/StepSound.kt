package gg.sona.afterimage.replay.perspective

enum class StepSound(val sound: String, val volume: Float, val pitch: Float) {
    STONE("step.stone", 1f, 1f),
    WOOD("step.wood", 1f, 1f),
    GRAVEL("step.gravel", 1f, 1f),
    GRASS("step.grass", 1f, 1f),
    METAL("step.stone", 1f, 1.5f),
    CLOTH("step.cloth", 1f, 1f),
    SAND("step.sand", 1f, 1f),
    SNOW("step.snow", 1f, 1f),
    LADDER("step.ladder", 1f, 1f),
    ANVIL("step.anvil", 0.3f, 1f),
    SLIME("mob.slime.small", 1f, 1f);

    companion object {
        private val WOOD_BLOCKS = setOf(
            5,
            17,
            25,
            26,
            47,
            50,
            53,
            54,
            58,
            63,
            64,
            68,
            69,
            72,
            75,
            76,
            85,
            86,
            91,
            93,
            94,
            96,
            99,
            100,
            103,
            104,
            105,
            107,
            125,
            126,
            127,
            134,
            135,
            136,
            143,
            146,
            147,
            148,
            149,
            150,
            151,
            162,
            163,
            164,
            176,
            177,
            178,
            183,
            184,
            185,
            186,
            187,
            188,
            189,
            190,
            191,
            192,
            193,
            194,
            195,
            196,
            197
        )
        private val GRAVEL_BLOCKS = setOf(3, 13, 60, 82)
        private val GRASS_BLOCKS =
            setOf(2, 6, 18, 19, 31, 32, 37, 38, 39, 40, 46, 59, 83, 106, 110, 111, 115, 141, 142, 161, 170, 175)
        private val METAL_BLOCKS = setOf(27, 28, 41, 42, 52, 57, 66, 71, 101, 133, 152, 154, 157, 167)
        private val CLOTH_BLOCKS = setOf(35, 51, 81, 92, 171)
        private val SAND_BLOCKS = setOf(12, 88)
        private val SNOW_BLOCKS = setOf(78, 80)

        fun of(blockId: Int): StepSound = when (blockId) {
            in WOOD_BLOCKS -> WOOD
            in GRAVEL_BLOCKS -> GRAVEL
            in GRASS_BLOCKS -> GRASS
            in METAL_BLOCKS -> METAL
            in CLOTH_BLOCKS -> CLOTH
            in SAND_BLOCKS -> SAND
            in SNOW_BLOCKS -> SNOW
            65 -> LADDER
            145 -> ANVIL
            165 -> SLIME
            else -> STONE
        }
    }
}
