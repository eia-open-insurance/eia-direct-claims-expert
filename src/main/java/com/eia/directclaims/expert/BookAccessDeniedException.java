package com.eia.directclaims.expert;

/**
 * Thrown when a caller presents a book other than DIRECT. The API maps this to
 * 404 so that the expert never confirms whether an ASSUMED record exists.
 */
public class BookAccessDeniedException extends RuntimeException {
    public BookAccessDeniedException() {
        super("Not found");
    }
}
