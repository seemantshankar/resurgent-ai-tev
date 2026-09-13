package com.resurgent.tev.parser.nomenclature;

import java.util.List;
import java.util.Optional;

/**
 * The vocabulary fragment sent with a Packet: global spine plus the mandate's
 * industry pack and overlay.
 */
public record OntologySlice(
        IndustryResolution industry,
        List<NomenclatureNode> nodes,
        List<NomenclatureAlias> aliases) {

    public Optional<NomenclatureNode> node(String path) {
        return nodes.stream().filter(n -> n.path().equals(path)).findFirst();
    }

    public Optional<String> leafPathForAlias(String aliasText) {
        return aliases.stream()
                .filter(a -> a.aliasText().equals(aliasText))
                .map(NomenclatureAlias::leafPath)
                .findFirst();
    }
}
