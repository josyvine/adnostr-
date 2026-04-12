package com.adnostr.app;

import android.graphics.Matrix;
import android.graphics.PointF;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivityImageZoomBinding;

import java.io.IOException;

import coil.Coil;
import coil.request.ImageRequest;
import coil.request.SuccessResult;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fullscreen Secure Zoom Portal.
 * FEATURE: Downloads and decrypts the ad media in high resolution.
 * FEATURE: Implements smooth Pinch-to-Zoom and Drag-to-Pan logic.
 * FEATURE: 100% immersive fullscreen mode.
 */
public class ImageZoomActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Zoom";
    private ActivityImageZoomBinding binding;
    private final OkHttpClient httpClient = new OkHttpClient();

    // Zoom and Pan State Variables
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private PointF start = new PointF();
    private PointF mid = new PointF();
    private float oldDist = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Enable Full-Immersive Mode
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        binding = ActivityImageZoomBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String url = getIntent().getStringExtra("ZOOM_URL");
        String keyHex = getIntent().getStringExtra("AES_KEY");

        if (url == null || url.isEmpty()) {
            finish();
            return;
        }

        // 2. Fetch and Decrypt for Fullscreen viewing
        fetchFullImage(url, keyHex);

        // 3. Setup Touch Interaction Logic
        setupZoomLogic();

        binding.btnCloseZoom.setOnClickListener(v -> finish());
    }

    private void fetchFullImage(String url, String keyHex) {
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(ImageZoomActivity.this, "Failed to load image", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) return;

                try {
                    byte[] encryptedBytes = response.body().bytes();
                    byte[] finalImageData;

                    if (keyHex != null && !keyHex.isEmpty()) {
                        byte[] aesKey = EncryptionUtils.hexToBytes(keyHex);
                        finalImageData = EncryptionUtils.decrypt(encryptedBytes, aesKey);
                    } else {
                        finalImageData = encryptedBytes;
                    }

                    runOnUiThread(() -> {
                        ImageRequest imageRequest = new ImageRequest.Builder(ImageZoomActivity.this)
                                .data(finalImageData)
                                .target(binding.ivZoomableContent)
                                .listener(new ImageRequest.Listener() {
                                    @Override
                                    public void onSuccess(@NonNull ImageRequest request, @NonNull SuccessResult result) {
                                        // FIXED: Corrected signature for Coil 2.x
                                        // Once loaded, center the image via Matrix
                                        centerImageInitially();
                                    }
                                })
                                .build();
                        Coil.imageLoader(ImageZoomActivity.this).enqueue(imageRequest);
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Zoom Decryption Error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Centers the image in the matrix view so it doesn't start at 0,0.
     */
    private void centerImageInitially() {
        binding.ivZoomableContent.post(() -> {
            if (binding.ivZoomableContent.getDrawable() == null) return;
            
            float viewWidth = binding.ivZoomableContent.getWidth();
            float viewHeight = binding.ivZoomableContent.getHeight();
            float drawableWidth = binding.ivZoomableContent.getDrawable().getIntrinsicWidth();
            float drawableHeight = binding.ivZoomableContent.getDrawable().getIntrinsicHeight();

            float scale;
            if (drawableWidth / viewWidth > drawableHeight / viewHeight) {
                scale = viewWidth / drawableWidth;
            } else {
                scale = viewHeight / drawableHeight;
            }

            matrix.setScale(scale, scale);
            matrix.postTranslate((viewWidth - drawableWidth * scale) / 2, (viewHeight - drawableHeight * scale) / 2);
            binding.ivZoomableContent.setImageMatrix(matrix);
        });
    }

    /**
     * Implements standard Pinch and Drag gestures using raw Touch Events.
     */
    private void setupZoomLogic() {
        binding.ivZoomableContent.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ImageView view = (ImageView) v;

                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                        savedMatrix.set(matrix);
                        start.set(event.getX(), event.getY());
                        mode = DRAG;
                        break;

                    case MotionEvent.ACTION_POINTER_DOWN:
                        oldDist = spacing(event);
                        if (oldDist > 10f) {
                            savedMatrix.set(matrix);
                            midPoint(mid, event);
                            mode = ZOOM;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        mode = NONE;
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (mode == DRAG) {
                            matrix.set(savedMatrix);
                            matrix.postTranslate(event.getX() - start.x, event.getY() - start.y);
                        } else if (mode == ZOOM) {
                            float newDist = spacing(event);
                            if (newDist > 10f) {
                                matrix.set(savedMatrix);
                                float scale = newDist / oldDist;
                                matrix.postScale(scale, scale, mid.x, mid.y);
                            }
                        }
                        break;
                }

                view.setImageMatrix(matrix);
                return true;
            }

            private float spacing(MotionEvent event) {
                float x = event.getX(0) - event.getX(1);
                float y = event.getY(0) - event.getY(1);
                return (float) Math.sqrt(x * x + y * y);
            }

            private void midPoint(PointF point, MotionEvent event) {
                float x = event.getX(0) + event.getX(1);
                float y = event.getY(0) + event.getY(1);
                point.set(x / 2, y / 2);
            }
        });
    }
}