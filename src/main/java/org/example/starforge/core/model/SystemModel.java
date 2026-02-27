package org.example.starforge.core.model;

import java.util.ArrayList;
import java.util.List;

public final class SystemModel {
    public final StarModel star;
    public final List<PlanetModel> planets = new ArrayList<>();

    public final List<ZoneModel> zones = new ArrayList<>();
    public final List<CometModel> comets = new ArrayList<>();

    // ---- debug / bookkeeping ----
    public double diskTotalEarth = Double.NaN;   // gas+solids, Earth masses
    public double diskSolidsEarth = Double.NaN;  // solids only, Earth masses
    public AccretionSimulator.AccretionStats accretionStats = null;

    public SystemModel(StarModel star) {
        this.star = star;
    }
}
