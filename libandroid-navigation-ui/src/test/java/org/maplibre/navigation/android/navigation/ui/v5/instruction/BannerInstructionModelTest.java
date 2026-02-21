package org.maplibre.navigation.android.navigation.ui.v5.instruction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import org.maplibre.navigation.android.navigation.ui.v5.utils.DistanceFormatter;
import org.maplibre.navigation.core.models.BannerInstructions;
import org.maplibre.navigation.core.models.BannerText;
import org.maplibre.navigation.core.models.DirectionsRoute;
import org.maplibre.navigation.core.models.LegStep;
import org.maplibre.navigation.core.models.ManeuverType;
import org.maplibre.navigation.core.routeprogress.RouteLegProgress;
import org.maplibre.navigation.core.routeprogress.RouteProgress;
import org.maplibre.navigation.core.routeprogress.RouteStepProgress;

public class BannerInstructionModelTest {

  @Test
  public void constructor_promotesSubInstructionToPrimary() {
    BannerText primary = mock(BannerText.class);
    BannerText secondary = mock(BannerText.class);
    BannerText sub = mock(BannerText.class);
    ManeuverType maneuverType = mock(ManeuverType.class);
    when(maneuverType.getText()).thenReturn("turn");
    when(sub.getType()).thenReturn(maneuverType);

    BannerInstructions instructions = mock(BannerInstructions.class);
    when(instructions.getPrimary()).thenReturn(primary);
    when(instructions.getSecondary()).thenReturn(secondary);
    when(instructions.getSub()).thenReturn(sub);

    BannerInstructionModel model = new BannerInstructionModel(
      mock(DistanceFormatter.class),
      buildRouteProgress(),
      instructions
    );

    assertEquals(sub, model.retrievePrimaryBannerText());
    assertNull(model.retrieveSecondaryBannerText());
    assertNull(model.retrieveSubBannerText());
    assertEquals("turn", model.retrievePrimaryManeuverType());
  }

  @Test
  public void constructor_usesPrimaryAndSecondaryWhenSubMissing() {
    BannerText primary = mock(BannerText.class);
    BannerText secondary = mock(BannerText.class);

    BannerInstructions instructions = mock(BannerInstructions.class);
    when(instructions.getPrimary()).thenReturn(primary);
    when(instructions.getSecondary()).thenReturn(secondary);
    when(instructions.getSub()).thenReturn(null);

    BannerInstructionModel model = new BannerInstructionModel(
      mock(DistanceFormatter.class),
      buildRouteProgress(),
      instructions
    );

    assertEquals(primary, model.retrievePrimaryBannerText());
    assertEquals(secondary, model.retrieveSecondaryBannerText());
    assertNull(model.retrieveSubBannerText());
  }

  private RouteProgress buildRouteProgress() {
    RouteStepProgress stepProgress = mock(RouteStepProgress.class);
    when(stepProgress.getDistanceRemaining()).thenReturn(120d);

    LegStep currentStep = mock(LegStep.class);
    when(currentStep.getDrivingSide()).thenReturn("right");

    RouteLegProgress legProgress = mock(RouteLegProgress.class);
    when(legProgress.getCurrentStepProgress()).thenReturn(stepProgress);
    when(legProgress.getCurrentStep()).thenReturn(currentStep);

    RouteProgress routeProgress = mock(RouteProgress.class);
    when(routeProgress.getCurrentLegProgress()).thenReturn(legProgress);
    when(routeProgress.getDirectionsRoute()).thenReturn(mock(DirectionsRoute.class));
    return routeProgress;
  }
}
