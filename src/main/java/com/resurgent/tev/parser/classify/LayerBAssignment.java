package com.resurgent.tev.parser.classify;

/**
 * One LLM path, either for every still-unbound cell on {@code row} or for one
 * {@code coord}. The graph still decides amount role.
 */
public record LayerBAssignment(Integer row, String coord, String pathRoot, String path) {

    public LayerBAssignment {
        if (pathRoot != null) {
            pathRoot = pathRoot.trim().toLowerCase(java.util.Locale.ROOT);
        }
        if (path != null) {
            path = path.trim();
        }
        if (coord != null) {
            coord = coord.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }
}
