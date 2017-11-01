package ru.biophotonics.msu.camera2scratch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2BasicFragment extends Fragment
        implements View.OnClickListener, ActivityCompat.OnRequestPermissionsResultCallback {


    //-------OBJECTS INITIALIZATION----------//
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 2;
    private static final String FRAGMENT_DIALOG = "dialog";
    private static final Integer CUSTOM_JPEG_ORIENTATION = 0 ;

    private File mImageFolder;
    private File mImageFile;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2BasicFragment";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * Does device support AutoFocus or not
     */
    private boolean mAutoFocusSupported = false;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);

        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * The {@link Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable"
     * will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageSaver(reader.acquireLatestImage(),mImageFile));
        }

    };

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;

    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */

    FrameLayout mAcceptControl;


    private CameraCaptureSession.CaptureCallback mCaptureCallback = null;
//            = new CameraCaptureSession.CaptureCallback() {
//
//        private void process(CaptureResult result) {
//            switch (mState) {
//                case STATE_PREVIEW: {
//                    // We have nothing to do when the camera preview is working normally.
//                    break;
//                }
//                case STATE_WAITING_LOCK: {
//
//                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
//
//                    if (afState == null) {
//                        mState = STATE_PICTURE_TAKEN;
//                        captureStillPicture();
//                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
//                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState ||
//                            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED == afState ||
//                            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED == afState) {
//                        // CONTROL_AE_STATE can be null on some devices
//
//                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
//
//                        if (aeState == null ||
//                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
//
//
//                            mState = STATE_PICTURE_TAKEN;
//                            captureStillPicture();
//                        } else {
//
//                            runPrecaptureSequence();
//                        }
//                    }
//                    break;
//                }
//                case STATE_WAITING_PRECAPTURE: {
//
//                    showToast("process: state waiting precapture");
//                    // CONTROL_AE_STATE can be null on some devices
//                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
//                    if (aeState == null ||
//                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
//                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
//                        mState = STATE_WAITING_NON_PRECAPTURE;
//                    }
//                    break;
//                }
//                case STATE_WAITING_NON_PRECAPTURE: {
//                    showToast("state waiting non precapture");
//                    // CONTROL_AE_STATE can be null on some devices
//                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
//                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
//                        mState = STATE_PICTURE_TAKEN;
//                        captureStillPicture();
//                    }
//                    break;
//                }
//            }
//        }
//
//        @Override
//        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
//                                        @NonNull CaptureRequest request,
//                                        @NonNull CaptureResult partialResult) {
//            process(partialResult);
//        }
//
//        @Override
//        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
//                                       @NonNull CaptureRequest request,
//                                       @NonNull TotalCaptureResult result) {
//
//            process(result);
//        }
//
//    };
    private CameraCharacteristics mCameraInfo;
    private boolean mManualFocusEngaged = false;
    private ImageView mAngleLeftBot;
    private ImageView mAngleLeftTop;
    private ImageView mAngleRightTop;
    private ImageView mAngleRigthBot;
    private boolean mAntibandingModeSupported = false;


    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */


    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
            int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {
        Log.d(TAG,"chooseOptimalSize::textureViewSize: WxH"+textureViewWidth+"x"+textureViewHeight);
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();

        //TODO: delete if it isn't working

        List<Size> optimal = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();


        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                    option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }

            Log.d(TAG,"chooseOptimalSize::option" + option.toString());
            if(option.getWidth() == textureViewWidth &&
                    option.getHeight() == textureViewHeight){
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public static Camera2BasicFragment newInstance() {
        return new Camera2BasicFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        createImageFolder();
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
        view.findViewById(R.id.info).setOnClickListener(this);
        view.findViewById(R.id.accept_button).setOnClickListener(this);
        view.findViewById(R.id.decline_button).setOnClickListener(this);

        initFrame(view);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);

        mAcceptControl = (FrameLayout) view.findViewById(R.id.control_accept);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {

        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        switch(requestCode){
            case REQUEST_CAMERA_PERMISSION:{
                if (requestCode == REQUEST_CAMERA_PERMISSION) {
                    if (grantResults.length != 1 || grantResults[0]
                            != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.request_permission))
                                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                    }
                } else{
                    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
                }
            }
            case REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT:{
                if(requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT){
                    if (grantResults.length != 1 || grantResults[0]
                            != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance("Необходим доступ к галерее")
                                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                    }
                }
            }
        }
    }


    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    @SuppressWarnings("SuspiciousNameCombination")
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                int[] afAvailableModes = characteristics.get(
                        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

                if (afAvailableModes.length == 0 || (afAvailableModes.length == 1
                        && afAvailableModes[0] == CameraMetadata.CONTROL_AF_MODE_OFF)) {
                    mAutoFocusSupported = false;
                } else {
                    mAutoFocusSupported = true;
                }


                //checking for antibanding modes
                int [] aeAntibandingModes = characteristics.get(
                        CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES);

                for(int antibandingMode: aeAntibandingModes){
                    Log.d(TAG,"setUpCameraOutputs::aeAntibandingModes:"+antibandingMode);
                }

                if (aeAntibandingModes.length == 0 || (aeAntibandingModes.length == 1
                        && aeAntibandingModes[0] == CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_OFF)) {
                    Log.d(TAG,"setUpCameraOutputs::antibanding mode is not supported");
                    mAntibandingModeSupported = false;
                } else {
                    Log.d(TAG,"setUpCameraOutputs::antibanding mode is supported");
                    mAntibandingModeSupported = true;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBackgroundHandler);

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                Log.d(TAG,"setUpCameraOutputs::Sensor Orientation:"+mSensorOrientation);
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }
                Log.d(TAG,"setUpCameraOutputs::swapped dimensions:" + swappedDimensions);

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);


                // We fit the aspect ratio of TextureView to the size of preview we picked.
