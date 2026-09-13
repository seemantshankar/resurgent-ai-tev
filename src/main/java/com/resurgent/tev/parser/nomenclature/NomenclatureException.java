package com.resurgent.tev.parser.nomenclature;

/** Domain failure loading or extending the controlled vocabulary. */
public final class NomenclatureException extends RuntimeException {

    public NomenclatureException(String message) {
        super(message);
    }

    public NomenclatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
