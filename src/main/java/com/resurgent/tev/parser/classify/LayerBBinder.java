package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.BindCellRow;
import com.resurgent.tev.parser.nomenclature.NomenclatureAlias;
import com.resurgent.tev.parser.nomenclature.NomenclatureSeed;
import com.resurgent.tev.parser.nomenclature.ProjectFactField;
import com.resurgent.tev.parser.nomenclature.ProjectFactSeed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assigns one nomenclature path to each eligible cell. Formula errors are
 * omitted. Amount role comes from the formula graph: a single-cell restatement
 * is a helper, a SUM is a total, and a SUM member is an add. Residuals that are
 * not members stay unbound.
 */
final class LayerBBinder {

    static final String LEGAL_NAME = "Project Identity > Legal Name";
    static final String CONSTITUTION = "Project Identity > Constitution / Entity Type";
    static final String PARTNERS = "Project Identity > Partners / Promoters";
    static final String ADDRESS = "Project Identity > Address";
    static final String POWER = "Project > Power Connection";
    static final String MANPOWER = "Project > Manpower";
    static final String CAPACITY = "Project > Installed Capacity";

    static final String FRAME_TITLE = "Frame > Schedule Title";
    static final String FRAME_ANNEXURE = "Frame > Annexure";
    static final String FRAME_BANNER = "Frame > Section Banner";
    static final String FRAME_PERIOD = "Frame > Period";
    static final String FRAME_SCALE = "Frame > Scale";
    static final String FRAME_MARK = "Frame > Field Mark";
    static final String FRAME_BLANK = "Frame > Blank";

    private static final Pattern PERIOD = Pattern.compile(
            "(?i)^(year(\\s+\\d+)?|construction|fy\\s*\\d{2,4})$");
    private static final Pattern FIELD_MARK = Pattern.compile("(?i)^(\\d+\\)|:)$");
    private static final Pattern ANNEXURE = Pattern.compile("(?i)^annexure\\b.*");
    private static final Pattern SCALE = Pattern.compile(
            "(?i).*(\\brs\\.?\\s*in\\s+(lakhs|lacs|crores?|million)\\b|\\bin\\s+lakhs\\b"
                    + "|\\(amt\\.?\\s*in\\s+rs\\.?\\)|amt\\.?\\s*in\\s+rs\\.?|\\bin\\s+rs\\.?\\)).*");
    private static final Pattern CROSS_SHEET = Pattern.compile(
            "(?i)^\\+?(?:'[^']+'|[A-Za-z][A-Za-z0-9_ ]*)!\\$?[A-Z]{1,3}\\$?\\d+$");
    private static final Pattern SAME_SHEET = Pattern.compile(
            "(?i)^\\+?\\$?([A-Z]{1,3})\\$?(\\d+)$");
    private static final Pattern SUM = Pattern.compile("(?i)^SUM\\(([^)]+)\\)$");
    private static final Pattern RESCALE = Pattern.compile(
            "(?i)^\\+?(?:ROUND\\()?\\$?([A-Z]{1,3})\\$?(\\d+)\\s*[*/]\\s*[\\d.]+(?:\\s*,\\s*-?\\d+\\))?$");
    private static final Pattern CATEGORY = Pattern.compile("^[A-Za-z][A-Za-z0-9 /&'\\-]{0,48}$");
    private static final Pattern A1 = Pattern.compile("(?i)\\$?([A-Z]{1,3})\\$?(\\d+)");

    private LayerBBinder() {}

    record Draft(long cellId, String coord, String pathRoot, String path, String amountRole, String verbatim) {}

