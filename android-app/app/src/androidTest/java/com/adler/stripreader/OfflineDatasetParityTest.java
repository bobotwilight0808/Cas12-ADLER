package com.adler.stripreader;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class OfflineDatasetParityTest {
    @Test
    public void matchesCalibratedDatasetMetrics() throws Exception {
        AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        String[] datasetFiles = assets.list("dataset");
        assumeTrue("Enable local dataset tests with -PincludeCalibrationDataset=true.",
                datasetFiles != null && datasetFiles.length > 0);
        assertTrue(OpenCVLoader.initLocal());
        StripReader reader = new StripReader();
        Map<String, String[]> overrides = loadOverrides(assets);
        int singleCount = 0;
        int singleReadable = 0;
        int singleCorrect = 0;
        int dualCount = 0;
        int dualReadable = 0;
        int dualCorrect = 0;

        try (BufferedReader labels = new BufferedReader(new InputStreamReader(
                assets.open("labels.csv"), StandardCharsets.UTF_8))) {
            labels.readLine();
            String line;
            while ((line = labels.readLine()) != null) {
                String[] columns = line.split(",", -1);
                if (columns.length < 9 || !"yes".equals(columns[8])) continue;
                String path = columns[0].replace("\uFEFF", "");
                String stripType = columns[1];
                String expectedSingle = columns[5];
                String expectedT1 = columns[6];
                String expectedT2 = columns[7];
                String[] override = overrides.get(path);
                if (override != null) {
                    if (!override[1].isEmpty()) expectedSingle = override[1];
                    if (!override[2].isEmpty()) expectedT1 = override[2];
                    if (!override[3].isEmpty()) expectedT2 = override[3];
                }
                Bitmap bitmap = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(assets, "dataset/" + path),
                        (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
                Mat rgba = new Mat();
                Mat bgr = new Mat();
                Utils.bitmapToMat(bitmap, rgba);
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
                AnalysisResult result = reader.analyze(bgr, stripType);
                rgba.release();
                bgr.release();
                bitmap.recycle();

                boolean readable = "ok".equals(result.readability);
                if ("single".equals(stripType)) {
                    singleCount++;
                    if (readable) singleReadable++;
                    if (readable && columns[4].equals(result.validity)
                            && expectedSingle.equals(result.singleResult)) {
                        singleCorrect++;
                    } else {
                        System.out.println("SINGLE_MISMATCH " + path
                                + " expected=" + columns[4] + "/" + expectedSingle
                                + " actual=" + result.readability + "/" + result.validity
                                + "/" + result.singleResult + " " + lineSummary(result));
                    }
                } else {
                    dualCount++;
                    if (readable) dualReadable++;
                    if (readable && columns[4].equals(result.validity)
                            && expectedT1.equals(result.t1Result)
                            && expectedT2.equals(result.t2Result)) {
                        dualCorrect++;
                    } else {
                        System.out.println("DUAL_MISMATCH " + path
                                + " expected=" + columns[4] + "/" + expectedT1 + "/" + expectedT2
                                + " actual=" + result.readability + "/" + result.validity
                                + "/" + result.t1Result + "/" + result.t2Result
                                + " " + lineSummary(result));
                    }
                }
            }
        }

        System.out.println("DATASET_METRICS single=" + singleCorrect + "/" + singleCount
                + " readable=" + singleReadable + " dual=" + dualCorrect + "/" + dualCount
                + " readable=" + dualReadable);

        assertEquals(41, singleCount);
        assertEquals(41, singleReadable);
        assertEquals(39, singleCorrect);
        assertEquals(67, dualCount);
        assertEquals(67, dualReadable);
        assertEquals(67, dualCorrect);
    }

    private static Map<String, String[]> loadOverrides(AssetManager assets) throws Exception {
        Map<String, String[]> output = new HashMap<>();
        try (BufferedReader rows = new BufferedReader(new InputStreamReader(
                assets.open("label_overrides.csv"), StandardCharsets.UTF_8))) {
            rows.readLine();
            String line;
            while ((line = rows.readLine()) != null) {
                String[] columns = line.split(",", -1);
                if (columns.length >= 4) output.put(columns[0].replace("\uFEFF", ""), columns);
            }
        }
        return output;
    }

    private static String lineSummary(AnalysisResult result) {
        StringBuilder output = new StringBuilder();
        for (AnalysisResult.StripReading strip : result.strips) {
            output.append("strip").append(strip.index).append('[');
            for (AnalysisResult.LineReading reading : strip.lines.values()) {
                output.append(reading.name).append('=')
                        .append(String.format(java.util.Locale.US, "%.2f", reading.score))
                        .append(reading.present ? "+" : "-").append(' ');
            }
            output.append(']');
        }
        return output.toString();
    }
}
