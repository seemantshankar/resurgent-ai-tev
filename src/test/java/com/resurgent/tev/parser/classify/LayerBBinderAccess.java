package com.resurgent.tev.parser.classify;

import java.util.Map;

/** Test-only bridge to the package-private alias index. */
public final class LayerBBinderAccess {
    private LayerBBinderAccess() {}

    public static Map<String, String> aliasIndex() {
        return LayerBBinder.aliasIndex();
    }

    public static String norm(String text) {
        return LayerBBinder.norm(text);
    }
}
