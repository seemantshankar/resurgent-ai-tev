package com.resurgent.tev.parser.config;

/**
 * The supplied configuration file is invalid: unknown key, wrong type, value out of
 * range, or an attempt to disable a security protection. Mapped to exit code 2.
 */
public class ConfigValidationException extends RuntimeException {

    public ConfigValidationException(String message) {
        super(message);
    }
}
