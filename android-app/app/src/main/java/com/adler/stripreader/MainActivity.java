package com.adler.stripreader;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final int REQUEST_GALLERY = 1001;
    private static final int REQUEST_CAMERA = 1002;
    private static final int MAX_ANALYSIS_DIMENSION = 4096;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final StripReader stripReader = new StripReader();

    private ScrollView homeScreen;
    private ScrollView analysisScreen;
    private TextView modeTitle;
    private DetectionImageView detectionImage;
    private ResultStripView resultStripView;
    private View progressContainer;
    private View errorContainer;
    private TextView errorMessage;
    private View resultContainer;
    private View resultSummary;
    private TextView resultTitle;
    private TextView resultMeta;
    private TextView confidenceNotice;
    private LinearLayout lineDetailsContainer;
    private Button primaryActionButton;
    private ImageButton retryButton;

    private Uri selectedImageUri;
    private Uri pendingCameraUri;
    private String currentStripType = "dual";
    private boolean currentSourceIsCamera;
    private boolean openCvReady;
    private int requestGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        applySystemInsets();
        bindActions();
        bindSystemBack();
        openCvReady = OpenCVLoader.initLocal();
        showHome();
        if (!openCvReady) {
            Toast.makeText(this, R.string.offline_engine_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void bindViews() {
        homeScreen = findViewById(R.id.home_screen);
        analysisScreen = findViewById(R.id.analysis_screen);
        modeTitle = findViewById(R.id.mode_title);
        detectionImage = findViewById(R.id.detection_image);
        resultStripView = findViewById(R.id.result_strip_view);
        progressContainer = findViewById(R.id.progress_container);
        errorContainer = findViewById(R.id.error_container);
        errorMessage = findViewById(R.id.error_message);
        resultContainer = findViewById(R.id.result_container);
        resultSummary = findViewById(R.id.result_summary);
        resultTitle = findViewById(R.id.result_title);
        resultMeta = findViewById(R.id.result_meta);
        confidenceNotice = findViewById(R.id.confidence_notice);
        lineDetailsContainer = findViewById(R.id.line_details_container);
        primaryActionButton = findViewById(R.id.primary_action_button);
        retryButton = findViewById(R.id.retry_button);
    }

    private void applySystemInsets() {
        View root = findViewById(R.id.root_frame);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom()
                );
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private void bindActions() {
        findViewById(R.id.camera_option).setOnClickListener(view -> chooseCameraStripType());
        findViewById(R.id.single_option).setOnClickListener(view -> launchGallery("single"));
        findViewById(R.id.dual_option).setOnClickListener(view -> launchGallery("dual"));
        findViewById(R.id.back_button).setOnClickListener(view -> showHome());
        retryButton.setOnClickListener(view -> {
            if (selectedImageUri != null) beginAnalysis();
        });
        primaryActionButton.setOnClickListener(view -> {
            if (currentSourceIsCamera) launchCamera();
            else launchGallery(currentStripType);
        });
    }

    private void bindSystemBack() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (analysisScreen.getVisibility() == View.VISIBLE) showHome();
                else finish();
            }
        });
    }

    private void chooseCameraStripType() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.mode_dialog_title)
                .setItems(new String[]{getString(R.string.single_target), getString(R.string.dual_target)},
                        (dialog, which) -> {
                            currentStripType = which == 0 ? "single" : "dual";
                            currentSourceIsCamera = true;
                            launchCamera();
                        })
                .show();
    }

    private void launchGallery(String stripType) {
        currentStripType = stripType;
        currentSourceIsCamera = false;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_GALLERY);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.image_picker_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void launchCamera() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "adler-strip-" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ADLER");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        pendingCameraUri = getContentResolver().insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (pendingCameraUri == null) {
            Toast.makeText(this, R.string.camera_output_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
        intent.setClipData(ClipData.newRawUri("ADLER capture", pendingCameraUri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_CAMERA);
        } catch (ActivityNotFoundException exception) {
            getContentResolver().delete(pendingCameraUri, null, null);
            pendingCameraUri = null;
            Toast.makeText(this, R.string.camera_app_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_GALLERY && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            selectedImageUri = data.getData();
            int permissionFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permissionFlags != 0) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            selectedImageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // The temporary read grant remains enough for the current analysis.
                }
            }
            beginAnalysis();
            return;
        }

        if (requestCode == REQUEST_CAMERA) {
            if (resultCode == RESULT_OK && pendingCameraUri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(pendingCameraUri, values, null, null);
                selectedImageUri = pendingCameraUri;
                pendingCameraUri = null;
                currentSourceIsCamera = true;
                beginAnalysis();
            } else if (pendingCameraUri != null) {
                getContentResolver().delete(pendingCameraUri, null, null);
                pendingCameraUri = null;
            }
        }
    }

    private void beginAnalysis() {
        if (selectedImageUri == null) return;
        int generation = ++requestGeneration;
        showAnalysisScreen();
        modeTitle.setText("single".equals(currentStripType)
                ? R.string.single_mode_title : R.string.dual_mode_title);
        progressContainer.setVisibility(View.VISIBLE);
        errorContainer.setVisibility(View.GONE);
        resultContainer.setVisibility(View.GONE);
        primaryActionButton.setVisibility(View.GONE);
        detectionImage.setCornerSets(Collections.emptyList());
        resultStripView.prepare(currentStripType);

        if (!openCvReady) {
            renderError(getString(R.string.offline_engine_unavailable));
            return;
        }
        Uri imageUri = selectedImageUri;
        String stripType = currentStripType;
        analysisExecutor.execute(() -> decodeAndAnalyze(imageUri, stripType, generation));
    }

    private void decodeAndAnalyze(Uri imageUri, String stripType, int generation) {
        Mat rgba = null;
        Mat bgr = null;
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), imageUri);
            Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int longestSide = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sampleSize = Math.max(1,
                        (int) Math.ceil(longestSide / (double) MAX_ANALYSIS_DIMENSION));
                decoder.setTargetSampleSize(sampleSize);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            rgba = new Mat();
            bgr = new Mat();
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
            AnalysisResult result = stripReader.analyze(bgr, stripType);
            mainHandler.post(() -> {
                if (generation != requestGeneration) return;
                detectionImage.setBitmap(bitmap, bitmap.getWidth(), bitmap.getHeight());
                renderResult(result);
            });
        } catch (IOException | RuntimeException exception) {
            String detail = exception.getMessage();
            String message = detail == null || detail.trim().isEmpty()
                    ? getString(R.string.image_processing_failed)
                    : detail;
            mainHandler.post(() -> {
                if (generation != requestGeneration) return;
                renderError(message);
            });
        } finally {
            if (rgba != null) rgba.release();
            if (bgr != null) bgr.release();
        }
    }

    private void renderResult(AnalysisResult result) {
        progressContainer.setVisibility(View.GONE);
        errorContainer.setVisibility(View.GONE);
        resultContainer.setVisibility(View.VISIBLE);
        primaryActionButton.setVisibility(View.VISIBLE);
        primaryActionButton.setText(currentSourceIsCamera
                ? R.string.capture_again : R.string.choose_another);
        resultTitle.setText(result.displayTitle());
        resultTitle.setTextColor(colorForTone(result.tone()));
        resultSummary.setBackground(resultSummaryBackground(result.tone()));
        if ("ok".equals(result.readability)) {
            resultMeta.setText(String.format(Locale.US,
                    getString(R.string.result_meta_format),
                    result.confidence.toUpperCase(Locale.US),
                    result.stripCount,
                    getResources().getQuantityString(R.plurals.strip_count_label, result.stripCount),
                    getString("single".equals(result.stripType)
                            ? R.string.single_target : R.string.dual_target)));
        } else {
            resultMeta.setText(R.string.unreadable_result_meta);
        }
        confidenceNotice.setVisibility(
                "low".equals(result.confidence) && "ok".equals(result.readability)
                        ? View.VISIBLE : View.GONE);
        detectionImage.setCornerSets(result.cornerSets());
        resultStripView.setResult(result);
        renderLineDetails(result);
    }

    private void renderError(String message) {
        progressContainer.setVisibility(View.GONE);
        resultContainer.setVisibility(View.GONE);
        errorContainer.setVisibility(View.VISIBLE);
        errorMessage.setText(message);
        primaryActionButton.setVisibility(View.VISIBLE);
        primaryActionButton.setText(currentSourceIsCamera
                ? R.string.capture_again : R.string.choose_another);
    }

    private void renderLineDetails(AnalysisResult result) {
        lineDetailsContainer.removeAllViews();
        if (result.strips.isEmpty()) {
            TextView empty = textView(getString(R.string.strip_photo_hint),
                    12, R.color.muted);
            empty.setPadding(0, dp(8), 0, dp(6));
            lineDetailsContainer.addView(empty);
            return;
        }

        String[] order = "single".equals(result.stripType)
                ? new String[]{"T", "C"} : new String[]{"T2", "T1", "C"};
        for (int stripIndex = 0; stripIndex < result.strips.size(); stripIndex++) {
            AnalysisResult.StripReading strip = result.strips.get(stripIndex);
            if (result.strips.size() > 1) {
                TextView stripHeader = textView(
                        String.format(Locale.US, getString(R.string.strip_header_format),
                                strip.index + 1, strip.confidence.toUpperCase(Locale.US)),
                        10, R.color.muted);
                LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                headerParams.topMargin = stripIndex == 0 ? dp(4) : dp(18);
                stripHeader.setLayoutParams(headerParams);
                lineDetailsContainer.addView(stripHeader);
            }
            for (String lineName : order) {
                AnalysisResult.LineReading reading = strip.lines.get(lineName);
                if (reading != null) addLineReading(reading);
            }
        }
    }

    private void addLineReading(AnalysisResult.LineReading reading) {
        LinearLayout block = new LinearLayout(this);
        block.setGravity(android.view.Gravity.CENTER_VERTICAL);
        block.setOrientation(LinearLayout.HORIZONTAL);
        block.setPadding(0, dp(13), 0, dp(13));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = textView(lineDisplayName(reading.name), 11, R.color.ink);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        labels.addView(name);
        TextView threshold = textView(String.format(Locale.US,
                getString(R.string.threshold_format), reading.threshold), 9, R.color.muted);
        LinearLayout.LayoutParams thresholdParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thresholdParams.topMargin = dp(4);
        labels.addView(threshold, thresholdParams);
        block.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout values = new LinearLayout(this);
        values.setGravity(android.view.Gravity.END);
        values.setOrientation(LinearLayout.VERTICAL);
        TextView score = textView(String.format(Locale.US, "%.3f", reading.score),
                15, R.color.ink);
        score.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        score.setGravity(android.view.Gravity.END);
        values.addView(score);
        TextView state = textView(getString(reading.present
                ? R.string.line_visible : R.string.line_not_visible), 9,
                reading.present ? R.color.coral : R.color.muted);
        state.setTypeface(state.getTypeface(), reading.present
                ? Typeface.BOLD : Typeface.NORMAL);
        state.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stateParams.topMargin = dp(3);
        values.addView(state, stateParams);
        block.addView(values, new LinearLayout.LayoutParams(
                dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        lineDetailsContainer.addView(block);

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.line));
        lineDetailsContainer.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private String lineDisplayName(String name) {
        if ("C".equals(name)) return getString(R.string.control_line_label);
        return getString(R.string.test_line_format, name);
    }

    private GradientDrawable resultSummaryBackground(AnalysisResult.Tone tone) {
        int fill;
        int border;
        if (tone == AnalysisResult.Tone.POSITIVE) {
            fill = getColor(R.color.coral_soft);
            border = getColor(R.color.coral);
        } else if (tone == AnalysisResult.Tone.NEGATIVE) {
            fill = getColor(R.color.green_soft);
            border = getColor(R.color.green_dark);
        } else {
            fill = getColor(R.color.amber_soft);
            border = getColor(R.color.amber);
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(6));
        background.setStroke(dp(1), border);
        return background;
    }

    private TextView textView(String text, int sizeSp, int colorResource) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(getColor(colorResource));
        return view;
    }

    private int colorForTone(AnalysisResult.Tone tone) {
        if (tone == AnalysisResult.Tone.POSITIVE) return getColor(R.color.coral);
        if (tone == AnalysisResult.Tone.NEGATIVE) return getColor(R.color.green_dark);
        return getColor(R.color.amber);
    }

    private void showAnalysisScreen() {
        homeScreen.setVisibility(View.GONE);
        analysisScreen.setVisibility(View.VISIBLE);
        analysisScreen.scrollTo(0, 0);
    }

    private void showHome() {
        requestGeneration++;
        analysisScreen.setVisibility(View.GONE);
        homeScreen.setVisibility(View.VISIBLE);
        homeScreen.scrollTo(0, 0);
        detectionImage.clear();
        resultStripView.clear();
        resultContainer.setVisibility(View.GONE);
        errorContainer.setVisibility(View.GONE);
        progressContainer.setVisibility(View.VISIBLE);
        primaryActionButton.setVisibility(View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        requestGeneration++;
        analysisExecutor.shutdownNow();
        super.onDestroy();
    }
}