    static Map<String, String> aliasIndex() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String path : NomenclatureSeed.SPINE_PATHS) {
            putAlias(aliases, NomenclatureSeed.nameOf(path), path);
            putAlias(aliases, path, path);
        }
        for (NomenclatureAlias alias : NomenclatureSeed.SPINE_ALIASES) {
            putAlias(aliases, alias.aliasText(), alias.leafPath());
        }
        for (String path : NomenclatureSeed.HOTEL_LEAVES) {
            putAlias(aliases, NomenclatureSeed.nameOf(path), path);
        }
        for (NomenclatureAlias alias : NomenclatureSeed.HOTEL_ALIASES) {
            putAlias(aliases, alias.aliasText(), alias.leafPath());
        }
        for (ProjectFactField field : ProjectFactSeed.FIELDS) {
            putAlias(aliases, field.name(), field.path());
            putAlias(aliases, field.path(), field.path());
            putAlias(aliases, NomenclatureSeed.nameOf(field.path()), field.path());
        }
        return Map.copyOf(aliases);
    }

    /** Alias index from a living ontology slice, then the seed defaults. */
    static Map<String, String> aliasIndex(Map<String, String> living) {
        Map<String, String> aliases = new LinkedHashMap<>(aliasIndex());
        if (living != null) {
            for (Map.Entry<String, String> entry : living.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                putAlias(aliases, entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(aliases);
    }

    static List<String> allowedPaths() {
        List<String> paths = new ArrayList<>();
        paths.addAll(NomenclatureSeed.SPINE_PATHS);
        paths.addAll(NomenclatureSeed.HOTEL_LEAVES);
        for (ProjectFactField field : ProjectFactSeed.FIELDS) {
            paths.add(field.path());
        }
        paths.add(FRAME_TITLE);
        paths.add(FRAME_ANNEXURE);
        paths.add(FRAME_BANNER);
        paths.add(FRAME_PERIOD);
        paths.add(FRAME_SCALE);
        paths.add(FRAME_MARK);
        paths.add(FRAME_BLANK);
        return List.copyOf(paths);
    }

    static List<String> allowedPaths(List<String> living) {
        LinkedHashMap<String, Boolean> paths = new LinkedHashMap<>();
        for (String path : allowedPaths()) {
            paths.put(path, Boolean.TRUE);
        }
        if (living != null) {
            for (String path : living) {
                if (path != null && !path.isBlank()) {
                    paths.put(path, Boolean.TRUE);
                }
            }
        }
        return List.copyOf(paths.keySet());
    }

    static List<Draft> bind(
            List<BindCellRow> cells, List<LayerBAssignment> fromLlm, Map<String, String> aliases) {
        Map<String, BindCellRow> byCoord = new LinkedHashMap<>();
        for (BindCellRow cell : cells) {
            byCoord.put(cell.coord().toUpperCase(Locale.ROOT), cell);
        }
        Map<String, Draft> out = new LinkedHashMap<>();
        prove(cells, aliases, out);
        inheritMerges(cells, byCoord, out);
        applyLlm(cells, fromLlm, out);
        placeFirmNameInsideAddress(cells, out);
        inheritMerges(cells, byCoord, out);
        continueIdentity(cells, aliases, out);
        markSectionBanners(cells, out);
        shareTotalWord(cells, out);
        inheritMerges(cells, byCoord, out);
        applyRoles(cells, out);
        List<Draft> drafts = new ArrayList<>();
        for (BindCellRow cell : cells) {
            if (cell.error()) {
                continue;
            }
            Draft draft = out.get(cell.coord().toUpperCase(Locale.ROOT));
            if (draft != null) {
                drafts.add(draft);
            }
        }
        return List.copyOf(drafts);
    }

    static List<BindCellRow> unbound(List<BindCellRow> cells, List<Draft> drafts) {
        Map<String, Draft> byCoord = new LinkedHashMap<>();
        for (Draft draft : drafts) {
            byCoord.put(draft.coord(), draft);
        }
        List<BindCellRow> missing = new ArrayList<>();
        for (BindCellRow cell : cells) {
            if (cell.error()) {
                continue;
            }
            if (!byCoord.containsKey(cell.coord().toUpperCase(Locale.ROOT))) {
                missing.add(cell);
            }
        }
        return missing;
    }

    private static void prove(
            List<BindCellRow> cells, Map<String, String> aliases, Map<String, Draft> out) {
        Map<Integer, List<BindCellRow>> rows = rowsOf(cells);
        for (BindCellRow cell : cells) {
            if (cell.error() || cell.textValue() == null || cell.textValue().isBlank()) {
                continue;
            }
            String text = cell.textValue().trim();
            if (PERIOD.matcher(text).matches()) {
                put(out, cell, "frame", FRAME_PERIOD);
            } else if (ANNEXURE.matcher(text).matches()) {
                put(out, cell, "frame", FRAME_ANNEXURE);
            } else if (SCALE.matcher(text).matches()) {
                put(out, cell, "frame", FRAME_SCALE);
            } else if (FIELD_MARK.matcher(text).matches()) {
                put(out, cell, "frame", FRAME_MARK);
            }
        }
        for (BindCellRow cell : cells) {
            if (cell.error() || bound(out, cell)) {
                continue;
            }
            if ("empty".equals(cell.valueType()) && !cell.mergedParticipant()) {
                put(out, cell, "frame", FRAME_BLANK);
            } else if (isScheduleTitle(cell, rows.get(cell.rowNum()))) {
                put(out, cell, "frame", FRAME_TITLE);
            } else if (pagesRef(cell)) {
                put(out, cell, "frame", FRAME_ANNEXURE);
            }
        }
        bindIdentityRows(rows, aliases, out);
        bindEconomicRows(rows, aliases, out);
        bindSumHeads(cells, out);
        for (BindCellRow cell : cells) {
            if (cell.error() || bound(out, cell) || cell.textValue() == null) {
                continue;
            }
            String path = aliases.get(norm(cell.textValue()));
            if (path != null && !path.isBlank()) {
                put(out, cell, "frame", FRAME_BANNER);
            }
        }
    }

    private static void bindIdentityRows(
            Map<Integer, List<BindCellRow>> rows,
            Map<String, String> aliases,
            Map<String, Draft> out) {
        for (List<BindCellRow> row : rows.values()) {
            IdentityField field = identityField(row, aliases);
            if (field == null) {
                continue;
            }
            for (BindCellRow cell : row) {
                if (cell.error() || bound(out, cell)) {
                    continue;
                }
                if (cell.colNum() == field.label().colNum()
                        || isValueCell(cell, field.label())) {
                    put(out, cell, field.root(), field.path());
                }
            }
        }
    }

    private static void bindSumHeads(List<BindCellRow> cells, Map<String, Draft> out) {
        Map<Integer, List<BindCellRow>> rows = rowsOf(cells);
        for (BindCellRow cell : cells) {
            if (cell.error() || bound(out, cell) || !"number".equals(cell.valueType())) {
                continue;
            }
            String formula = stripped(cell.formulaText());
            if (!SUM.matcher(formula).matches()) {
                continue;
            }
            String path = null;
            for (String coord : membersOfSum(cell, cells)) {
                Draft draft = out.get(coord);
                if (draft != null && "economic".equals(draft.pathRoot()) && moneyPath(draft.path())) {
                    path = draft.path();
                    break;
                }
            }
            if (path == null) {
                continue;
            }
            put(out, cell, "economic", path);
            for (BindCellRow sibling : rows.getOrDefault(cell.rowNum(), List.of())) {
                if (sibling.error() || bound(out, sibling) || sibling.textValue() == null) {
                    continue;
                }
                if ("TOTAL".equals(norm(sibling.textValue()))) {
                    put(out, sibling, "economic", path);
                }
            }
        }
    }

    private static Set<String> membersOfSum(BindCellRow head, List<BindCellRow> cells) {
        Map<String, BindCellRow> byCoord = new LinkedHashMap<>();
        for (BindCellRow cell : cells) {
            byCoord.put(cell.coord().toUpperCase(Locale.ROOT), cell);
        }
        Set<String> members = new java.util.HashSet<>();
        String formula = stripped(head.formulaText());
        var matcher = SUM.matcher(formula);
        if (!matcher.matches()) {
            return Set.of();
        }
        for (String part : matcher.group(1).split(",")) {
            String token = part.trim();
            int colon = token.indexOf(':');
            if (colon < 0) {
                String coord = normalizeA1(token);
                if (coord != null && byCoord.containsKey(coord)) {
                    members.add(coord);
                }
                continue;
            }
            String start = normalizeA1(token.substring(0, colon));
            String end = normalizeA1(token.substring(colon + 1));
            if (start == null || end == null) {
                continue;
            }
            int startCol = colOf(start);
            int startRow = rowOf(start);
            int endCol = colOf(end);
            int endRow = rowOf(end);
            for (int row = Math.min(startRow, endRow); row <= Math.max(startRow, endRow); row++) {
                for (int col = Math.min(startCol, endCol); col <= Math.max(startCol, endCol); col++) {
                    String coord = a1(col, row);
                    if (byCoord.containsKey(coord)) {
                        members.add(coord);
                    }
                }
            }
        }
        return Set.copyOf(members);
    }

    private static void bindEconomicRows(
            Map<Integer, List<BindCellRow>> rows,
            Map<String, String> aliases,
            Map<String, Draft> out) {
        for (List<BindCellRow> row : rows.values()) {
            List<BindCellRow> amounts = new ArrayList<>();
            for (BindCellRow cell : row) {
                if (!cell.error() && "number".equals(cell.valueType()) && !bound(out, cell)) {
                    amounts.add(cell);
                }
            }
            if (amounts.isEmpty()) {
                continue;
            }
            BindCellRow label = null;
            String path = null;
            for (BindCellRow amount : amounts) {
                BindCellRow nearest = nearestAlias(row, amount, aliases, out);
                if (nearest == null) {
                    continue;
                }
                String matched = aliases.get(norm(nearest.textValue()));
                if (matched == null || matched.isBlank()) {
                    continue;
                }
                label = nearest;
                path = matched;
                break;
            }
            if (path == null) {
                continue;
            }
            put(out, label, "economic", path);
            for (BindCellRow amount : amounts) {
                if (!bound(out, amount)) {
                    put(out, amount, "economic", path);
                }
            }
        }
    }

    private static void placeFirmNameInsideAddress(
            List<BindCellRow> cells, Map<String, Draft> out) {
        for (BindCellRow cell : cells) {
            if (cell.error()) {
                continue;
            }
            String ref = sameSheetRef(cell.formulaText());
            if (ref == null) {
                continue;
            }
            Draft source = out.get(ref);
            if (source == null || !LEGAL_NAME.equals(source.path())) {
                continue;
            }
            if (cell.coord().equalsIgnoreCase(ref)) {
                continue;
            }
            Draft current = out.get(cell.coord().toUpperCase(Locale.ROOT));
            if (current == null || LEGAL_NAME.equals(current.path())) {
                put(out, cell, "identity", ADDRESS);
            }
        }
    }

    private static void continueIdentity(
            List<BindCellRow> cells, Map<String, String> aliases, Map<String, Draft> out) {
        Map<Integer, List<BindCellRow>> rows = rowsOf(cells);
        List<Integer> rowNums = new ArrayList<>(rows.keySet());
        rowNums.sort(Integer::compareTo);
        String carryRoot = null;
        String carryPath = null;
        int carryCol = -1;
        for (int rowNum : rowNums) {
            List<BindCellRow> row = rows.get(rowNum);
            if (startsNewField(row, aliases)) {
                IdentityField field = identityField(row, aliases);
                if (field != null && "identity".equals(field.root())) {
                    BindCellRow value = valueOn(row, field.label(), out);
                    carryRoot = field.root();
                    carryPath = field.path();
                    carryCol = value != null ? value.colNum() : field.label().colNum();
                } else {
                    carryRoot = null;
                    carryPath = null;
                    carryCol = -1;
                }
                continue;
            }
            if (carryPath == null) {
                continue;
            }
            BindCellRow continued = null;
            boolean foreign = false;
            for (BindCellRow cell : row) {
                if (cell.error() || "empty".equals(cell.valueType())) {
                    continue;
                }
                if (cell.colNum() == carryCol && cell.textValue() != null && !cell.textValue().isBlank()) {
                    continued = cell;
                } else if (!bound(out, cell) || !"frame".equals(rootOf(out, cell))) {
                    foreign = true;
                }
            }
            if (foreign || continued == null) {
                carryRoot = null;
                carryPath = null;
                carryCol = -1;
                continue;
            }
            if (bound(out, continued)) {
                Draft existing = out.get(continued.coord().toUpperCase(Locale.ROOT));
                if (existing != null && "identity".equals(existing.pathRoot())) {
                    carryRoot = existing.pathRoot();
                    carryPath = existing.path();
                }
                continue;
            }
            put(out, continued, carryRoot, carryPath);
        }
    }

    private static void shareTotalWord(List<BindCellRow> cells, Map<String, Draft> out) {
        for (List<BindCellRow> row : rowsOf(cells).values()) {
            String path = null;
            for (BindCellRow cell : row) {
                Draft draft = out.get(cell.coord().toUpperCase(Locale.ROOT));
                if (draft != null && "economic".equals(draft.pathRoot())) {
                    path = draft.path();
                    break;
                }
            }
            if (path == null) {
                continue;
            }
            for (BindCellRow cell : row) {
                if (cell.error() || bound(out, cell) || cell.textValue() == null) {
                    continue;
                }
                if ("TOTAL".equals(norm(cell.textValue()))) {
                    put(out, cell, "economic", path);
                }
            }
        }
    }

    private static void applyLlm(
            List<BindCellRow> cells, List<LayerBAssignment> fromLlm, Map<String, Draft> out) {
        if (fromLlm == null || fromLlm.isEmpty()) {
            return;
        }
        Map<Integer, List<BindCellRow>> rows = rowsOf(cells);
        for (LayerBAssignment assignment : fromLlm) {
            if (!allowed(assignment.pathRoot(), assignment.path())) {
                continue;
            }
            if (assignment.coord() != null) {
                for (BindCellRow cell : cells) {
                    if (cell.coord().equalsIgnoreCase(assignment.coord()) && !cell.error()
                            && !bound(out, cell) && accepts(cell, assignment)) {
                        put(out, cell, assignment.pathRoot(), assignment.path());
                    }
                }
            } else if (assignment.row() != null) {
                for (BindCellRow cell : rows.getOrDefault(assignment.row(), List.of())) {
                    if (!cell.error() && !bound(out, cell) && accepts(cell, assignment)) {
                        put(out, cell, assignment.pathRoot(), assignment.path());
                    }
                }
            }
        }
    }

    private static boolean accepts(BindCellRow cell, LayerBAssignment assignment) {
        if (!"number".equals(cell.valueType())) {
            return true;
        }
        return "economic".equals(assignment.pathRoot());
    }

    private static void inheritMerges(
            List<BindCellRow> cells, Map<String, BindCellRow> byCoord, Map<String, Draft> out) {
        for (BindCellRow cell : cells) {
            if (cell.error() || !cell.mergedParticipant() || bound(out, cell)) {
                continue;
            }
            String anchor = anchorCoord(cell.mergedRange());
            if (anchor == null) {
                continue;
            }
            Draft source = out.get(anchor);
            if (source == null) {
                continue;
            }
            BindCellRow anchorCell = byCoord.get(anchor);
            if (anchorCell == null) {
                continue;
            }
            put(out, cell, source.pathRoot(), source.path());
        }
    }

    private static void applyRoles(List<BindCellRow> cells, Map<String, Draft> out) {
        Map<String, BindCellRow> byCoord = new LinkedHashMap<>();
        for (BindCellRow cell : cells) {
            byCoord.put(cell.coord().toUpperCase(Locale.ROOT), cell);
        }
        Set<String> sumMembers = sumMembers(cells);
        boolean hasSum = !sumMembers.isEmpty();
        List<String> drop = new ArrayList<>();
        for (BindCellRow cell : cells) {
            Draft draft = out.get(cell.coord().toUpperCase(Locale.ROOT));
            if (draft == null || !"economic".equals(draft.pathRoot())) {
                continue;
            }
            if (!"number".equals(cell.valueType()) || !moneyPath(draft.path())) {
                continue;
            }
            String formula = stripped(cell.formulaText());
            String role;
            String path = draft.path();
            String root = draft.pathRoot();
            if (CROSS_SHEET.matcher(formula).matches()) {
                role = "helper";
            } else if (SUM.matcher(formula).matches()) {
                role = "total";
            } else {
                String source = singleSourceCoord(formula);
                if (source != null) {
                    Draft sourceDraft = out.get(source);
                    if (sourceDraft != null && "economic".equals(sourceDraft.pathRoot())) {
                        path = sourceDraft.path();
                        root = sourceDraft.pathRoot();
                    }
                    role = "helper";
                } else if (!hasSum || sumMembers.contains(cell.coord().toUpperCase(Locale.ROOT))) {
                    role = "add";
                } else {
                    drop.add(cell.coord().toUpperCase(Locale.ROOT));
                    continue;
                }
            }
            out.put(draft.coord(), new Draft(
                    draft.cellId(), draft.coord(), root, path, role, draft.verbatim()));
        }
        for (String coord : drop) {
            out.remove(coord);
        }
    }

    static String amountRole(BindCellRow cell, String pathRoot, String path) {
        if (!"economic".equals(pathRoot) || !"number".equals(cell.valueType()) || !moneyPath(path)) {
            return null;
        }
        String formula = stripped(cell.formulaText());
        if (CROSS_SHEET.matcher(formula).matches() || singleSourceCoord(formula) != null) {
            return "helper";
        }
        if (SUM.matcher(formula).matches()) {
            return "total";
        }
        return "add";
    }

    /** Coords covered by SUM(...) ranges in this Candidate. */
    static Set<String> sumMembers(List<BindCellRow> cells) {
        Set<String> members = new java.util.HashSet<>();
        Map<String, BindCellRow> byCoord = new LinkedHashMap<>();
        for (BindCellRow cell : cells) {
            byCoord.put(cell.coord().toUpperCase(Locale.ROOT), cell);
        }
        for (BindCellRow cell : cells) {
            String formula = stripped(cell.formulaText());
            var matcher = SUM.matcher(formula);
            if (!matcher.matches()) {
                continue;
            }
            for (String part : matcher.group(1).split(",")) {
                String token = part.trim();
                int colon = token.indexOf(':');
                if (colon < 0) {
                    String coord = normalizeA1(token);
                    if (coord != null && byCoord.containsKey(coord)) {
                        members.add(coord);
                    }
                    continue;
                }
                String start = normalizeA1(token.substring(0, colon));
                String end = normalizeA1(token.substring(colon + 1));
                if (start == null || end == null) {
                    continue;
                }
                int startCol = colOf(start);
                int startRow = rowOf(start);
                int endCol = colOf(end);
                int endRow = rowOf(end);
                for (int row = Math.min(startRow, endRow); row <= Math.max(startRow, endRow); row++) {
                    for (int col = Math.min(startCol, endCol); col <= Math.max(startCol, endCol); col++) {
                        String coord = a1(col, row);
                        if (byCoord.containsKey(coord)) {
                            members.add(coord);
                        }
                    }
                }
            }
        }
        return Set.copyOf(members);
    }

    private static String singleSourceCoord(String formula) {
        if (formula == null || formula.isBlank()) {
            return null;
        }
        var same = SAME_SHEET.matcher(formula);
        if (same.matches()) {
            return same.group(1).toUpperCase(Locale.ROOT) + same.group(2);
        }
        var rescale = RESCALE.matcher(formula);
        if (rescale.matches()) {
            return rescale.group(1).toUpperCase(Locale.ROOT) + rescale.group(2);
        }
        return null;
    }

    private static String normalizeA1(String token) {
        if (token == null) {
            return null;
        }
        var matcher = A1.matcher(token.trim());
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1).toUpperCase(Locale.ROOT) + matcher.group(2);
    }

    private static int colOf(String a1) {
        int col = 0;
        for (int i = 0; i < a1.length(); i++) {
            char ch = a1.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                col = col * 26 + (ch - 'A' + 1);
            } else {
                break;
            }
        }
        return col;
    }

    private static int rowOf(String a1) {
        int i = 0;
        while (i < a1.length() && a1.charAt(i) >= 'A' && a1.charAt(i) <= 'Z') {
            i++;
        }
        return Integer.parseInt(a1.substring(i));
    }

    private static String a1(int col, int row) {
        StringBuilder letters = new StringBuilder();
        int value = col;
        while (value > 0) {
            value--;
            letters.insert(0, (char) ('A' + (value % 26)));
            value /= 26;
        }
        return letters.toString() + row;
    }

    private static boolean moneyPath(String path) {
        return path.startsWith("Project Cost")
                || path.startsWith("Means of Finance")
                || path.startsWith("Profit & Loss")
                || path.startsWith("Balance Sheet")
                || path.startsWith("Cash Flow");
    }

    static boolean allowed(String root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return false;
        }
        if ("frame".equals(root)) {
            return path.equals(FRAME_TITLE)
                    || path.equals(FRAME_ANNEXURE)
                    || path.equals(FRAME_BANNER)
                    || path.equals(FRAME_PERIOD)
                    || path.equals(FRAME_SCALE)
                    || path.equals(FRAME_MARK)
                    || path.equals(FRAME_BLANK);
        }
        if ("identity".equals(root)) {
            return path.equals(LEGAL_NAME)
                    || path.equals(CONSTITUTION)
                    || path.equals(PARTNERS)
                    || path.equals(ADDRESS);
        }
        if (!"economic".equals(root) || path.startsWith("Frame > ") || path.startsWith("Project Identity > ")) {
            return false;
        }
        if (allowedPaths().contains(path)) {
            return true;
        }
        int sep = path.lastIndexOf(" > ");
        if (sep <= 0) {
            return false;
        }
        String parent = path.substring(0, sep);
        String leaf = path.substring(sep + 3);
        boolean knownParent = parent.equals("Cash Flow")
                || parent.equals("Profit & Loss")
                || parent.equals("Balance Sheet")
                || parent.equals("Project Cost")
                || parent.equals("Means of Finance")
                || NomenclatureSeed.SPINE_PATHS.contains(parent);
        return knownParent && CATEGORY.matcher(leaf).matches() && !leaf.contains("!") && !leaf.contains("#");
    }

    private static IdentityField identityField(List<BindCellRow> row, Map<String, String> aliases) {
        for (BindCellRow cell : row) {
            if (cell.error() || cell.textValue() == null) {
                continue;
            }
            String key = norm(cell.textValue());
            if (key.equals("NAME OF THE FIRM") || key.equals("LEGAL NAME")) {
                return new IdentityField(cell, "identity", LEGAL_NAME);
            }
            if (key.equals("CONSTITUTION")) {
                return new IdentityField(cell, "identity", CONSTITUTION);
            }
            if (key.equals("NAME OF PARTNERS") || key.equals("PARTNERS") || key.equals("PARTNERS / PROMOTERS")) {
                return new IdentityField(cell, "identity", PARTNERS);
            }
            if (key.equals("LOCATION") || key.equals("ADDRESS") || key.equals("REGISTERED OFFICE")) {
                return new IdentityField(cell, "identity", ADDRESS);
            }
            if (key.equals("POWER") || key.equals("POWER CONNECTION")) {
                return new IdentityField(cell, "economic", POWER);
            }
            if (key.equals("MANPOWER")) {
                return new IdentityField(cell, "economic", MANPOWER);
            }
            if (key.equals("CAPACITY") || key.startsWith("CAPACITY")) {
                return new IdentityField(cell, "economic", CAPACITY);
            }
            String aliasPath = aliases == null ? null : aliases.get(key);
            if (aliasPath != null && !aliasPath.isBlank()) {
                if (aliasPath.startsWith("Project Identity > ")) {
                    return new IdentityField(cell, "identity", aliasPath);
                }
                if (aliasPath.startsWith("Project > ")) {
                    return new IdentityField(cell, "economic", aliasPath);
                }
            }
        }
        return null;
    }

    private static boolean startsNewField(List<BindCellRow> row, Map<String, String> aliases) {
        if (identityField(row, aliases) != null) {
            return true;
        }
        for (BindCellRow cell : row) {
            if (cell.textValue() != null && FIELD_MARK.matcher(cell.textValue().trim()).matches()
                    && cell.textValue().trim().endsWith(")")) {
                return true;
            }
            if ("number".equals(cell.valueType())) {
                return true;
            }
        }
        return false;
    }

    private static BindCellRow valueOn(List<BindCellRow> row, BindCellRow label, Map<String, Draft> out) {
        BindCellRow value = null;
        for (BindCellRow cell : row) {
            if (cell.error() || cell.colNum() <= label.colNum()) {
                continue;
            }
            if (isValueCell(cell, label)) {
                value = cell;
            }
        }
        return value;
    }

    private static boolean isValueCell(BindCellRow cell, BindCellRow label) {
        if (cell.colNum() <= label.colNum() || cell.error()) {
            return false;
        }
        if ("empty".equals(cell.valueType())) {
            return false;
        }
        if (cell.textValue() != null && FIELD_MARK.matcher(cell.textValue().trim()).matches()) {
            return false;
        }
        return cell.textValue() != null || cell.formulaText() != null || "number".equals(cell.valueType());
    }

    private static BindCellRow nearestAlias(
            List<BindCellRow> row,
            BindCellRow amount,
            Map<String, String> aliases,
            Map<String, Draft> out) {
        BindCellRow nearest = null;
        for (BindCellRow cell : row) {
            if (cell.colNum() >= amount.colNum() || cell.error() || cell.textValue() == null) {
                continue;
            }
            if (bound(out, cell) && !"economic".equals(rootOf(out, cell))) {
                continue;
            }
            String text = cell.textValue().trim();
            if (FIELD_MARK.matcher(text).matches() || "TOTAL".equals(norm(text))) {
                continue;
            }
            String path = aliases.get(norm(text));
            if (path != null && !path.isBlank()) {
                nearest = cell;
            }
        }
        return nearest;
    }

    private static boolean isScheduleTitle(BindCellRow cell, List<BindCellRow> row) {
        if (cell.mergedRange() == null || cell.mergedParticipant() || cell.textValue() == null) {
            return false;
        }
        if (!cell.mergedRange().contains(":")) {
            return false;
        }
        if (row == null) {
            return false;
        }
        for (BindCellRow other : row) {
            if ("number".equals(other.valueType())) {
                return false;
            }
        }
        return true;
    }

    private static void markSectionBanners(List<BindCellRow> cells, Map<String, Draft> out) {
        for (List<BindCellRow> row : rowsOf(cells).values()) {
            if (!isSectionBanner(row, out)) {
                continue;
            }
            for (BindCellRow cell : row) {
                if (!cell.error() && cell.textValue() != null && !cell.textValue().isBlank()
                        && !bound(out, cell)) {
                    put(out, cell, "frame", FRAME_BANNER);
                }
            }
        }
    }

    private static boolean isSectionBanner(List<BindCellRow> row, Map<String, Draft> out) {
        BindCellRow text = null;
        for (BindCellRow cell : row) {
            if (cell.error()) {
                continue;
            }
            if ("number".equals(cell.valueType())) {
                return false;
            }
            if (cell.textValue() != null && !cell.textValue().isBlank() && !bound(out, cell)) {
                if (text != null) {
                    return false;
                }
                text = cell;
            }
        }
        if (text == null) {
            return false;
        }
        String value = text.textValue().trim();
        if (PERIOD.matcher(value).matches() || ANNEXURE.matcher(value).matches()
                || SCALE.matcher(value).matches() || FIELD_MARK.matcher(value).matches()) {
            return false;
        }
        String letters = value.replaceAll("[^A-Za-z]", "");
        return letters.length() >= 4 && letters.equals(letters.toUpperCase(Locale.ROOT));
    }

    private static boolean pagesRef(BindCellRow cell) {
        String formula = stripped(cell.formulaText());
        return formula.toLowerCase(Locale.ROOT).contains("pages!");
    }

    private static String sameSheetRef(String formula) {
        String stripped = stripped(formula);
        var matcher = SAME_SHEET.matcher(stripped);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1).toUpperCase(Locale.ROOT) + matcher.group(2);
    }

    private static String stripped(String formula) {
        if (formula == null) {
            return "";
        }
        String value = formula.trim();
        if (value.startsWith("=")) {
            value = value.substring(1).trim();
        }
        return value;
    }

    private static String anchorCoord(String mergedRange) {
        if (mergedRange == null || mergedRange.isBlank()) {
            return null;
        }
        String start = mergedRange.split(":")[0].trim().toUpperCase(Locale.ROOT);
        return start.isBlank() ? null : start;
    }

    private static Map<Integer, List<BindCellRow>> rowsOf(List<BindCellRow> cells) {
        Map<Integer, List<BindCellRow>> rows = new LinkedHashMap<>();
        for (BindCellRow cell : cells) {
            rows.computeIfAbsent(cell.rowNum(), ignored -> new ArrayList<>()).add(cell);
        }
        return rows;
    }

    private static void put(Map<String, Draft> out, BindCellRow cell, String root, String path) {
        String coord = cell.coord().toUpperCase(Locale.ROOT);
        String verbatim = cell.textValue() != null && !cell.textValue().isBlank()
                ? cell.textValue()
                : cell.formulaText();
        if (verbatim != null && verbatim.length() > 240) {
            verbatim = verbatim.substring(0, 240);
        }
        out.put(coord, new Draft(cell.cellId(), coord, root, path, null, verbatim));
    }

    private static boolean bound(Map<String, Draft> out, BindCellRow cell) {
        return out.containsKey(cell.coord().toUpperCase(Locale.ROOT));
    }

    private static String rootOf(Map<String, Draft> out, BindCellRow cell) {
        Draft draft = out.get(cell.coord().toUpperCase(Locale.ROOT));
        return draft == null ? null : draft.pathRoot();
    }

    private static void putAlias(Map<String, String> aliases, String text, String path) {
        String key = norm(text);
        if (key.isBlank()) {
            return;
        }
        String existing = aliases.get(key);
        if (existing == null) {
            aliases.put(key, path);
        } else if (existing.isBlank() || !existing.equals(path)) {
            aliases.put(key, "");
        }
    }

    static String norm(String text) {
        if (text == null) {
            return "";
        }
        return text.toUpperCase(Locale.ROOT)
                .replace("'", "")
                .replace("’", "")
                .replaceAll("[^A-Z0-9&/ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record IdentityField(BindCellRow label, String root, String path) {}
}
