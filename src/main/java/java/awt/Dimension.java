package java.awt;

import java.io.Serializable;

/**
 * Minimal Dimension stub for Android + Apache POI compatibility.
 */
public class Dimension implements Serializable {
    public int width;
    public int height;

    public Dimension() {
        this(0, 0);
    }

    public Dimension(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Dimension) {
            Dimension d = (Dimension) obj;
            return width == d.width && height == d.height;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int sum = width + height;
        return sum * (sum + 1) / 2 + width;
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}