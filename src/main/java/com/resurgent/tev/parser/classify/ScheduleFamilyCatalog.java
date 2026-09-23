package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The schedule families a classify run may use. Starts from the seven seed
 * names plus any category admitted by an earlier run. A new name is added when
 * a packet fits none of the current list.
 */
final class ScheduleFamilyCatalog {

    private final Set<String> admitted = new LinkedHashSet<>();

    static ScheduleFamilyCatalog seeded() {
        return new ScheduleFamilyCatalog();
    }

    synchronized void restore(Collection<String> stored) {
        if (stored == null) {
            return;
        }
        for (String name : stored) {
            admit(name);
        }
    }

    /** @return true when {@code name} is a new category this catalog did not have */
    synchronized boolean admit(String name) {
        String family = ScheduleFamily.isKnown(name) ? null : ScheduleFamily.newFamily(name);
        if (family == null || admitted.contains(family)) {
            return false;
        }
        admitted.add(family);
        return true;
    }

    synchronized boolean contains(String name) {
        return ScheduleFamily.isKnown(name) || admitted.contains(name);
    }

    synchronized List<String> names() {
        List<String> names = new ArrayList<>(ScheduleFamily.seeds());
        names.addAll(admitted);
        return List.copyOf(names);
    }

    synchronized Set<String> admitted() {
        return Set.copyOf(admitted);
    }
}
