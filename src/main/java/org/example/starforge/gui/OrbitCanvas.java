// src/main/java/org/example/starforge/gui/OrbitCanvas.java
package org.example.starforge.gui;

import org.example.starforge.core.model.MoonModel;
import org.example.starforge.core.model.PlanetModel;
import org.example.starforge.core.model.SystemModel;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

public final class OrbitCanvas extends Canvas {
    private SystemModel sys;

    public OrbitCanvas() {
        widthProperty().addListener(e -> draw());
        heightProperty().addListener(e -> draw());
        setWidth(900);
        setHeight(520);
    }

    public void setSystem(SystemModel sys) {
        this.sys = sys;
        draw();
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();

        g.setFill(Color.web("#0b1020"));
        g.fillRect(0, 0, w, h);

        if (sys == null) return;

        double cx = w / 2.0, cy = h / 2.0;

        double maxA = sys.planets.stream().mapToDouble(p -> p.orbitAroundStar.aAU()).max().orElse(1.0);
        double scale = 0.45 * Math.min(w, h) / maxA;

        // Star
        g.setFill(Color.GOLD);
        g.fillOval(cx - 4, cy - 4, 8, 8);

        // Orbits + bodies
        for (PlanetModel p : sys.planets) {
            double a = p.orbitAroundStar.aAU();
            double e = p.orbitAroundStar.e();

            double rx = a * scale;
            double ry = rx * Math.sqrt(Math.max(0.0, 1 - e * e));

            g.setStroke(Color.web("#2a335a"));
            g.strokeOval(cx - rx, cy - ry, rx * 2, ry * 2);

            // Show a marker at periapsis (preview snapshot)
            // Position by true anomaly at epoch (ν0) + argument of periapsis (ω)
            double nu = Math.toRadians(p.orbitAroundStar.trueAnomalyDeg());
            double wArg = Math.toRadians(p.orbitAroundStar.argPeriDeg());
            double ang = nu + wArg;

// r = a(1-e^2)/(1+e cos ν)
            double r = (a * (1 - e * e)) / (1 + e * Math.cos(nu));

            double px = cx + (r * Math.cos(ang)) * scale;
            double py = cy + (r * Math.sin(ang)) * scale;


            g.setFill(Color.web("#9bdcff"));
            g.fillOval(px - 3, py - 3, 6, 6);

            // draw moon rings around the planet marker (not to scale, but informative)
            int shown = Math.min(10, p.moons.size());
            if (shown > 0) {
                g.setStroke(Color.web("#4a5568"));
                for (int i = 0; i < shown; i++) {
                    MoonModel m = p.moons.get(i);
                    double ring = (i + 1) * 6;
                    g.strokeOval(px - ring, py - ring, ring * 2, ring * 2);
                }
            }
        }
    }
}