//                int orientation = getResources().getConfiguration().orientation;
//                if (orientation != Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(
                        mPreviewSize.getHeight(), mPreviewSize.getWidth());


                drawFrame();
//
//                } else {
//                            mTextureView.setAspectRatio(
//                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
//                }

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                
                mCameraInfo = characteristics;
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    /**
     * Opens the camera specified by {@link Camera2BasicFragment#mCameraId}.
     */
    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }

        if (ContextCompat.checkSelfPermission(getActivity(),Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED){
            checkWriteStoragePermission();
            return;
        }

        setUpCameraOutputs(width, height);
        configureTransform(width, height);

        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }

       mTextureView.setOnTouchListener(new View.OnTouchListener() {

           @Override
           public boolean onTouch(View view, MotionEvent motionEvent) {

               Log.d(TAG,"onTouch");
               final int actionMasked = motionEvent.getActionMasked();
               if (actionMasked != MotionEvent.ACTION_DOWN) {
                   return false;
               }
               if (mManualFocusEngaged) {
                   Log.d(TAG, "Manual focus already engaged");
                   return true;
               }

               final Rect sensorArraySize = mCameraInfo.get(
                       CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

               //TODO: here I just flip x,y, but this needs
               // to correspond with the sensor orientation
               // (via SENSOR_ORIENTATION)
               final int y = (int)((motionEvent.getX() / (float)view.getWidth())  * (float)sensorArraySize.height());
               final int x = (int)((motionEvent.getY() / (float)view.getHeight()) * (float)sensorArraySize.width());

               final int halfTouchWidth  = 150; //(int)motionEvent.getTouchMajor();
               // TODO: this doesn't represent actual touch size in pixel. Values range in [3, 10]...
               final int halfTouchHeight = 150; //(int)motionEvent.getTouchMinor();
               MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(x - halfTouchWidth,  0),
                       Math.max(y - halfTouchHeight, 0),
                       halfTouchWidth  * 2,
                       halfTouchHeight * 2,
                       MeteringRectangle.METERING_WEIGHT_MAX - 1);

               CameraCaptureSession.CaptureCallback captureCallbackHandler = new CameraCaptureSession.CaptureCallback() {
                   @Override
                   public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                       super.onCaptureCompleted(session, request, result);
                       mManualFocusEngaged = false;

                       if (request.getTag() == "FOCUS_TAG") {
                           //the focus trigger is complete -
                           //resume repeating (preview surface will get frames), clear AF trigger
                           mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null);
                           setAntibandingMode(mPreviewRequestBuilder);
                           try {
                               mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                                       mCaptureCallback, mBackgroundHandler);
                           } catch (CameraAccessException e) {
                               e.printStackTrace();
                           }
                       }
                   }

                   @Override
                   public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                       super.onCaptureFailed(session, request, failure);
                       Log.e(TAG, "Manual AF failure: " + failure);
                       mManualFocusEngaged = false;
                   }
               };

               //first stop the existing repeating request
               try {
                   mCaptureSession.stopRepeating();
               } catch (CameraAccessException e) {
                   e.printStackTrace();
               }

               //cancel any existing AF trigger (repeated touches, etc.)
               mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
               mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);

               setAntibandingMode(mPreviewRequestBuilder);
               try {
                   mCaptureSession.capture(mPreviewRequestBuilder.build(),
                           captureCallbackHandler, mBackgroundHandler);
               } catch (CameraAccessException e) {
                   e.printStackTrace();
               }

               //Now add a new AF trigger with focus region
               if (isMeteringAreaAFSupported()) {
                   mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusAreaTouch});
               }
               mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
               mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
               mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
               setAntibandingMode(mPreviewRequestBuilder);
               mPreviewRequestBuilder.setTag("FOCUS_TAG"); //we'll capture this later for resuming the preview

               //then we ask for a single request (not repeating!)
               try {
                   mCaptureSession.capture(mPreviewRequestBuilder.build(),
                           captureCallbackHandler, mBackgroundHandler);
               } catch (CameraAccessException e) {
                   e.printStackTrace();
               }
               mManualFocusEngaged = true;

               return true;
           }

           private boolean isMeteringAreaAFSupported() {
               return mCameraInfo.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) >= 1;
           }
       });
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);



            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {



                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                setFlashOff(mPreviewRequestBuilder);

                                setAntibandingMode(mPreviewRequestBuilder);
                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setAntibandingMode(CaptureRequest.Builder mPreviewRequestBuilder) {

        if(mAntibandingModeSupported){
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ);
        } // Do nothing if it's not supported
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);

    }

    /**
     * Initiate a still image capture.
     */
    private void takePicture() {

        if(!mManualFocusEngaged){
            if(mAutoFocusSupported){
                //lockFocus();
                captureStillPicture();
                //TODO: DELETE
                showToast("AutoFocus supported");
            }
        }
        else{
            showToast("Capturing");
            captureStillPicture();
        }
    }

    private void createImageFolder() {
        File imageFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mImageFolder = new File(imageFile, "camera2scratch");
        if(!mImageFolder.exists()) {
            mImageFolder.mkdirs();
        }
    }

    private File createImageFile() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "U_" + timestamp;
        File imageFile = new File(mImageFolder,prepend+".jpg" );
        mImageFile = imageFile;
        Log.d(TAG,"createImageFile:: ImagePath"+mImageFile.getAbsolutePath());
        return imageFile;
    }


    private void checkWriteStoragePermission() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if(ContextCompat.checkSelfPermission(getActivity(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
            } else {
                if(shouldShowRequestPermissionRationale(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(getActivity(), "app needs to be able to save videos", Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT);
            }
        }
    }

    private void initFrame(View view){
        mAngleLeftBot = (ImageView) view.findViewById(R.id.lb_angle);
        mAngleLeftTop = (ImageView) view.findViewById(R.id.lt_angle);
        mAngleRightTop = (ImageView) view.findViewById(R.id.rt_angle);
        mAngleRigthBot = (ImageView) view.findViewById(R.id.rb_angle);
    }

    private void drawFrame(){


        //x is width
        //y is height

        // 3/4 for the biggest dimension (width here)

        int fullWidth = mPreviewSize.getWidth();
        int fullHeight = mPreviewSize.getHeight();

        float hotAreaWidth = 3*fullWidth/4;
        float hotAreaHeight = (float) (fullHeight/2.05); //5.4 / 2.6337 = 2.05

        int image_width = mAngleLeftTop.getWidth();
        int image_height = mAngleLeftTop.getHeight();

        float left = mTextureView.getY() + (fullHeight - hotAreaHeight)/2 ;
        float right = left + hotAreaHeight - image_height;

        float top = mTextureView.getX() + (fullWidth - hotAreaWidth)/2;
        float bot = top + hotAreaWidth - image_width;


        Log.d(TAG,"DrawFrame:: coordinates: bot: " + bot
                +",  top "+ top+", right "+ right+", left "+ left);


        Log.d(TAG,"DrawFrame::image_width"+image_width);
        mAngleLeftTop.setX(left);
        mAngleLeftTop.setY(top);

        mAngleLeftBot.setX(left);
        mAngleLeftBot.setY(bot);
//
        mAngleRightTop.setX(right);
        mAngleRightTop.setY(top);

        mAngleRigthBot.setX(right);
        mAngleRigthBot.setY(bot);

    }
    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            setAntibandingMode(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    private void captureStillPicture() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }



            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setFlashOff(captureBuilder);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();

            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, CUSTOM_JPEG_ORIENTATION);
                    //Configuration.ORIENTATION_LANDSCAPE);
                    //getOrientation(rotation));

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);

                    try {
                        createImageFile();
                    } catch (IOException e) {
                        e.printStackTrace();
                        try {
                            session.abortCaptures();
                        } catch (CameraAccessException e1) {
                            e1.printStackTrace();
                        }
                    }
