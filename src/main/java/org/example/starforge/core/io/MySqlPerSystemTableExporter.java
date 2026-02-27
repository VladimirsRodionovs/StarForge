package org.example.starforge.core.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.starforge.core.model.*;
import org.example.starforge.core.physics.Units;
import org.example.starforge.core.random.DeterministicRng;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

public final class MySqlPerSystemTableExporter {
    // Compact CSV ("PData") order for planets/moons (ObjectDescription):
    // tp,mE,envME,baseP,rE,sG,fracIron,fracRock,fracIce,waterMassFrac,
    // pBar,atmT,pN2,pO2,pCO2,pH2O,teqK,gsDK,tMeanK,tMinK,tMaxK,
    // tLock,tidHeat,habReg,biProv,biSur,biMic,biSub,heavHyd,lghtHyd,evnt,evAge,
    // wCov,wGel,o2Pct,co2Pct,n2Pct,PRes
    //
    // Notes:
    // - enum fields are stored by ordinal (stable as long as enum order doesn't change)
    // - PRes order: metal,silicates,water_ice,methane_ice,ammonia_ice,organics

    // Connection priority:
    // 1) local/db.local.properties
    // 2) -D system props
    // 3) env vars
    // 4) defaults
    //
    // System props:
    // starforge.db.url, starforge.db.user, starforge.db.pass
    //
    // Env vars:
    // STARFORGE_DB_URL  = jdbc:mysql://127.0.0.1:3306/DBNAME?useSSL=false&serverTimezone=UTC
    // STARFORGE_DB_USER = username
    // STARFORGE_DB_PASS = password
    private static final String DEFAULT_URL  = "jdbc:mysql://localhost:3306/EXOLOG";
    private static final String DEFAULT_USER = "ghost_reg";
    private static final String DEFAULT_PASS = "";
    private static final Path LOCAL_DB_CONFIG_PATH = Paths.get("local", "db.local.properties");

    private static final String TEMPLATE_TABLE = "StarSystem_1";
    private static final String TARGET_TABLE = "StarSystems";

    private final ObjectMapper om = new ObjectMapper();

    public Connection openConnection() throws SQLException {
        Properties local = loadLocalDbProps();
        String url = firstNonBlank(
                local.getProperty("db.url"),
                System.getProperty("starforge.db.url"),
                System.getenv("STARFORGE_DB_URL"),
                DEFAULT_URL
        );
        String user = firstNonBlank(
                local.getProperty("db.user"),
                System.getProperty("starforge.db.user"),
                System.getenv("STARFORGE_DB_USER"),
                DEFAULT_USER
        );
        String pass = firstNonBlank(
                local.getProperty("db.password"),
                System.getProperty("starforge.db.pass"),
                System.getenv("STARFORGE_DB_PASS"),
                DEFAULT_PASS
        );

        if (url == null || url.isBlank()) {
            System.out.println("url bad?");
            throw new SQLException("Missing DB URL. Configure local/db.local.properties, env or -D.");
        }
        if (user == null) user = "";
        if (pass == null) pass = "";
        return DriverManager.getConnection(url, user, pass);
    }

