package com.zcshou.utils;

import com.baidu.mapapi.model.LatLng;

import java.util.ArrayList;
import java.util.List;

public class CampusRunUtils {
    public static final int TRACK_LANE_COUNT = 8;
    public static final double TRACK_STRAIGHT_LENGTH_M = 87.0;
    public static final double TRACK_INNER_RADIUS_M = 36.0;
    public static final double TRACK_LANE_WIDTH_M = 1.2;
    public static final double DEFAULT_TRACK_RADIUS_M = TRACK_INNER_RADIUS_M;
    private static final double METERS_PER_LAT_DEG = 110574.0;

    private CampusRunUtils() {
    }

    public static List<LatLng> buildTrackPreview(LatLng center, double angleFromNorthDeg, double stepMeters) {
        return buildTrackPreview(center, angleFromNorthDeg, stepMeters, 0);
    }

    public static List<LatLng> buildTrackPreview(LatLng center, double angleFromNorthDeg, double stepMeters, int laneIndex) {
        List<LatLng> result = new ArrayList<>();
        if (center == null) {
            return result;
        }

        int safeLane = Math.max(0, Math.min(TRACK_LANE_COUNT - 1, laneIndex));
        double radius = TRACK_INNER_RADIUS_M + safeLane * TRACK_LANE_WIDTH_M;
        double halfStraight = TRACK_STRAIGHT_LENGTH_M / 2.0;
        double normalizedStep = Math.max(1.0, stepMeters);

        // Counterclockwise:
        // 1) top straight: right -> left
        appendStraight(result, center, angleFromNorthDeg, halfStraight, radius, -halfStraight, radius, normalizedStep);
        // 2) left arc: top -> bottom (outer side)
        appendArc(result, center, angleFromNorthDeg, -halfStraight, 0.0, radius, 90.0, 270.0, normalizedStep);
        // 3) bottom straight: left -> right
        appendStraight(result, center, angleFromNorthDeg, -halfStraight, -radius, halfStraight, -radius, normalizedStep);
        // 4) right arc: bottom -> top (outer side)
        appendArc(result, center, angleFromNorthDeg, halfStraight, 0.0, radius, -90.0, 90.0, normalizedStep);

        // Move start to the opposite corner of the current forced start.
        LatLng forcedStart = localMetersToLatLng(center, -halfStraight, radius, angleFromNorthDeg);
        rotateToNearestStart(result, forcedStart);

        if (!result.isEmpty()) {
            result.add(result.get(0));
        }
        return result;
    }

    public static List<LatLng> buildInnerLanePreview(LatLng center, double angleFromNorthDeg, double stepMeters) {
        return buildTrackPreview(center, angleFromNorthDeg, stepMeters, 0);
    }

    public static List<LatLng> buildOuterLanePreview(LatLng center, double angleFromNorthDeg, double stepMeters) {
        return buildTrackPreview(center, angleFromNorthDeg, stepMeters, TRACK_LANE_COUNT - 1);
    }

    public static LatLng resolveCenterFromTopPoint(LatLng topPoint, double angleFromNorthDeg) {
        if (topPoint == null) {
            return null;
        }
        // Selected point is the highest point on the top arc: (x=halfStraight+r, y=0)
        // Move backward along track heading by (halfStraight+r) to get center.
        double halfStraight = TRACK_STRAIGHT_LENGTH_M / 2.0;
        double offset = halfStraight + TRACK_INNER_RADIUS_M;
        return localMetersToLatLng(topPoint, -offset, 0.0, angleFromNorthDeg);
    }

    private static void appendStraight(List<LatLng> out, LatLng center, double angleDeg,
                                       double sx, double sy, double ex, double ey, double stepMeters) {
        double dx = ex - sx;
        double dy = ey - sy;
        double distance = Math.hypot(dx, dy);
        int steps = Math.max(1, (int) Math.ceil(distance / stepMeters));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            double x = sx + dx * t;
            double y = sy + dy * t;
            out.add(localMetersToLatLng(center, x, y, angleDeg));
        }
    }

    private static void appendArc(List<LatLng> out, LatLng center, double angleDeg,
                                  double cx, double cy, double radius,
                                  double startDeg, double endDeg, double stepMeters) {
        double arcLen = Math.abs(endDeg - startDeg) / 360.0 * (2.0 * Math.PI * radius);
        int steps = Math.max(4, (int) Math.ceil(arcLen / stepMeters));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            double deg = startDeg + (endDeg - startDeg) * t;
            double rad = Math.toRadians(deg);
            double x = cx + radius * Math.cos(rad);
            double y = cy + radius * Math.sin(rad);
            out.add(localMetersToLatLng(center, x, y, angleDeg));
        }
    }

    private static LatLng localMetersToLatLng(LatLng center, double xMeters, double yMeters, double angleFromNorthDeg) {
        double headingRad = Math.toRadians(angleFromNorthDeg);
        double headingEast = Math.sin(headingRad);
        double headingNorth = Math.cos(headingRad);
        double leftEast = -headingNorth;
        double leftNorth = headingEast;

        double eastMeters = xMeters * headingEast + yMeters * leftEast;
        double northMeters = xMeters * headingNorth + yMeters * leftNorth;

        double lat = center.latitude + northMeters / METERS_PER_LAT_DEG;
        double metersPerLngDeg = 111320.0 * Math.cos(Math.toRadians(center.latitude));
        double lng = center.longitude + eastMeters / metersPerLngDeg;
        return new LatLng(lat, lng);
    }

    private static void rotateToNearestStart(List<LatLng> points, LatLng targetStart) {
        if (points == null || points.isEmpty() || targetStart == null) {
            return;
        }

        int bestIndex = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            LatLng p = points.get(i);
            double dLat = p.latitude - targetStart.latitude;
            double dLng = p.longitude - targetStart.longitude;
            double d2 = dLat * dLat + dLng * dLng;
            if (d2 < bestDist) {
                bestDist = d2;
                bestIndex = i;
            }
        }

        if (bestIndex == 0) {
            return;
        }

        List<LatLng> rotated = new ArrayList<>(points.size());
        rotated.addAll(points.subList(bestIndex, points.size()));
        rotated.addAll(points.subList(0, bestIndex));
        points.clear();
        points.addAll(rotated);
    }

}