//                    Log.d(TAG,"onCaptureStarted::capture started");
//                    try {
//                        createImageFile();
//                        Log.d(TAG,"onCaptureStarted::mImagePath" + mImagePath);
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    try {
                        session.stopRepeating();
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    showToast("Saved: " + mImageFile);
                    Log.d(TAG,"Image saved:"+ mImageFile);

                    //unlockFocus(); //this returns camera to normal state.
                }
            };


            mCaptureSession.stopRepeating();
            mCaptureSession.abortCaptures();
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
//    private int getOrientation(int rotation) {
//        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
//        // We have to take that into account and rotate JPEG properly.
//        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
//        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
//        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
//    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     * AutoFocus trigger
     *
     * set
     */
    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setFlashOff(mPreviewRequestBuilder);
            setAntibandingMode(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                takePicture();
                mAcceptControl.setVisibility(View.VISIBLE);
                break;

            }
            case R.id.info: {
                Activity activity = getActivity();
                if (null != activity) {
                    new AlertDialog.Builder(activity)
                            .setMessage(R.string.intro_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                break;
            }

            case R.id.accept_button:{
                Activity activity = getActivity();

                Intent mediaStoreUpdateIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaStoreUpdateIntent.setData(Uri.fromFile(mImageFile));
                getActivity().sendBroadcast(mediaStoreUpdateIntent);

                if (null != activity){
                    Intent intent = new Intent(activity, ImageActivity.class);
                    intent.putExtra("ImagePath", mImageFile.getAbsolutePath());
                    startActivity(intent);
                    activity.finish();
                }
                break;
            }

            case R.id.decline_button:{

                mImageFile.delete();
                unlockFocus();
                mAcceptControl.setVisibility(View.GONE);
                break;
            }

        }
    }

    private void setFlashOff(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.FLASH_MODE_OFF);
        }
    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private class ImageSaver implements Runnable {

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */

        private final File mFile;

        ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                Log.d(TAG,"ImageSaver:: image path"+mFile.getAbsolutePath());
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();

                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();


            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    /**
     * Shows OK/Cancel confirmation dialog about camera permission.
     */
    public static class ConfirmationDialog extends DialogFragment {

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            parent.requestPermissions(new String[]{Manifest.permission.CAMERA},
                                    REQUEST_CAMERA_PERMISSION);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Activity activity = parent.getActivity();
                                    if (activity != null) {
                                        activity.finish();
                                    }
                                }
                            })
                    .create();
        }
    }

}
