package com.lotus.gausscmp.compare.data;

import com.lotus.gausscmp.compare.diff.RowDiff;
import com.lotus.gausscmp.compare.diff.RowDiffType;
import java.util.*;

public final class DrillDownComparator {
    private final List<String> keyColumns;

    public DrillDownComparator(List<String> keyColumns) {
        this.keyColumns = keyColumns;
    }

    public List<RowDiff> compare(Map<String, Map<String, Object>> source,
                                 Map<String, Map<String, Object>> target) {
        List<RowDiff> diffs = new ArrayList<>();
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(source.keySet());
        allKeys.addAll(target.keySet());
        for (String key : allKeys) {
            Map<String, Object> sRow = source.get(key);
            Map<String, Object> tRow = target.get(key);
            if (sRow == null) {
                diffs.add(new RowDiff(RowDiffType.EXTRA_IN_TARGET, parseKey(key), null, tRow, List.of()));
                continue;
            }
            if (tRow == null) {
                diffs.add(new RowDiff(RowDiffType.MISSING_IN_TARGET, parseKey(key), sRow, null, List.of()));
                continue;
            }
            List<String> mismatchCols = compareRow(sRow, tRow);
            if (!mismatchCols.isEmpty()) {
                diffs.add(new RowDiff(RowDiffType.MISMATCH, parseKey(key), sRow, tRow, mismatchCols));
            }
        }
        return diffs;
    }

    private List<String> compareRow(Map<String, Object> s, Map<String, Object> t) {
        List<String> mismatches = new ArrayList<>();
        Set<String> allCols = new TreeSet<>(s.keySet());
        allCols.addAll(t.keySet());
        for (String col : allCols) {
            Object sv = s.get(col);
            Object tv = t.get(col);
            if (!Objects.equals(toStr(sv), toStr(tv))) mismatches.add(col);
        }
        return mismatches;
    }

    private static String toStr(Object v) {
        return v == null ? null : v.toString();
    }

    private Map<String, Object> parseKey(String key) {
        String[] parts = key.split("\u0001", -1);
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyColumns.size() && i < parts.length; i++) {
            m.put(keyColumns.get(i), parts[i]);
        }
        return m;
    }
}
