package com.example.android.heartrate;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";
    private TextureView textureView;
    private String cameraId;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private static final int REQUEST_CAMERA_PERMISSION = 1;

    // Thread handler member variables
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    // Heart rate detector member variables
    public static int hrtratebpm;
    private int mCurrentRollingAverage;
    private int mLastRollingAverage;
    private int mLastLastRollingAverage;
    private ArrayList<Long> mTimeArray;
    private int numCaptures = 0;
    TextView tv;

    // Chart variables
    private LineChart mChart;
    private LineDataSet mWaveDataSet;
    private LineDataSet mPeakDataSet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        
        textureView = findViewById(R.id.texture);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);
        
        mTimeArray = new ArrayList<>();
        tv = findViewById(R.id.neechewalatext);
        hrtratebpm = 0; // Reset

        mChart = findViewById(R.id.chart);
        setupChart();
    }

    private void setupChart() {
        mChart.getDescription().setEnabled(false);
        mChart.setTouchEnabled(false);
        mChart.setDragEnabled(false);
        mChart.setScaleEnabled(false);
        mChart.setDrawGridBackground(false);
        mChart.getLegend().setEnabled(true);
        mChart.setAutoScaleMinMaxEnabled(true);

        XAxis xAxis = mChart.getXAxis();
        xAxis.setDrawLabels(false);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);

        YAxis leftAxis = mChart.getAxisLeft();
        leftAxis.setDrawLabels(false);
        leftAxis.setDrawGridLines(false);
        leftAxis.setDrawAxisLine(false);
        
        mChart.getAxisRight().setEnabled(false);

        mWaveDataSet = new LineDataSet(new ArrayList<>(), "PPG Signal");
        mWaveDataSet.setColor(Color.parseColor("#FF4B4B"));
        mWaveDataSet.setLineWidth(2.5f);
        mWaveDataSet.setDrawCircles(false);
        mWaveDataSet.setDrawValues(false);
        mWaveDataSet.setMode(LineDataSet.Mode.LINEAR);

        mPeakDataSet = new LineDataSet(new ArrayList<>(), "Detected Peaks");
        mPeakDataSet.setColor(Color.TRANSPARENT); // Hide line
        mPeakDataSet.setCircleColor(Color.parseColor("#2196F3")); // Blue dots for peaks
        mPeakDataSet.setCircleRadius(6f);
        mPeakDataSet.setDrawCircleHole(false);
        mPeakDataSet.setDrawValues(false);

        LineData data = new LineData(mWaveDataSet, mPeakDataSet);
        mChart.setData(data);
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            Bitmap bmp = textureView.getBitmap();
            if (bmp == null) return;
            
            int width = bmp.getWidth();
            int height = bmp.getHeight();
            int[] pixels = new int[height * width];
            
            // Get a small block of pixels from the center
            int sampleWidth = width / 20;
            int sampleHeight = height / 20;
            bmp.getPixels(pixels, 0, width, width / 2, height / 2, sampleWidth, sampleHeight);
            
            int sum = 0;
            // Iterate only over the sampled pixels block
            for (int i = 0; i < sampleHeight; i++) {
                for (int j = 0; j < sampleWidth; j++) {
                    int pixel = pixels[i * width + j];
                    int red = (pixel >> 16) & 0xFF;
                    sum += red;
                }
            }

            if (numCaptures == 20) {
                mCurrentRollingAverage = sum;
            } else if (numCaptures > 20 && numCaptures < 49) {
                mCurrentRollingAverage = (mCurrentRollingAverage * (numCaptures - 20) + sum) / (numCaptures - 19);
            } else if (numCaptures >= 49) {
                mCurrentRollingAverage = (mCurrentRollingAverage * 29 + sum) / 30;
                
                boolean isPeak = false;
                // Peak detection
                if (mLastRollingAverage > mCurrentRollingAverage && mLastRollingAverage > mLastLastRollingAverage) {
                    isPeak = true;
                    mTimeArray.add(System.currentTimeMillis());
                    
                    // Keep the window at max 15 beats for median calculation
                    if (mTimeArray.size() > 15) {
                        mTimeArray.remove(0);
                    }
                    
                    if (mTimeArray.size() > 3) {
                        calcRealTimeBPM();
                    }
                }

                // Update Chart
                updateChart(numCaptures, mCurrentRollingAverage, isPeak ? mLastRollingAverage : -1);
            }

            numCaptures++;
            mLastLastRollingAverage = mLastRollingAverage;
            mLastRollingAverage = mCurrentRollingAverage;
        }
    };

    private void updateChart(int xValue, int yValue, int peakYValue) {
        mWaveDataSet.addEntry(new Entry(xValue, yValue));
        
        if (peakYValue != -1) {
            // Plot peak slightly in the past (xValue - 1) because the peak was the *last* frame
            mPeakDataSet.addEntry(new Entry(xValue - 1, peakYValue));
        }

        // Limit data size to avoid memory issues and ensure correct auto-scaling
        if (mWaveDataSet.getEntryCount() > 150) {
            mWaveDataSet.removeFirst();
        }
        
        // Remove old peaks that are out of the 150 frame window
        while (mPeakDataSet.getEntryCount() > 0 && mPeakDataSet.getEntryForIndex(0).getX() < xValue - 150) {
            mPeakDataSet.removeFirst();
        }

        LineData data = mChart.getData();
        data.notifyDataChanged();
        mChart.notifyDataSetChanged();
        
        // Scroll horizontally, keeping the last 150 frames visible
        mChart.setVisibleXRangeMaximum(150);
        mChart.moveViewToX(xValue - 150);
    }

    private void calcRealTimeBPM() {
        long[] timedist = new long[mTimeArray.size() - 1];
        for (int i = 0; i < mTimeArray.size() - 1; i++) {
            timedist[i] = mTimeArray.get(i + 1) - mTimeArray.get(i);
        }
        Arrays.sort(timedist);
        int med = (int) timedist[timedist.length / 2];
        
        if (med > 0) {
            hrtratebpm = 60000 / med;
            // Cap unrealistic values
            if (hrtratebpm >= 40 && hrtratebpm <= 200) {
                runOnUiThread(() -> tv.setText("Heart Rate: " + hrtratebpm + " BPM"));
            }
        }
    }

    private void saveHistory() {
        if (hrtratebpm >= 40 && hrtratebpm <= 200) {
            SharedPreferences prefs = getSharedPreferences("HeartRateHistory", MODE_PRIVATE);
            String existing = prefs.getString("history", "");
            
            String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
            String newEntry = date + " - " + hrtratebpm + " BPM";
            
            String updatedHistory;
            if (existing.isEmpty()) {
                updatedHistory = newEntry;
            } else {
                updatedHistory = newEntry + "\n" + existing;
            }
            
            prefs.edit().putString("history", updatedHistory).apply();
            Toast.makeText(this, "Measurement saved to history!", Toast.LENGTH_SHORT).show();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            if (cameraDevice != null) cameraDevice.close();
            cameraDevice = null;
        }
    };

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (null == cameraDevice) return;
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(CameraActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(CameraActivity.this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void updatePreview() {
        if (null == cameraDevice) return;
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(CameraActivity.this, "Camera permission is required.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        saveHistory();
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }
}
