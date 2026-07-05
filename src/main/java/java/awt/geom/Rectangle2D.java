package java.awt.geom;

public abstract class Rectangle2D {
    public abstract double getX();
    public abstract double getY();
    public abstract double getWidth();
    public abstract double getHeight();
    public abstract boolean isEmpty();

    public static class Float extends Rectangle2D {
        public float x, y, width, height;

        public Float() {}
        public Float(float x, float y, float w, float h) {
            this.x = x; this.y = y; this.width = w; this.height = h;
        }

        @Override public double getX() { return x; }
        @Override public double getY() { return y; }
        @Override public double getWidth() { return width; }
        @Override public double getHeight() { return height; }
        @Override public boolean isEmpty() { return width <= 0 || height <= 0; }
    }

    public static class Double extends Rectangle2D {
        public double x, y, width, height;

        public Double() {}
        public Double(double x, double y, double w, double h) {
            this.x = x; this.y = y; this.width = w; this.height = h;
        }

        @Override public double getX() { return x; }
        @Override public double getY() { return y; }
        @Override public double getWidth() { return width; }
        @Override public double getHeight() { return height; }
        @Override public boolean isEmpty() { return width <= 0 || height <= 0; }
    }
}