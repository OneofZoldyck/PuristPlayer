package com.brouken.player.subtitle;

/**
 * Purist: MIME types for subtitle formats Media3 doesn't parse natively. All are "text/*" so Media3 classifies
 * them as text tracks without registering custom types.
 */
public final class SubtitleMimes {
    public static final String TEXT_SAMI = "text/x-sami";
    public static final String TEXT_MICRODVD = "text/x-microdvd";
    public static final String TEXT_MPL2 = "text/x-mpl2";
    /** SubViewer 1/2 and YouTube SBV (same "h:mm:ss.fff,h:mm:ss.fff" timing lines). */
    public static final String TEXT_SUBVIEWER = "text/x-subviewer";
    public static final String TEXT_TMPLAYER = "text/x-tmplayer";

    /** Binary sidecars (standalone PGS .sup, VobSub .idx/.sub): handled separately, not by text parsers. */
    public static final String APPLICATION_PGS_SUP = "application/x-pgs-sup";
    public static final String APPLICATION_VOBSUB_IDX = "application/x-vobsub-idx";
    public static final String APPLICATION_VOBSUB_SUB = "application/x-vobsub-sub";

    private SubtitleMimes() {
    }

    public static boolean isBinarySidecar(String mime) {
        return APPLICATION_PGS_SUP.equals(mime) || APPLICATION_VOBSUB_IDX.equals(mime) || APPLICATION_VOBSUB_SUB.equals(mime);
    }

    /** Short display name for track labels, or null. */
    public static String displayName(String mime) {
        if (mime == null) return null;
        switch (mime) {
            case TEXT_SAMI: return "SAMI";
            case TEXT_MICRODVD: return "MicroDVD";
            case TEXT_MPL2: return "MPL2";
            case TEXT_SUBVIEWER: return "SubViewer";
            case TEXT_TMPLAYER: return "TMPlayer";
            default: return null;
        }
    }
}
