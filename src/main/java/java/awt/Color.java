package java.awt;

import java.io.Serializable;

public class Color implements Serializable {
    private final int r, g, b;
    private final int alpha;

    public Color(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public Color(int r, int g, int b, int a) {
        this.r = r & 0xFF;
        this.g = g & 0xFF;
        this.b = b & 0xFF;
        this.alpha = a & 0xFF;
    }

    public Color(int rgb) {
        this((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, (rgb >> 24) & 0xFF);
    }

    public int getRed()     { return r; }
    public int getGreen()   { return g; }
    public int getBlue()    { return b; }
    public int getAlpha()   { return alpha; }
    public int getRGB()     { return (alpha << 24) | (r << 16) | (g << 8) | b; }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Color) { Color c = (Color) o; return getRGB() == c.getRGB(); }
        return false;
    }

    @Override
    public int hashCode() { return getRGB(); }
}