    private static Properties loadLocalDbProps() throws SQLException {
        Properties out = new Properties();
        if (!Files.exists(LOCAL_DB_CONFIG_PATH)) {
            return out;
        }
        try (InputStream in = Files.newInputStream(LOCAL_DB_CONFIG_PATH)) {
            out.load(in);
            return out;
        } catch (IOException e) {
            throw new SQLException("Failed to read local DB config: " + LOCAL_DB_CONFIG_PATH.toAbsolutePath(), e);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    /** Export one system into StarSystems for the given StarSystemID. */
    public void exportOne(Connection c, int starSystemId, long systemSeed, StarRecord rec, SystemModel sys) throws SQLException {
        if (c == null) throw new SQLException("Connection is null");
        if (sys == null || sys.star == null) throw new SQLException("System/star is null");
        if (starSystemId <= 0) throw new SQLException("Invalid StarSystemID: " + starSystemId);

        ensureTargetTable(c);

        // One system per transaction (keeps it robust and fast enough)
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            // wipe star/planet/moon rows
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM `" + TARGET_TABLE + "` WHERE StarSystemID=? AND ObjectType IN (1,2,3)"
            )) {
                ps.setInt(1, starSystemId);
                ps.executeUpdate();
            }

            // Determine counts
            int planetCount = (sys.planets != null) ? sys.planets.size() : 0;
            int moonCount = 0;
            if (sys.planets != null) {
                for (PlanetModel p : sys.planets) {
                    if (p != null && p.moons != null) moonCount += p.moons.size();
                }
            }

            // Insert rows
            // IMPORTANT: We insert only the columns we know. Table may have more columns -> defaults are used.
            String insertSql =
                    "INSERT INTO `" + TARGET_TABLE + "` (" +
                            "StarSystemID,ObjectInternalID,ObjectType,ObjectName,ObjectDescription,ObjectStarClass,ObjectPlanetType,ObjectOrbitHost," +
                            "ObjectOrbitEccentricity,ObjectOrbitSemimajorAxisAU,ObjectOrbitMeanMotionPerDay,ObjectOrbitMeanAnomaly," +
                            "ObjectOrbitInclination,ObjectOrbitLongitudeAscNode,ObjectOrbitPerihelianArgument," +
                            "ObjectRotationInclination,ObjectRotationSpeedSideric,ObjectRadius,ObjectProGrade,Hidden" +
                            ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

            try (PreparedStatement ins = c.prepareStatement(insertSql)) {
                int internalId = 1;

                // ---- STAR (ObjectType=1) ----
                StarModel star = sys.star;
                String starName = pickStarName(star, rec);
                String starClass = pickStarClass(star, rec);

                ObjectNode starJson = om.createObjectNode();
               // starJson.put("schemaVersion", 1);
               // starJson.put("systemSeed", systemSeed);
               // starJson.put("objectSeed", mixSeed(systemSeed, internalId));
               // starJson.put("objectInternalID", internalId);

                starJson.put("name", starName);
                starJson.put("spectralClass", starClass);
                starJson.put("teffK", star.teffK());
                starJson.put("lumSolar", star.lumSolar());
                starJson.put("massSolar", star.massSolar());
                starJson.put("radiusSolar", star.radiusSolar());
                starJson.put("evolutionStage", safeStr(star.evolutionStage()));
                starJson.put("activityLevel", safeStr(star.activityLevel()));
                starJson.put("hzInnerAU_opt", star.hzInnerAU_opt());
                starJson.put("hzOuterAU_opt", star.hzOuterAU_opt());
                starJson.put("snowLineAU", star.snowLineAU());
                starJson.put("planetCount", planetCount);
                starJson.put("moonCount", moonCount);

                insertRow(ins,
                        starSystemId,
                        internalId,
                        1, // ObjectType
                        starName,
                        starJson.toString(),
                        starClass,
                        1, // ObjectPlanetType star
                        null, // ObjectOrbitHost null
                        null, null, null, null, null, null, null, // orbit fields
                        null, // rotation inclination
                        null, // rotation speed
                        starRadiusKm(star),
                        1,
                        0
                );

                // Map host internal ids for moons
                Map<PlanetModel, Integer> planetInternalIds = new IdentityHashMap<>();

                // ---- PLANETS (ObjectType=2) ----
                if (sys.planets != null) {
                    for (PlanetModel p : sys.planets) {
                        if (p == null) continue;
                        internalId++;

                        planetInternalIds.put(p, internalId);

                        Orbit o = p.orbitAroundStar;
                        double aAU = (o != null) ? o.aAU() : 0.0;
                        double ecc = (o != null) ? o.e() : 0.0;

                        double meanMotionDegPerDay = meanMotionDegPerDay(aAU, Math.max(1e-12, star.massSolar()));
                        double meanAnomDeg = (o != null) ? meanAnomalyDeg(ecc, o.trueAnomalyDeg()) : 0.0;

                        long objSeed = mixSeed(systemSeed, internalId);

                        String pData = planetPData(p, star);
                        insertRow(ins,
                                starSystemId,
                                internalId,
                                2,
                                safeStr(p.name),
                                pData,
                                null, // ObjectStarClass for non-star
                                classifyObjectPlanetTypeForPlanet(p),
                                1L, // all planets orbit star internal id=1
                                ecc,
                                aAU,
                                meanMotionDegPerDay,
                                meanAnomDeg,
                                (o != null) ? o.iDeg() : 0.0,
                                (o != null) ? o.omegaDeg() : 0.0,
                                (o != null) ? o.argPeriDeg() : 0.0,
                                rotationInclinationDegPlanet(objSeed, p),
                                rotationSpeedHoursPlanet(objSeed, p, star),
                                planetRadiusKm(p),
                                1,
                                0
                        );
                    }
                }

                // ---- MOONS (ObjectType=3) ----
                if (sys.planets != null) {
                    for (PlanetModel host : sys.planets) {
                        if (host == null || host.moons == null || host.moons.isEmpty()) continue;

                        Integer hostId = planetInternalIds.get(host);
                        if (hostId == null) continue;

                        for (MoonModel m : host.moons) {
                            if (m == null) continue;
                            internalId++;

                            Orbit mo = m.orbitAroundPlanet();
                            double aAU = (mo != null) ? mo.aAU() : 0.0;
                            double ecc = (mo != null) ? mo.e() : 0.0;

                            // around planet: central mass in solar masses
                            double mCentralSolar = Math.max(1e-12, host.massEarth / Units.M_EARTH_PER_M_SUN);
                            double meanMotionDegPerDay = meanMotionDegPerDay(aAU, mCentralSolar);
                            double meanAnomDeg = (mo != null) ? meanAnomalyDeg(ecc, mo.trueAnomalyDeg()) : 0.0;

                            long objSeed = mixSeed(systemSeed, internalId);

                            String mData = moonPData(m, star, host);

                            insertRow(ins,
                                    starSystemId,
                                    internalId,
                                    3,
                                    safeStr(m.name()),
                                    mData,
                                    null,
                                    classifyObjectPlanetTypeForMoon(m),
                                    (long) hostId,
                                    ecc,
                                    aAU,
                                    meanMotionDegPerDay,
                                    meanAnomDeg,
                                    (mo != null) ? mo.iDeg() : 0.0,
                                    (mo != null) ? mo.omegaDeg() : 0.0,
                                    (mo != null) ? mo.argPeriDeg() : 0.0,
                                    rotationInclinationDegMoon(objSeed, m),
                                    rotationSpeedHoursMoon(objSeed, m, host, star),
                                    m.radiusEarth() * Units.R_EARTH_KM,
                                    1,
                                    0
                            );
                        }
                    }
                }

                ins.executeBatch();
            }

            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }

