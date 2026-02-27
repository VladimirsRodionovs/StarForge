package org.example.starforge.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.starforge.core.random.DeterministicRng;
import org.example.starforge.core.physics.PlanetRadii;

public final class PlanetModel {

    public String name;

    public double massEarth;
    public double coreMassEarth;
    public double envelopeMassEarth;
    public double envelopeZ;

    /** Pressure at the envelope-condensed boundary (bar), if applicable (giants/sub-neptunes). */
    public double basePressureBar;

    public double radiusEarth;
    public double surfaceG;

    // Bulk composition (solids). Gas envelopes are not modeled yet.
    public double fracIron;
    public double fracRock;
    public double fracIce;
    public double waterMassFrac;

    public CrustType crustType = CrustType.BASALTIC;

    public Orbit orbitAroundStar;

    public String type; // post-classification label for UI

    public List<MoonModel> moons = new ArrayList<>();

    public boolean tidallyLockedToStar;
    public String tidalHeatingLevel; // NONE / WEAK / STRONG
    public boolean habitableRegionPresent;

    public double pressureBar;

    // ---- Atmosphere & climate (v1) ----
    // Volatile inventories (Earth masses): total available material (not all in the atmosphere).
    public double invN2Earth;
    public double invCO2Earth;
    public double invH2OEarth;
    public double invO2Earth; // used mostly for biosphere-driven atmospheric shifts

    // Atmospheric masses (Earth masses) for the modeled gases.
    public double atmN2Earth;
    public double atmCO2Earth;
    public double atmH2OEarth;
    public double atmO2Earth;


    // ---- Core + gas envelope (vNext) ----
   // public double coreMassEarth;        // condensed core mass (solids + ices)
  //  public double envelopeMassEarth;    // H/He envelope mass
   // public double envelopeZ;            // metallicity proxy (0..1), optional
   // public double basePressureBar;      // pressure at envelope/condensed boundary (bar)


    // Partial pressures (bar) derived from atmospheric masses.
    public double pN2Bar;
    public double pCO2Bar;
    public double pH2OBar;
    public double pO2Bar;

    // ---- Surface water (coarse) ----
    public WaterCoverage waterCoverage = WaterCoverage.DRY;
    public double waterGELkm = 0.0; // global equivalent layer depth in km

    // A compact view of atmospheric composition by gas name -> partial pressure (bar).
    public Map<String, Double> atmosphere = new LinkedHashMap<>();

    public AtmosphereType atmosphereType = AtmosphereType.NONE;
    public OasisPotential oasisPotential = OasisPotential.NONE;

    // Climate scalars (Kelvin)
    public double teqK;
    public double greenhouseDeltaK;
    public double tMeanK;
    public double tMinK;
    public double tMaxK;

    // ---- Debug: water vapor saturation / partitioning ----
    // Filled by AtmospherePhysics to help validate the evaporation/saturation logic.
    public double h2oPsatBar;          // saturation vapor pressure at the controlling temperature
    public double h2oInventoryCapBar;  // pressure if whole accessible H2O inventory were vapor
    public double h2oUsedBar;          // actual water vapor pressure used
    public boolean h2oIceRegime;       // true if Psat was computed over ice
    public boolean h2oRunaway;         // true if runaway steam branch used

    public double heatRedistribution; // 0..1

    public BiosphereSurfaceStatus biosphereSurface = BiosphereSurfaceStatus.NONE;
    public BiosphereMicrobialStatus biosphereMicrobial = BiosphereMicrobialStatus.NONE;
    public BiosphereSubsurfaceStatus biosphereSubsurface = BiosphereSubsurfaceStatus.NONE;

    public BiosphereProvenance biosphereProvenance = BiosphereProvenance.ABIOGENIC;

    // Resource flags
    public boolean heavyHydrocarbons;
    public boolean lightHydrocarbons;

    // Events
    public String event;        // null if none
    public double eventAgeMyr;  // valid if event != null

    /* ------------------------------------------------------------
       БАЗОВЫЕ КОНСТРУКТОРЫ
       ------------------------------------------------------------ */

    public PlanetModel(String name) {
        this.name = name;
    }

    public PlanetModel(
            String name,
            double massEarth,
            double radiusEarth,
            double surfaceG,
            Orbit orbit
    ) {
        this.name = name;
        this.massEarth = massEarth;
        this.coreMassEarth = massEarth;
        this.envelopeMassEarth = 0.0;
        this.envelopeZ = 0.0;
        this.basePressureBar = Double.NaN;
        this.radiusEarth = radiusEarth;
        this.surfaceG = surfaceG;
        this.orbitAroundStar = orbit;
    }

    /* ------------------------------------------------------------
       СТАТИЧЕСКИЙ МОСТ ИЗ ФИЗИКИ → ИГРА
       ------------------------------------------------------------ */

    /**
     * Создание планеты из эмбриона аккреции.
     * Здесь НЕТ биологии, атмосферы и климата —
     * только физика и орбита.
     */
    public static PlanetModel fromEmbryo(
            StarModel star,
            EmbryoModel embryo,
            DeterministicRng rng
    ) {
        double mass = embryo.massEarth;

        // Bulk composition (carried from embryo). This influences radius.
        double fe = embryo.fracIron;
        double rock = embryo.fracRock;
        double ice = embryo.fracIce;

        // Radius: composition-aware solid curve
        double radius = PlanetRadii.solidRadiusEarth(mass, fe, rock, ice);

        double g = mass / (radius * radius);

        Orbit orbit = new Orbit(
                embryo.aAU,                 // semi-major axis
                rng.range(0.0, 0.15),        // eccentricity
                rng.range(0.0, 3.5),         // inclination (deg)
                rng.range(0.0, 360.0),       // argument of periapsis
                rng.range(0.0, 360.0),       // longitude of ascending node
                rng.range(0.0, 360.0)        // true anomaly at epoch
        );

        PlanetModel p = new PlanetModel(
                "P",
                mass,
                radius,
                g,
                orbit
        );

        // Gas envelope fields (may be filled later by gas accretion stage)
        p.coreMassEarth = embryo.coreMassEarth;
        p.envelopeMassEarth = embryo.envelopeMassEarth;
        p.envelopeZ = embryo.envelopeZ;

        // Save composition for later (ocean/atmosphere/events)
        p.fracIron = fe;
        p.fracRock = rock;
        p.fracIce = ice;
        p.waterMassFrac = embryo.waterMassFrac;
        p.crustType = embryo.crustType;

        // Тип — для UI (учитываем газовую оболочку, если она уже задана)
        double envFrac = (mass > 0) ? (p.envelopeMassEarth / mass) : 0.0;

        if (mass < 0.05) {
            p.type = "Dwarf";
        } else if (envFrac >= 0.60 && mass >= 30.0) {
            p.type = "Gas Giant";
        } else if (envFrac >= 0.35 && mass >= 10.0) {
            p.type = "Ice Giant";
        } else if (envFrac >= 0.10) {
            p.type = "Sub-Neptune";
        } else if (mass < 2.0) {
            p.type = "Rocky";
        } else {
            p.type = "Super-Earth";
        }


        // Заглушки — будут дорасчитаны позже
        p.tidallyLockedToStar = false;
        p.tidalHeatingLevel = "NONE";
        p.habitableRegionPresent = false;

        p.pressureBar = 0.0;

        return p;
    }
}
