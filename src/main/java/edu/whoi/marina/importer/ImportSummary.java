package edu.whoi.marina.importer;

import java.util.LinkedHashMap;
import java.util.Map;

public class ImportSummary {
    public final Map<String, Integer> counts = new LinkedHashMap<>();

    public void inc(String key) {
        counts.merge(key, 1, Integer::sum);
    }

    public void inc(String key, int by) {
        counts.merge(key, by, Integer::sum);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Import summary:\n");
        counts.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }
}