    /** Update ObjectRotationInclination only (planets + moons). */
    public void updateRotationInclination(Connection c, int starSystemId, long systemSeed, SystemModel sys) throws SQLException {
        if (c == null) throw new SQLException("Connection is null");
        if (sys == null || sys.star == null) throw new SQLException("System/star is null");
        if (starSystemId <= 0) throw new SQLException("Invalid StarSystemID: " + starSystemId);
        ensureTargetTable(c);

        String updateSql = "UPDATE `" + TARGET_TABLE + "` SET ObjectRotationInclination=? WHERE StarSystemID=? AND ObjectInternalID=? AND ObjectType IN (2,3)";
        try (PreparedStatement ps = c.prepareStatement(updateSql)) {
            int id = 1; // star = 1, planets start from 2
            if (sys.planets != null) {
                for (PlanetModel p : sys.planets) {
                    if (p == null) continue;
                    id++;
                    long objSeed = mixSeed(systemSeed, id);
                    setNullableDouble(ps, 1, rotationInclinationDegPlanet(objSeed, p));
                    ps.setInt(2, starSystemId);
                    ps.setInt(3, id);
                    ps.addBatch();
                }
            }

            if (sys.planets != null) {
                for (PlanetModel host : sys.planets) {
                    if (host == null || host.moons == null || host.moons.isEmpty()) continue;
                    for (MoonModel m : host.moons) {
                        if (m == null) continue;
                        id++;
                        long objSeed = mixSeed(systemSeed, id);
                        setNullableDouble(ps, 1, rotationInclinationDegMoon(objSeed, m));
                        ps.setInt(2, starSystemId);
                        ps.setInt(3, id);
                        ps.addBatch();
                    }
                }
            }

            ps.executeBatch();
        }
    }

    /** Update ObjectRotationSpeedSideric only (planets + moons). */
    public void updateRotationSpeed(Connection c, int starSystemId, long systemSeed, SystemModel sys) throws SQLException {
        if (c == null) throw new SQLException("Connection is null");
        if (sys == null || sys.star == null) throw new SQLException("System/star is null");
        if (starSystemId <= 0) throw new SQLException("Invalid StarSystemID: " + starSystemId);
        ensureTargetTable(c);

        String updateSql = "UPDATE `" + TARGET_TABLE + "` SET ObjectRotationSpeedSideric=? WHERE StarSystemID=? AND ObjectInternalID=? AND ObjectType IN (2,3)";
        try (PreparedStatement ps = c.prepareStatement(updateSql)) {
            int id = 1; // star = 1, planets start from 2
            if (sys.planets != null) {
                for (PlanetModel p : sys.planets) {
                    if (p == null) continue;
                    id++;
                    long objSeed = mixSeed(systemSeed, id);
                    setNullableDouble(ps, 1, rotationSpeedHoursPlanet(objSeed, p, sys.star));
                    ps.setInt(2, starSystemId);
                    ps.setInt(3, id);
                    ps.addBatch();
                }
            }

            if (sys.planets != null) {
                for (PlanetModel host : sys.planets) {
                    if (host == null || host.moons == null || host.moons.isEmpty()) continue;
                    for (MoonModel m : host.moons) {
                        if (m == null) continue;
                        id++;
                        long objSeed = mixSeed(systemSeed, id);
                        setNullableDouble(ps, 1, rotationSpeedHoursMoon(objSeed, m, host, sys.star));
                        ps.setInt(2, starSystemId);
                        ps.setInt(3, id);
                        ps.addBatch();
                    }
                }
            }

            ps.executeBatch();
        }
    }

