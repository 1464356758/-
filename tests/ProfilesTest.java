import com.cameraprofile.studio.JpegEngine;
import java.nio.file.*;

public class ProfilesTest {
  public static void main(String[] a) throws Exception {
    byte[] in = Files.readAllBytes(Paths.get("tests/input.jpg"));
    Files.write(
        Paths.get("tests/profile-0.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "Apple", "iPhone 18 Pro Max", 1, 120, 80, null, null, 1.48d, 24, null, null)));
    Files.write(
        Paths.get("tests/profile-1.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "Apple", "iPhone 18 Pro Max", 1, 120, 80, null, null, 1.8d, 24, null, null)));
    Files.write(
        Paths.get("tests/profile-2.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "Apple", "iPhone 18 Pro Max", 1, 120, 80, null, null, 2.8d, 24, null, null)));
    Files.write(
        Paths.get("tests/profile-3.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "Apple", "iPhone 18 Pro Max", 1, 120, 80, null, null, 4.0d, 24, null, null)));
    Files.write(
        Paths.get("tests/profile-4.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "Apple", "iPhone 18 Pro Max", 1, 120, 80, null, null, 2.2d, 13, null, null)));
    Files.write(
        Paths.get("tests/profile-5.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "Apple", "iPhone 18 Pro Max", 1, 120, 80, null, null, 2.8d, 100, null, null)));
    Files.write(
        Paths.get("tests/profile-6.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "HUAWEI", "HUAWEI Mate 90 Pro Max", 1, 120, 80, null, null, 1.4d, 24, null, null)));
    Files.write(
        Paths.get("tests/profile-7.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "HUAWEI", "HUAWEI Mate 90 Pro Max", 1, 120, 80, null, null, 4d, 24, null, null)));
    Files.write(
        Paths.get("tests/profile-8.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "HUAWEI", "HUAWEI Mate 90 Pro Max", 1, 120, 80, null, null, 2.2d, 13, null, null)));
    Files.write(
        Paths.get("tests/profile-9.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "HUAWEI", "HUAWEI Mate 90 Pro Max", 1, 120, 80, null, null, 2.6d, 89, null, null)));
    Files.write(
        Paths.get("tests/profile-10.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "Xiaomi", "Xiaomi 18 Pro Max", 1, 120, 80, null, null, 1.67d, null, null, null)));
    Files.write(
        Paths.get("tests/profile-11.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "Xiaomi", "Xiaomi 18 Pro Max", 1, 120, 80, null, null, 2.4d, 75, null, null)));
    Files.write(
        Paths.get("tests/profile-12.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif("Apple", "iPhone 17", 1, 120, 80, null, null, 1.6d, 26, null, null)));
    Files.write(
        Paths.get("tests/profile-13.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif("Apple", "iPhone 17", 1, 120, 80, null, null, 2.2d, 13, null, null)));
    Files.write(
        Paths.get("tests/profile-14.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif("Apple", "iPhone 17", 1, 120, 80, null, null, 1.6d, 52, null, null)));
    Files.write(
        Paths.get("tests/profile-15.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "LEICA CAMERA AG",
                "LEICA Q3 43",
                1,
                120,
                80,
                null,
                null,
                2d,
                43,
                43d,
                "APO-Summicron 43 f/2 ASPH.")));
    Files.write(
        Paths.get("tests/profile-16.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "LEICA CAMERA AG",
                "LEICA Q3 43",
                1,
                120,
                80,
                null,
                null,
                2.8d,
                43,
                43d,
                "APO-Summicron 43 f/2 ASPH.")));
    Files.write(
        Paths.get("tests/profile-17.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "LEICA CAMERA AG",
                "LEICA Q3 43",
                1,
                120,
                80,
                null,
                null,
                4d,
                43,
                43d,
                "APO-Summicron 43 f/2 ASPH.")));
    Files.write(
        Paths.get("tests/profile-18.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "LEICA CAMERA AG",
                "LEICA Q3 43",
                1,
                120,
                80,
                null,
                null,
                5.6d,
                43,
                43d,
                "APO-Summicron 43 f/2 ASPH.")));
    Files.write(
        Paths.get("tests/profile-19.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "LEICA CAMERA AG",
                "LEICA Q3 43",
                1,
                120,
                80,
                null,
                null,
                8d,
                43,
                43d,
                "APO-Summicron 43 f/2 ASPH.")));
    Files.write(
        Paths.get("tests/profile-20.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "FUJIFILM",
                "GFX100 II",
                1,
                120,
                80,
                null,
                null,
                1.7d,
                44,
                55d,
                "GF55mmF1.7 R WR")));
    Files.write(
        Paths.get("tests/profile-21.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "FUJIFILM",
                "GFX100 II",
                1,
                120,
                80,
                null,
                null,
                2.8d,
                44,
                55d,
                "GF55mmF1.7 R WR")));
    Files.write(
        Paths.get("tests/profile-22.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "FUJIFILM", "GFX100 II", 1, 120, 80, null, null, 4d, 44, 55d, "GF55mmF1.7 R WR")));
    Files.write(
        Paths.get("tests/profile-23.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "FUJIFILM",
                "GFX100 II",
                1,
                120,
                80,
                null,
                null,
                5.6d,
                44,
                55d,
                "GF55mmF1.7 R WR")));
    Files.write(
        Paths.get("tests/profile-24.jpg"),
        JpegEngine.rebuild(
            in,
            JpegEngine.exif(
                "FUJIFILM", "GFX100 II", 1, 120, 80, null, null, 8d, 44, 55d, "GF55mmF1.7 R WR")));
    System.out.println("PASS: all six profiles / 25 lens configurations encoded");
  }
}
