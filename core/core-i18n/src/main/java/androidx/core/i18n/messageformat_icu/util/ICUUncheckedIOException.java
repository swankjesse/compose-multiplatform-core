/*
 *******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation and
 * others. All Rights Reserved.
 *******************************************************************************
 */
package androidx.core.i18n.messageformat_icu.util;

import androidx.annotation.RestrictTo;

/**
 * Unchecked version of {@link java.io.IOException}.
 * Some ICU APIs do not throw the standard exception but instead wrap it
 * into this unchecked version.
 *
 * <p>This currently extends {@link RuntimeException},
 * but when ICU can rely on Java 8 this class should be changed to extend
 * java.io.UncheckedIOException instead.
 *
 * icu_annot::draft ICU 53
 * icu_annot::provisional This API might change or be removed in a future release.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class ICUUncheckedIOException extends RuntimeException {
    private static final long serialVersionUID = 1210263498513384449L;

    /**
     * Default constructor.
     *
     * icu_annot::draft ICU 53
     * icu_annot::provisional This API might change or be removed in a future release.
     */
    public ICUUncheckedIOException() {
    }

    /**
     * Constructor.
     *
     * @param message exception message string
     * icu_annot::draft ICU 53
     * icu_annot::provisional This API might change or be removed in a future release.
     */
    public ICUUncheckedIOException(String message) {
        super(message);
    }

    /**
     * Constructor.
     *
     * @param cause original exception (normally a {@link java.io.IOException})
     * icu_annot::draft ICU 53
     * icu_annot::provisional This API might change or be removed in a future release.
     */
    public ICUUncheckedIOException(Throwable cause) {
        super(cause);
    }

    /**
     * Constructor.
     *
     * @param message exception message string
     * @param cause original exception (normally a {@link java.io.IOException})
     * icu_annot::draft ICU 53
     * icu_annot::provisional This API might change or be removed in a future release.
     */
    public ICUUncheckedIOException(String message, Throwable cause) {
        super(message, cause);
    }
}