    /** Return all StarSystemID values present in StarSystems (sorted asc). */
    public List<Integer> listStarSystemIds(Connection c) throws SQLException {
        if (c == null) throw new SQLException("Connection is null");
        ensureTargetTable(c);
        List<Integer> out = new ArrayList<>();
        String sql =
                "SELECT DISTINCT StarSystemID FROM `" + TARGET_TABLE + "` " +
                        "WHERE StarSystemID IS NOT NULL ORDER BY StarSystemID";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt(1);
                if (!rs.wasNull() && id > 0) out.add(id);
            }
        }
        return out;
    }

    /** Stats for close-moon correction in one table. */
    public record CloseMoonCorrectionStats(int planetsWithMoons, int convertedToRings) {}

    /** Convert nearest moon per planet to ring if it is inside the fluid Roche limit. */
    public CloseMoonCorrectionStats applyCloseMoonCorrection(Connection c, int starSystemId) throws SQLException {
        if (c == null) throw new SQLException("Connection is null");
        if (starSystemId <= 0) throw new SQLException("Invalid StarSystemID: " + starSystemId);
        ensureTargetTable(c);

        String selectSql =
                "SELECT p.ObjectInternalID AS planetId, p.ObjectRadius AS planetRadiusKm, p.ObjectDescription AS planetDesc, " +
                        "m.ObjectInternalID AS moonId, m.ObjectName AS moonName, m.ObjectOrbitSemimajorAxisAU AS moonAau, " +
                        "m.ObjectRadius AS moonRadiusKm, m.ObjectDescription AS moonDesc " +
                        "FROM `" + TARGET_TABLE + "` p " +
                        "JOIN `" + TARGET_TABLE + "` m ON m.StarSystemID = p.StarSystemID AND m.ObjectOrbitHost = p.ObjectInternalID " +
                        "WHERE p.StarSystemID = ? AND p.ObjectType = 2 AND m.ObjectType = 3 " +
                        "ORDER BY p.ObjectInternalID, m.ObjectOrbitSemimajorAxisAU, m.ObjectInternalID";

        String updateSql =
                "UPDATE `" + TARGET_TABLE + "` " +
                        "SET ObjectType=9, ObjectPlanetType=9, ObjectName=?, ObjectDescription=? " +
                        "WHERE StarSystemID=? AND ObjectInternalID=? AND ObjectType=3";

        LinkedHashMap<Integer, MoonCandidate> nearestByPlanet = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(selectSql)) {
            ps.setInt(1, starSystemId);
            try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int planetId = rs.getInt("planetId");
                if (nearestByPlanet.containsKey(planetId)) continue;

                MoonCandidate row = new MoonCandidate();
                row.planetRadiusKm = rs.getDouble("planetRadiusKm");
                row.planetDesc = rs.getString("planetDesc");
                row.moonId = rs.getInt("moonId");
                row.moonName = rs.getString("moonName");
                row.moonAau = rs.getDouble("moonAau");
                row.moonRadiusKm = rs.getDouble("moonRadiusKm");
                row.moonDesc = rs.getString("moonDesc");
                nearestByPlanet.put(planetId, row);
            }
            }
        }

        int converted = 0;
        try (PreparedStatement ps = c.prepareStatement(updateSql)) {
            for (MoonCandidate row : nearestByPlanet.values()) {
                if (!shouldConvertToRing(row)) continue;
                ps.setString(1, toRingName(row.moonName));
                ps.setString(2, buildRingDescription(row));
                ps.setInt(3, starSystemId);
                ps.setInt(4, row.moonId);
                ps.addBatch();
                converted++;
            }
            if (converted > 0) ps.executeBatch();
        }

        return new CloseMoonCorrectionStats(nearestByPlanet.size(), converted);
    }

    // -------------------- table handling --------------------

    private void ensureTargetTable(Connection c) throws SQLException {
        if (!tableExists(c, TARGET_TABLE)) {
            if (!tableExists(c, TEMPLATE_TABLE)) {
                throw new SQLException("Table `" + TARGET_TABLE + "` not found and template `" + TEMPLATE_TABLE + "` is missing.");
            }

            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS `" + TARGET_TABLE + "` LIKE `" + TEMPLATE_TABLE + "`");
            }
        }

        if (!columnExists(c, TARGET_TABLE, "StarSystemID")) {
            throw new SQLException("Table `" + TARGET_TABLE + "` must contain column `StarSystemID`.");
        }
    }

    private boolean tableExists(Connection c, String tableName) throws SQLException {
        String sql =
                "SELECT 1 FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name = ? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(Connection c, String tableName, String columnName) throws SQLException {
        String sql =
                "SELECT 1 FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // -------------------- insert batching --------------------

    private void insertRow(
            PreparedStatement ps,
            int starSystemId,
            int internalId,
            int objectType,
            String objectName,
            String objectDescriptionJson,
            String objectStarClass,
            int objectPlanetType,
            Long objectOrbitHost,
            Double ecc,
            Double aAU,
            Double meanMotionPerDay,
            Double meanAnomaly,
            Double inc,
            Double lonAscNode,
            Double argPeri,
            Double rotationInclination,
            Double rotationSpeedSidericHours,
            Double radiusKm,
            Integer prograde,
            int hidden
    ) throws SQLException {
        int i = 1;
        ps.setInt(i++, starSystemId);
        ps.setInt(i++, internalId);
        ps.setInt(i++, objectType);
        ps.setString(i++, objectName);
        ps.setString(i++, objectDescriptionJson);

        if (objectStarClass == null) ps.setNull(i++, Types.VARCHAR);
        else ps.setString(i++, objectStarClass);

        ps.setInt(i++, objectPlanetType);

        if (objectOrbitHost == null) ps.setNull(i++, Types.INTEGER);
        else ps.setLong(i++, objectOrbitHost);

        setNullableDouble(ps, i++, ecc);
        setNullableDouble(ps, i++, aAU);
        setNullableDouble(ps, i++, meanMotionPerDay);
        setNullableDouble(ps, i++, meanAnomaly);

        setNullableDouble(ps, i++, inc);
        setNullableDouble(ps, i++, lonAscNode);
        setNullableDouble(ps, i++, argPeri);

        setNullableDouble(ps, i++, rotationInclination);
        setNullableDouble(ps, i++, rotationSpeedSidericHours);

        setNullableDouble(ps, i++, radiusKm);

        if (prograde == null) ps.setNull(i++, Types.TINYINT);
        else ps.setInt(i++, prograde);

        ps.setInt(i++, hidden);

        ps.addBatch();
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null || !Double.isFinite(v)) ps.setNull(idx, Types.DOUBLE);
        else ps.setDouble(idx, v);
    }

    // -------------------- compact CSV builders --------------------

    private String planetPData(PlanetModel p, StarModel star) {
        StringBuilder sb = new StringBuilder(256);
        appendStr(sb, safeStr(p.type));
        appendNum(sb, p.massEarth);
        appendNum(sb, p.envelopeMassEarth);
        appendNum(sb, p.basePressureBar);
        appendNum(sb, p.radiusEarth);
        appendNum(sb, p.surfaceG);
        appendNum(sb, p.fracIron);
        appendNum(sb, p.fracRock);
        appendNum(sb, p.fracIce);
        appendNum(sb, p.waterMassFrac);
        appendNum(sb, p.pressureBar);
        appendOrd(sb, p.atmosphereType);
        appendNum(sb, p.pN2Bar);
        appendNum(sb, p.pO2Bar);
        appendNum(sb, p.pCO2Bar);
        appendNum(sb, p.pH2OBar);
        appendNum(sb, p.teqK);
        appendNum(sb, p.greenhouseDeltaK);
        appendNum(sb, p.tMeanK);
        appendNum(sb, p.tMinK);
        appendNum(sb, p.tMaxK);
        appendBool(sb, p.tidallyLockedToStar);
        appendStr(sb, safeStr(p.tidalHeatingLevel));
        appendBool(sb, p.habitableRegionPresent);
        appendOrd(sb, p.biosphereProvenance);
        appendOrd(sb, p.biosphereSurface);
        appendOrd(sb, p.biosphereMicrobial);
        appendOrd(sb, p.biosphereSubsurface);
        appendBool(sb, p.heavyHydrocarbons);
        appendBool(sb, p.lightHydrocarbons);
        appendStr(sb, safeStr(p.event));
        appendNum(sb, p.eventAgeMyr);
        appendOrd(sb, p.waterCoverage);
        appendNum(sb, p.waterGELkm);
        appendNum(sb, gasPct(p.atmosphere, "O2"));
        appendNum(sb, gasPct(p.atmosphere, "CO2"));
        appendNum(sb, gasPct(p.atmosphere, "N2"));
        appendStr(sb, ResourceModelUtil.toPRes(ResourceModelUtil.mixForPlanet(p, star)));
        return sb.toString();
    }

    private String moonPData(MoonModel m, StarModel star, PlanetModel host) {
        StringBuilder sb = new StringBuilder(256);
        appendStr(sb, safeStr(m.type()));
        appendNum(sb, m.massEarth());
        appendNum(sb, m.envelopeMassEarth());
        appendNum(sb, m.basePressureBar());
        appendNum(sb, m.radiusEarth());
        appendNum(sb, m.surfaceG());
        appendNum(sb, m.fracIron());
        appendNum(sb, m.fracRock());
        appendNum(sb, m.fracIce());
        appendNum(sb, m.waterMassFrac());
        appendNum(sb, m.pressureBar());
        appendOrd(sb, m.atmosphereType());
        appendNum(sb, m.atmosphere() != null ? m.atmosphere().getOrDefault("N2", 0.0) : 0.0);
        appendNum(sb, m.atmosphere() != null ? m.atmosphere().getOrDefault("O2", 0.0) : 0.0);
        appendNum(sb, m.atmosphere() != null ? m.atmosphere().getOrDefault("CO2", 0.0) : 0.0);
        appendNum(sb, m.atmosphere() != null ? m.atmosphere().getOrDefault("H2O", 0.0) : 0.0);
        appendNum(sb, m.teqK());
        appendNum(sb, m.greenhouseDeltaK());
        appendNum(sb, m.tMeanK());
        appendNum(sb, m.tMinK());
        appendNum(sb, m.tMaxK());
        appendBool(sb, m.tidallyLockedToPlanet());
        appendStr(sb, safeStr(m.tidalHeatingLevel()));
        appendBool(sb, m.habitableRegionPresent());
        appendOrd(sb, m.biosphereProvenance());
        appendOrd(sb, m.biosphereSurface());
        appendOrd(sb, m.biosphereMicrobial());
        appendOrd(sb, m.biosphereSubsurface());
        appendBool(sb, m.heavyHydrocarbons());
        appendBool(sb, m.lightHydrocarbons());
        appendStr(sb, safeStr(m.event()));
        appendNum(sb, m.eventAgeMyr());
        appendOrd(sb, m.waterCoverage());
        appendNum(sb, m.waterGELkm());
        appendNum(sb, gasPct(m.atmosphere(), "O2"));
        appendNum(sb, gasPct(m.atmosphere(), "CO2"));
        appendNum(sb, gasPct(m.atmosphere(), "N2"));
        appendStr(sb, ResourceModelUtil.toPRes(ResourceModelUtil.mixForMoon(m, star, host)));
        return sb.toString();
    }

    // -------------------- rules / mappings --------------------

    /** ObjectPlanetType: 2=landable planet, 4=landable moon, 5=non-landable (giants etc) */
    private static int classifyObjectPlanetTypeForPlanet(PlanetModel p) {
        return classifyObjectPlanetType(
                false,
                p.type,
                p.atmosphereType,
                p.envelopeMassEarth,
                p.pressureBar,
                p.tMinK
        );
    }

    private static int classifyObjectPlanetTypeForMoon(MoonModel m) {
        return classifyObjectPlanetType(
                true,
                null,
                m.atmosphereType(),
                m.envelopeMassEarth(),
                m.pressureBar(),
                m.tMinK()
        );
    }

    /**
     * ObjectPlanetType:
     *  - 2: landable planet
     *  - 4: landable moon
     *  - 5: no solid surface (giants/sub-neptunes or H/He envelopes)
     *  - 6: extreme environment (pressure >= 200 bar OR tMinK >= 550 K)
     */
    private static int classifyObjectPlanetType(
            boolean isMoon,
            String type,
            AtmosphereType atmosphereType,
            double envelopeMassEarth,
            double pressureBar,
            double tMinK
    ) {
        String t = (type == null) ? "" : type.toLowerCase(Locale.ROOT);
        boolean noSurface =
                t.contains("gas giant") ||
                t.contains("ice giant") ||
                t.contains("sub-neptune") ||
                (atmosphereType == AtmosphereType.HHE) ||
                (envelopeMassEarth > 0.02);

        if (noSurface) return 5;

        boolean extreme =
                (Double.isFinite(pressureBar) && pressureBar >= 200.0) ||
                (Double.isFinite(tMinK) && tMinK >= 550.0);

        if (extreme) return 6;
        return isMoon ? 4 : 2;
    }

    // -------------------- orbit math --------------------

    private static double meanMotionDegPerDay(double aAU, double centralMassSolar) {
        if (!(aAU > 0) || !(centralMassSolar > 0)) return 0.0;
        double pYears = Math.sqrt((aAU * aAU * aAU) / Math.max(centralMassSolar, 1e-12));
        double pDays = pYears * 365.25;
        return 360.0 / Math.max(1e-12, pDays);
    }

    /** true anomaly -> mean anomaly (deg), ellipse */
    private static double meanAnomalyDeg(double e, double trueAnomDeg) {
        e = clamp(Math.abs(e), 0.0, 0.999999);
        double nu = Math.toRadians(normDeg(trueAnomDeg));

        double tanE2 = Math.sqrt((1.0 - e) / (1.0 + e)) * Math.tan(nu / 2.0);
        double E = 2.0 * Math.atan(tanE2);
        double M = E - e * Math.sin(E);

        return normDeg(Math.toDegrees(M));
    }

    private static double normDeg(double x) {
        double r = x % 360.0;
        if (r < 0) r += 360.0;
        return r;
    }

    // -------------------- star/name helpers --------------------

    private static String pickStarName(StarModel star, StarRecord rec) {
        // Rule: proper name, else class, else unnamed
        if (rec != null && rec.proper() != null && !rec.proper().isBlank()) return rec.proper().trim();
        if (rec != null && rec.spect() != null && !rec.spect().isBlank()) return rec.spect().trim();
        if (star != null && star.source() != null && star.source().spect() != null && !star.source().spect().isBlank())
            return star.source().spect().trim();
        return "unnamed";
    }

    private static String pickStarClass(StarModel star, StarRecord rec) {
        if (rec != null && rec.spect() != null && !rec.spect().isBlank()) return rec.spect().trim();
        if (star != null && star.source() != null && star.source().spect() != null && !star.source().spect().isBlank())
            return star.source().spect().trim();
        return null;
    }

    private static double starRadiusKm(StarModel star) {
        final double R_SUN_KM = 695_700.0;
        return Math.max(0.0, star.radiusSolar() * R_SUN_KM);
    }

    private static double planetRadiusKm(PlanetModel p) {
        return Math.max(0.0, p.radiusEarth * Units.R_EARTH_KM);
    }

    // -------------------- cfg + seed mixing --------------------

    private static String cfg(String key) {
        String v = System.getProperty(key);
        if (v != null && !v.isBlank()) return v;
        v = System.getenv(key);
        if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** Same splitmix-style mixer as your generator */
    public static long mixSeed(long a, long b) {
        long x = a ^ (b + 0x9E3779B97F4A7C15L);
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        return x;
    }

    private static String safeStr(String s) {
        return (s == null) ? "" : s;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double rotationInclinationDegPlanet(long objectSeed, PlanetModel p) {
        DeterministicRng rng = new DeterministicRng(objectSeed ^ 0xD1B54A32D192ED03L);
        if (p != null && p.tidallyLockedToStar) return rng.range(0.0, 5.0);

        double u = rng.nextDouble();
        if (u < 0.70) return rng.range(0.0, 30.0);
        if (u < 0.90) return rng.range(30.0, 40.0);
        if (u < 0.99) return rng.range(40.0, 60.0);
        return rng.range(60.0, 90.0);
    }

    private static double rotationInclinationDegMoon(long objectSeed, MoonModel m) {
        DeterministicRng rng = new DeterministicRng(objectSeed ^ 0x9E3779B97F4A7C15L);
        if (m != null && m.tidallyLockedToPlanet()) return rng.range(0.0, 3.0);

        double u = rng.nextDouble();
        if (u < 0.80) return rng.range(0.0, 10.0);
        if (u < 0.95) return rng.range(10.0, 20.0);
        return rng.range(20.0, 40.0);
    }

    private static double rotationSpeedHoursPlanet(long objectSeed, PlanetModel p, StarModel star) {
        DeterministicRng rng = new DeterministicRng(objectSeed ^ 0xA4093822299F31D0L);

        if (p != null && p.tidallyLockedToStar) {
            double periodDays = orbitalPeriodDaysAroundStar(p, star);
            return periodDays * 24.0;
        }

        String t = (p != null && p.type != null) ? p.type.toLowerCase(java.util.Locale.ROOT) : "";
        boolean gasLike = t.contains("gas giant") || t.contains("ice giant") || t.contains("sub-neptune")
                || (p != null && p.envelopeMassEarth > 0.02);

        double u = rng.nextDouble();

        if (gasLike) {
            if (u < 0.90) return rng.range(8.0, 16.0);
            return rng.range(16.0, 22.0);
        }

        // Rocky / super-earth / dwarf: broader distribution with rare slow anomalies
        if (u < 0.70) return rng.range(10.0, 30.0);
        if (u < 0.90) return rng.range(30.0, 40.0);
        if (u < 0.99) return rng.range(40.0, 60.0);

        // very rare slow rotators
        return rng.range(60.0, 300.0);
    }

    private static double rotationSpeedHoursMoon(long objectSeed, MoonModel m, PlanetModel host, StarModel star) {
        DeterministicRng rng = new DeterministicRng(objectSeed ^ 0xBB67AE8584CAA73BL);

        if (m != null && m.tidallyLockedToPlanet()) {
            double periodDays = orbitalPeriodDaysAroundPlanet(m, host);
            return periodDays * 24.0;
        }

        double u = rng.nextDouble();
        if (u < 0.70) return rng.range(10.0, 50.0);
        if (u < 0.97) return rng.range(50.0, 200.0);

        // rare anomalously slow spinners
        return rng.range(200.0, 600.0);
    }

    private static double orbitalPeriodDaysAroundStar(PlanetModel p, StarModel star) {
        if (p == null || p.orbitAroundStar == null || star == null) return Double.NaN;
        double aAU = p.orbitAroundStar.aAU();
        double mStar = Math.max(1e-12, star.massSolar());
        if (!(aAU > 0) || !(mStar > 0)) return Double.NaN;
        double pYears = Math.sqrt((aAU * aAU * aAU) / mStar);
        return pYears * 365.25;
    }

    private static double orbitalPeriodDaysAroundPlanet(MoonModel m, PlanetModel host) {
        if (m == null || m.orbitAroundPlanet() == null || host == null) return Double.NaN;
        double aAU = m.orbitAroundPlanet().aAU();
        double mCentralSolar = Math.max(1e-12, host.massEarth / Units.M_EARTH_PER_M_SUN);
        if (!(aAU > 0) || !(mCentralSolar > 0)) return Double.NaN;
        double pYears = Math.sqrt((aAU * aAU * aAU) / mCentralSolar);
        return pYears * 365.25;
    }

    private static double gasPct(Map<String, Double> atmosphere, String key) {
        if (atmosphere == null || atmosphere.isEmpty() || key == null) return 0.0;
        double total = 0.0;
        for (var e : atmosphere.entrySet()) {
            Double v = e.getValue();
            if (v == null || !Double.isFinite(v) || v <= 0.0) continue;
            total += v;
        }
        if (!(total > 0.0)) return 0.0;
        Double k = atmosphere.get(key);
        if (k == null || !Double.isFinite(k) || k <= 0.0) return 0.0;
        return (k / total) * 100.0;
    }

    private static void appendSep(StringBuilder sb) {
        if (sb.length() > 0) sb.append(',');
    }

    private static void appendStr(StringBuilder sb, String v) {
        appendSep(sb);
        if (v == null) return;
        sb.append(v.replace(",", " ")); // ensure CSV-safe
    }

    private static void appendNum(StringBuilder sb, double v) {
        appendSep(sb);
        if (!Double.isFinite(v)) return;
        sb.append(String.format(java.util.Locale.ROOT, "%.4f", v));
    }

    private static void appendBool(StringBuilder sb, boolean v) {
        appendSep(sb);
        sb.append(v ? '1' : '0');
    }

    private static void appendOrd(StringBuilder sb, Enum<?> e) {
        appendSep(sb);
        if (e == null) return;
        sb.append(e.ordinal());
    }

    private static boolean shouldConvertToRing(MoonCandidate row) {
        if (!Double.isFinite(row.planetRadiusKm) || row.planetRadiusKm <= 0.0) return false;
        if (!Double.isFinite(row.moonAau) || row.moonAau <= 0.0) return false;
        double aKm = row.moonAau * Units.KM_PER_AU;
        double rocheKm = fluidRocheLimitKm(row);
        return Double.isFinite(rocheKm) && aKm < rocheKm;
    }

    private static double fluidRocheLimitKm(MoonCandidate row) {
        double rp = row.planetRadiusKm;
        if (!(rp > 0.0)) return Double.NaN;

        // Fallback assumes equal densities.
        double densityRatioCbrt = 1.0;

        double mp = parsePDataMassEarth(row.planetDesc);
        double mm = parsePDataMassEarth(row.moonDesc);
        double rm = row.moonRadiusKm;

        if (Double.isFinite(mp) && mp > 0.0 && Double.isFinite(mm) && mm > 0.0 && Double.isFinite(rm) && rm > 0.0) {
            double rhoP = mp / (rp * rp * rp);
            double rhoM = mm / (rm * rm * rm);
            if (Double.isFinite(rhoP) && Double.isFinite(rhoM) && rhoP > 0.0 && rhoM > 0.0) {
                densityRatioCbrt = Math.cbrt(rhoP / rhoM);
                if (!Double.isFinite(densityRatioCbrt) || densityRatioCbrt <= 0.0) densityRatioCbrt = 1.0;
                densityRatioCbrt = clamp(densityRatioCbrt, 0.5, 2.5);
            }
        }

        return 2.44 * rp * densityRatioCbrt;
    }

    private static double parsePDataMassEarth(String pData) {
        if (pData == null || pData.isBlank()) return Double.NaN;
        String[] f = pData.split(",", -1);
        if (f.length < 2) return Double.NaN;
        return parseDoubleSafe(f[1]);
    }

    private static double parsePDataFracIce(String pData) {
        if (pData == null || pData.isBlank()) return Double.NaN;
        String[] f = pData.split(",", -1);
        if (f.length < 9) return Double.NaN;
        return parseDoubleSafe(f[8]);
    }

    private static double parseDoubleSafe(String s) {
        if (s == null) return Double.NaN;
        String t = s.trim();
        if (t.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(t);
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private static String toRingName(String moonName) {
        if (moonName == null || moonName.isBlank()) return "RING";
        String renamed = moonName.replaceFirst("-M(\\d+)$", "-R$1");
        if (!renamed.equals(moonName)) return renamed;
        return moonName.replaceFirst("M", "R");
    }

    private static String buildRingDescription(MoonCandidate row) {
        double widthKm = 300.0;
        if (Double.isFinite(row.moonRadiusKm) && row.moonRadiusKm > 0.0) {
            widthKm = row.moonRadiusKm * 2.5;
        }
        widthKm = clamp(widthKm, 80.0, 80000.0);

        double fracIce = parsePDataFracIce(row.moonDesc);
        String color = (Double.isFinite(fracIce) && fracIce >= 0.45) ? "pale_blue_gray" : "light_gray";
        String composition = (Double.isFinite(fracIce) && fracIce >= 0.45) ? "ice_rich_dust" : "silicate_metal_dust";

        return String.format(
                Locale.ROOT,
                "{\"type\":\"Ring\",\"widthKm\":%.1f,\"color\":\"%s\",\"composition\":\"%s\"}",
                widthKm,
                color,
                composition
        );
    }

    private static final class MoonCandidate {
        double planetRadiusKm;
        String planetDesc;
        int moonId;
        String moonName;
        double moonAau;
        double moonRadiusKm;
        String moonDesc;
    }
}
