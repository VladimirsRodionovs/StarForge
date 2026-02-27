// starforge-core/src/main/java/com/yourgame/starforge/core/model/StarModel.java
package org.example.starforge.core.model;

public record StarModel(
        StarRecord source,
        double teffK,
        double lumSolar,
        double massSolar,
        double radiusSolar,
        String evolutionStage,
        String activityLevel,
        double hzInnerAU_opt,
        double hzOuterAU_opt,
        double snowLineAU
) {}
