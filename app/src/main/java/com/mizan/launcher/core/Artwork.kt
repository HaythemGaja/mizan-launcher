package com.mizan.launcher.core

/**
 * Artwork fit decisions (v2.9, spec §29) — pure, no Android imports.
 *
 * Ask C.14 (2026-10-23, from Haythem's photo of the v2.8 home):
 * "poster of media is not complete" — the Coming Soon landscape
 * cards centre-cropped his 2:3 posters into 16:9 frames and cut
 * the art off. The rule this object pins:
 *
 * - A frame may only CROP-FILL from source art of the SAME aspect
 *   family (landscape art into a landscape frame, a 2:3 poster
 *   into a 2:3 poster frame): the crop then removes nothing the
 *   frame wouldn't show anyway.
 * - When the only art available is a PORTRAIT poster and the frame
 *   is LANDSCAPE, the poster renders COMPLETE (fit) over a
 *   blurred/themed fill of itself — never centre-cropped.
 *
 * Every card renderer (home landscape/poster cards, the Details
 * backdrop header, IPTV cards) takes its scale decision from
 * here so the sim can pin the whole surface at once.
 */
object Artwork {

    /** How one image renders inside one frame. */
    enum class Fit {
        /** Fill the frame edge-to-edge (centre-crop). Only safe for aspect-matched art. */
        CROP_FILL,

        /** Show the WHOLE image (fit) over a blurred/themed fill of the same image. */
        FIT_COMPLETE
    }

    /**
     * A landscape (16:9) frame: landscape source art (a TMDB
     * backdrop or episode still) fills it; a portrait poster —
     * the only art some titles have — renders complete.
     */
    fun landscapeFit(sourceIsLandscape: Boolean): Fit =
        if (sourceIsLandscape) Fit.CROP_FILL else Fit.FIT_COMPLETE

    /**
     * A 2:3 poster frame showing a 2:3 poster: aspect-matched, so
     * the edge-to-edge fill cuts nothing.
     */
    fun posterFit(): Fit = Fit.CROP_FILL

    /**
     * The Details backdrop header (a wide frame): a real backdrop
     * fills it; when the title has ONLY a poster, the poster
     * renders complete instead of being cropped into the banner.
     */
    fun headerFit(hasBackdrop: Boolean): Fit =
        if (hasBackdrop) Fit.CROP_FILL else Fit.FIT_COMPLETE
}
