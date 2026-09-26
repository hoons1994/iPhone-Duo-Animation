import java.nio.file.Files;
import java.nio.file.Path;
import com.hoons1994.iphoneduoanimation.SnapshotTransitionShader;

/** Exports the compiled application's shader for desktop Skia diagnostics. */
public class ExportShader {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]), SnapshotTransitionShader.INSTANCE.getSOURCE());
    }
}
