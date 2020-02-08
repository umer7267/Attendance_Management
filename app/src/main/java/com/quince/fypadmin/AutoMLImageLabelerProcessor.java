package com.quince.fypadmin;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.ml.common.FirebaseMLException;
import com.google.firebase.ml.common.modeldownload.FirebaseModelDownloadConditions;
import com.google.firebase.ml.common.modeldownload.FirebaseModelManager;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.automl.FirebaseAutoMLLocalModel;
import com.google.firebase.ml.vision.automl.FirebaseAutoMLRemoteModel;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.label.FirebaseVisionImageLabel;
import com.google.firebase.ml.vision.label.FirebaseVisionImageLabeler;
import com.google.firebase.ml.vision.label.FirebaseVisionOnDeviceAutoMLImageLabelerOptions;
import com.quince.fypadmin.common.CameraImageGraphic;
import com.quince.fypadmin.common.FrameMetadata;
import com.quince.fypadmin.common.GraphicOverlay;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class AutoMLImageLabelerProcessor extends VisionProcessorBase<List<FirebaseVisionImageLabel>> {
    private static final String TAG = "AutoMLImageLabelerProce";

    private final Context context;
    FirebaseVisionOnDeviceAutoMLImageLabelerOptions.Builder optionsBuilder;
    private FirebaseVisionImageLabeler detector;
    private Task<Void> modelDownloadingTask = null;
    private final Mode mode;

    /**
     * The detection mode of the processor. Different modes will have different behavior on whether or
     * not waiting for the model download complete.
     */
    public enum Mode {
        STILL_IMAGE,
        LIVE_PREVIEW
    }

    public AutoMLImageLabelerProcessor(Context context, Mode mode) throws FirebaseMLException {
        this.context = context;
        this.mode = mode;

        Log.d(TAG, "AutoMLImageLabelerProcessor: ");

        FirebaseAutoMLRemoteModel remoteModel =
                new FirebaseAutoMLRemoteModel.Builder("Students_testing").build();

        FirebaseModelDownloadConditions conditions = new FirebaseModelDownloadConditions.Builder()
                .requireWifi()
                .build();

        modelDownloadingTask = FirebaseModelManager.getInstance().download(remoteModel, conditions);

        FirebaseAutoMLLocalModel localModel = new FirebaseAutoMLLocalModel.Builder()
                .setAssetFilePath("automl/manifest.json")
                .build();

        optionsBuilder = new FirebaseVisionOnDeviceAutoMLImageLabelerOptions.Builder(localModel);

        FirebaseVisionOnDeviceAutoMLImageLabelerOptions options = optionsBuilder
                .setConfidenceThreshold(0.50f)   // Evaluate your model in the Firebase console
                // to determine an appropriate threshold.
                .build();

        detector = FirebaseVision.getInstance().getOnDeviceAutoMLImageLabeler(options);

        Log.d(TAG, "AutoMLImageLabelerProcessor: end");

//        FirebaseModelManager.getInstance().isModelDownloaded(remoteModel)
//                .addOnSuccessListener(new OnSuccessListener<Boolean>() {
//                    @Override
//                    public void onSuccess(Boolean isDownloaded) {
//                        if (isDownloaded) {
//                            Log.d(TAG, "onSuccess: downloaded");
//                            optionsBuilder = new FirebaseVisionOnDeviceAutoMLImageLabelerOptions.Builder(remoteModel);
//                        } else {
//                            Log.d(TAG, "onSuccess: not downloaded");
//                            optionsBuilder = new FirebaseVisionOnDeviceAutoMLImageLabelerOptions.Builder(localModel);
//                        }
//
//                        FirebaseVisionOnDeviceAutoMLImageLabelerOptions options = optionsBuilder
//                                .setConfidenceThreshold(0.0f)   // Evaluate your model in the Firebase console
//                                                                // to determine an appropriate threshold.
//                                .build();
//
//                        try {
//                            detector = FirebaseVision.getInstance().getOnDeviceAutoMLImageLabeler(options);
//                            Log.d(TAG, "onSuccess: assigning detector");
//                        } catch (FirebaseMLException e) {
//                            e.printStackTrace();
//                        }
//
//                    }
//                });
    }

    @Override
    public void stop() {
        try {
            detector.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception thrown while trying to close the image labeler", e);
        }
    }

    @Override
    protected Task<List<FirebaseVisionImageLabel>> detectInImage(FirebaseVisionImage image) {
        if (modelDownloadingTask == null) {
            Log.d(TAG, "detectInImage: model downloading task is null");
            // No download task means only the locally bundled model is used. Model can be used directly.
            return detector.processImage(image);
        } else if (!modelDownloadingTask.isComplete()){
            if (mode == Mode.LIVE_PREVIEW) {
                Log.i(TAG, "Model download is in progress. Skip detecting image.");
                return Tasks.forResult(Collections.<FirebaseVisionImageLabel>emptyList());
            } else {
                Log.i(TAG, "Model download is in progress. Waiting...");
                return modelDownloadingTask.continueWithTask(new Continuation<Void, Task<List<FirebaseVisionImageLabel>>>() {
                    @Override
                    public Task<List<FirebaseVisionImageLabel>> then(@NonNull Task<Void> task) {
                        return processImageOnDownloadComplete(image);
                    }
                });
            }
        } else {
            Log.d(TAG, "detectInImage: model downloading task: " + modelDownloadingTask);
            return processImageOnDownloadComplete(image);
        }
    }

    @Override
    protected void onSuccess(@Nullable Bitmap originalCameraImage,
                             @NonNull List<FirebaseVisionImageLabel> labels,
                             @NonNull FrameMetadata frameMetadata,
                             @NonNull GraphicOverlay graphicOverlay) {
        graphicOverlay.clear();
        if (originalCameraImage != null) {
            CameraImageGraphic imageGraphic = new CameraImageGraphic(graphicOverlay, originalCameraImage);
            graphicOverlay.add(imageGraphic);
        }

        for (FirebaseVisionImageLabel label : labels) {
            String text = label.getText();
            float confidence = label.getConfidence();
            Log.d(TAG, "onSuccess: Text: " + text + " confidence: " + confidence);
        }

        LabelGraphic labelGraphic = new LabelGraphic(graphicOverlay, labels);
        graphicOverlay.add(labelGraphic);
        graphicOverlay.postInvalidate();
    }

    @Override
    protected void onFailure(@NonNull Exception e) {
        Log.w(TAG, "Label detection failed.", e);
    }

    private Task<List<FirebaseVisionImageLabel>> processImageOnDownloadComplete(
            FirebaseVisionImage image) {
        if (modelDownloadingTask.isSuccessful()) {
            Log.d(TAG, "processImageOnDownloadComplete: model downloaded successfully");
            return detector.processImage(image);
        } else {
            String downloadingError = "Error downloading remote model.";
            Log.e(TAG, downloadingError, modelDownloadingTask.getException());
            Toast.makeText(context, downloadingError, Toast.LENGTH_SHORT).show();
            return Tasks.forException(new Exception("Failed to download remote model.", modelDownloadingTask.getException()));
        }
    }
}
