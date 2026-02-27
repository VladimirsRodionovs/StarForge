// src/main/java/org/example/starforge/core/io/StarCsvReader.java
package org.example.starforge.core.io;

import org.example.starforge.core.model.StarRecord;
import org.apache.commons.csv.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class StarCsvReader {

    public List<StarRecord> read(Path csvPath, int limit) throws IOException {
        List<StarRecord> out = new ArrayList<>();
        try (Reader r = new InputStreamReader(new FileInputStream(csvPath.toFile()), StandardCharsets.UTF_8)) {
            CSVParser p = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withTrim()
                    .parse(r);

            for (CSVRecord row : p) {
                if (out.size() >= limit) break;

                long id = parseLong(row.get("id"), -1);
                String proper = get(row, "proper");
                double dist = parseDouble(row.get("dist"), Double.NaN);
                double x = parseDouble(row.get("x"), Double.NaN);
                double y = parseDouble(row.get("y"), Double.NaN);
                double z = parseDouble(row.get("z"), Double.NaN);
                String spect = get(row, "spect");
                double lum = parseDouble(row.get("lum"), Double.NaN);
                double ci = parseDouble(row.get("ci"), Double.NaN);
                String base = get(row, "base");

                if (!Double.isFinite(dist) || dist >= 100000) continue;

                out.add(new StarRecord(id, proper, dist, x, y, z, spect, lum, ci, base));
            }
        }
        return out;
    }

    private static String get(CSVRecord row, String name) {
        if (!row.isMapped(name)) return "";
        String v = row.get(name);
        return v == null ? "" : v.trim();
    }

    private static double parseDouble(String s, double def) {
        if (s == null) return def;
        s = s.trim();
        if (s.isEmpty()) return def;
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        s = s.trim();
        if (s.isEmpty()) return def;
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }
}
