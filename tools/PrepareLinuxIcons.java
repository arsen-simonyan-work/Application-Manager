import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class PrepareLinuxIcons {
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected source PNG, output directory and icon name");
        }
        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        if (source == null) throw new IOException("Cannot read application icon: " + args[0]);
        Path output = Path.of(args[1]);
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256, 512}) {
            BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            var graphics = icon.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                double scale = Math.min((double) size / source.getWidth(), (double) size / source.getHeight());
                int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
                graphics.drawImage(source, (size - width) / 2, (size - height) / 2, width, height, null);
            } finally {
                graphics.dispose();
            }
            Path file = output.resolve(size + "x" + size).resolve("apps").resolve(args[2] + ".png");
            Files.createDirectories(file.getParent());
            if (!ImageIO.write(icon, "png", file.toFile())) throw new IOException("Cannot write icon: " + file);
        }
    }
}
