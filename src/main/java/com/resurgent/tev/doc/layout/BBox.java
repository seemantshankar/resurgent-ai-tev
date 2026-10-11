package com.resurgent.tev.doc.layout;

/** Axis-aligned box in PDF user space (points), origin top-left of the page. */
public record BBox(double x0, double y0, double x1, double y1) {

    /**
     * Normalizes reversed endpoints independently on each axis. Coordinates use
     * PDF points with the page's top-left as origin.
     */
    public BBox {
        if (x1 < x0) {
            double t = x0;
            x0 = x1;
            x1 = t;
        }
        if (y1 < y0) {
            double t = y0;
            y0 = y1;
            y1 = t;
        }
    }

    public double width() {
        return x1 - x0;
    }

    public double height() {
        return y1 - y0;
    }

    public double centerX() {
        return (x0 + x1) / 2.0;
    }

    public double centerY() {
        return (y0 + y1) / 2.0;
    }

    public BBox union(BBox other) {
        return new BBox(
                Math.min(x0, other.x0),
                Math.min(y0, other.y0),
                Math.max(x1, other.x1),
                Math.max(y1, other.y1));
    }
}
