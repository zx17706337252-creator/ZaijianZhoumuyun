package java.awt;

import java.awt.geom.Rectangle2D;

public class Rectangle extends Rectangle2D implements java.io.Serializable {
    public int x, y, width, height;

    public Rectangle() { this(0, 0, 0, 0); }
    public Rectangle(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
    }

    @Override public double getX() { return x; }
    @Override public double getY() { return y; }
    @Override public double getWidth() { return width; }
    @Override public double getHeight() { return height; }
    @Override public boolean isEmpty() { return width <= 0 || height <= 0; }

    public void setRect(double x, double y, double w, double h) {
        this.x = (int)x; this.y = (int)y; this.width = (int)w; this.height = (int)h;
    }

    @Override
    public String toString() { return x + "," + y + " " + width + "x" + height; }
}