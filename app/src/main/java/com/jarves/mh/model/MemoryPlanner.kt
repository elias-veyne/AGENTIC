package com.jarves.mh.model

/**
 * Chooses a model configuration that fits the device, and refuses loads that will not.
 *
 * ## Why this is arithmetic and not a guess
 *
 * A 35B Q4_K_M weight file is around 20 GB and the PRD's mid and high tiers both
 * assume it. On a phone the failure is not a slow generation, it is the kernel
 * killing the process mid-load and taking the user's session with it. Deciding before
 * the load whether it will fit is the only point where the answer is still cheap.
 *
 * Every multiplier here is the PRD's, named so the constants can be checked against
 * section 2.4 rather than buried in an expression.
 */
class MemoryPlanner(
    /** Weight-file overhead: mmap pages, allocator slack, tokenizer and config. */
    private val weightOverhead: Double = WEIGHT_OVERHEAD,
) {
    /**
     * Device classes from PRD table 6.
     *
     * Ordered low to high; [forRam] selects by available RAM, not total RAM, because
     * a device with 12 GB installed but 2 GB free should be planned as Entry.
     */
    enum class DeviceTier(val minRamBytes: Long) {
        /** Small local model, no draft head, short context. */
        ENTRY(3_200_000_000L),

        /** 35B MoE at Q4_K_M with a draft head. */
        MID(4_500_000_000L),

        /** 35B MoE at Q5_K_M with a draft head and the longest context. */
        HIGH(8_000_000_000L),
        ;

        companion object {
            /**
             * The highest tier [availableRamBytes] supports.
             *
             * Below [ENTRY]'s floor this still returns [ENTRY]: there is no tier
             * below it, and refusing outright would leave a 3 GB phone with no local
             * option at all. [MemoryPlanner.canLoad] is what actually refuses.
             *
             * Written with `lastOrNull` rather than `last`. `last` throws
             * NoSuchElementException when nothing matches, which is exactly the
             * under-3 GB case, so the version that reads as equivalent would crash the
             * planner on the smallest devices it exists to serve.
             */
            fun forRam(availableRamBytes: Long): DeviceTier =
                entries.filter { availableRamBytes >= it.minRamBytes }.lastOrNull() ?: DeviceTier.ENTRY
        }
    }

    /** How a model is loaded for a given tier. */
    data class ModelProfile(
        val tier: DeviceTier,
        val quantisation: String,
        val contextTokens: Int,
        val speculativeDraft: Boolean,
    )

    /**
     * The configuration recommended for [availableRamBytes].
     *
     * Speculative decoding is off on [DeviceTier.ENTRY]. The draft head is itself a
     * 1B model: on the tier that exists because the device is small, loading a second
     * model to accelerate a third is how the tier stops fitting.
     */
    fun recommend(availableRamBytes: Long): ModelProfile =
        when (DeviceTier.forRam(availableRamBytes)) {
            DeviceTier.ENTRY -> ModelProfile(DeviceTier.ENTRY, "IQ4_XS", 4_096, speculativeDraft = false)
            DeviceTier.MID -> ModelProfile(DeviceTier.MID, "Q4_K_M", 8_192, speculativeDraft = true)
            DeviceTier.HIGH -> ModelProfile(DeviceTier.HIGH, "Q5_K_M", 16_384, speculativeDraft = true)
        }

    /**
     * Bytes of KV cache for [contextTokens] at [kvBytesPerToken].
     *
     * The caller supplies the per-token cost because it depends on the model's layer
     * count, head count and head dimension, which are properties of the checkpoint
     * rather than of the device. A wrong estimate here is the one input that can make
     * a load that fits on paper fail in practice, so it is not guessed internally.
     */
    fun kvCacheBytes(contextTokens: Int, kvBytesPerToken: Long): Long =
        contextTokens.toLong() * kvBytesPerToken

    /**
     * Peak resident set expected when loading [weightBytes] at [contextTokens].
     *
     * PRD 2.4.2: weight size times the weight overhead, plus the KV cache estimate.
     */
    fun projectedRss(weightBytes: Long, contextTokens: Int, kvBytesPerToken: Long): Long =
        (weightBytes * weightOverhead).toLong() + kvCacheBytes(contextTokens, kvBytesPerToken)

    /**
     * Whether a model projecting [projectedBytes] should be loaded with
     * [availableRamBytes] free.
     *
     * Applies the PRD's 1.2x headroom on top of the projection, so a load that only
     * *just* fits is still refused: the runtime also needs room for the app, the
     * Compose UI and the inference process's own overhead, none of which is in the
     * weight file.
     */
    fun canLoad(availableRamBytes: Long, projectedBytes: Long): Boolean =
        availableRamBytes >= projectedBytes * LOAD_HEADROOM

    /**
     * The reason a load was refused, for the message shown to the user.
     *
     * Kept as data rather than a formatted string so callers can localise it and so
     * tests can assert on the tier suggestion instead of on wording.
     */
    data class Refusal(
        val availableRamBytes: Long,
        val projectedBytes: Long,
        val suggested: ModelProfile,
    )

    /**
     * Refuse a load that does not fit, suggesting the next tier down.
     *
     * @return null when the load fits.
     */
    fun refusalOrNull(
        availableRamBytes: Long,
        weightBytes: Long,
        contextTokens: Int,
        kvBytesPerToken: Long,
    ): Refusal? {
        val projected = projectedRss(weightBytes, contextTokens, kvBytesPerToken)
        if (canLoad(availableRamBytes, projected)) return null

        val current = recommend(availableRamBytes).tier
        val suggested = when (current) {
            // Already the smallest tier: there is nothing cheaper to suggest, so the
            // caller has to tell the user this device cannot run the model rather
            // than pointing at another model that will not fit either.
            DeviceTier.ENTRY -> recommend(availableRamBytes)
            DeviceTier.MID -> recommend(DeviceTier.ENTRY.minRamBytes)
            DeviceTier.HIGH -> recommend(DeviceTier.MID.minRamBytes)
        }
        return Refusal(availableRamBytes, projected, suggested)
    }

    companion object {
        /** PRD 2.4.2 multiplier on the weight file size. */
        const val WEIGHT_OVERHEAD = 1.15

        /** PRD 2.4.2 multiplier applied to the whole projection before loading. */
        const val LOAD_HEADROOM = 1.2
    }
}
