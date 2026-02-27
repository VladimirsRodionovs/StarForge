// starforge-core/src/main/java/com/yourgame/starforge/core/model/StarRecord.java
package org.example.starforge.core.model;

public record StarRecord(
        long id,
        String proper,
        double distPc,
        double xPc, double yPc, double zPc,
        String spect,
        double lumSolar,
        double ci,
        String base
) {}
