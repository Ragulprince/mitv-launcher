// Rotates portrait phone wallpapers a quarter-turn counter-clockwise into landscape JPEGs (mx1.jpg ...).
// Usage: java tools/RotateWallpapers.java <input-folder> <app/src/main/assets/wall>
// Edit the file-name list below to your own images, and pick cw/ccw by looking at the result.
import javax.imageio.*;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;

public class RotateWallpapers {
    public static void main(String[] a) throws Exception {
        String[] in = {"ab34551e92ee46e87050633f2ea591d3", "a9645a534f1353e13c74cce5db4f96c8",
                "9744a3de41cbcc85f0e296c3dab1779b", "103e39e5bb6602dff3b368c73fea69bd"};
        for (int i = 0; i < in.length; i++) {
            BufferedImage s = ImageIO.read(new File(a[0], in[i] + ".jpg"));
            int w = s.getWidth(), h = s.getHeight();
            BufferedImage o = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) o.setRGB(y, w - 1 - x, s.getRGB(x, y)); // counter-clockwise
            ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(.93f);
            try (FileImageOutputStream out = new FileImageOutputStream(new File(a[1], "mx" + (i + 1) + ".jpg"))) {
                wr.setOutput(out);
                wr.write(null, new IIOImage(o, null, null), p);
            }
            System.out.println("mx" + (i + 1) + " " + o.getWidth() + "x" + o.getHeight());
        }
    }
}
