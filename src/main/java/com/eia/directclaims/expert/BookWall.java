package com.eia.directclaims.expert;

/**
 * Book wall for the internal expert API. Direct Claims is fixed to the DIRECT
 * book (SPEC §3.6.1); anything else is treated as a non-existent resource.
 */
public final class BookWall {
    private BookWall() {
    }

    public static void requireDirect(String bookHeader) {
        if (!"DIRECT".equals(bookHeader)) {
            throw new BookAccessDeniedException();
        }
    }
}
