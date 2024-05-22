package com.alexmercerind.media_kit_video;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.android.FlutterFragmentActivity;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.view.TextureRegistry;

public class VideoOutput {
    public long id = 0;
    public long wid = 0;

    private Surface surface;
    private final TextureRegistry.SurfaceProducer surfaceProducer;

    private boolean flutterJNIAPIAvailable;
    private final Method newGlobalObjectRef;
    private final Method deleteGlobalObjectRef;
    private boolean waitUntilFirstFrameRenderedNotify;

    private long handle;
    private MethodChannel channelReference;
    private TextureRegistry textureRegistryReference;

    private final Object lock = new Object();
    private Choreographer.FrameCallback frameCallback;

    VideoOutput(long handle, MethodChannel channelReference, TextureRegistry textureRegistryReference) {
        this.handle = handle;
        this.channelReference = channelReference;
        this.textureRegistryReference = textureRegistryReference;
        try {
            flutterJNIAPIAvailable = false;
            waitUntilFirstFrameRenderedNotify = false;
            // com.alexmercerind.mediakitandroidhelper.MediaKitAndroidHelper is part of package:media_kit_libs_android_video & package:media_kit_libs_android_audio packages.
            // Use reflection to invoke methods of com.alexmercerind.mediakitandroidhelper.MediaKitAndroidHelper.
            Class<?> mediaKitAndroidHelperClass = Class.forName("com.alexmercerind.mediakitandroidhelper.MediaKitAndroidHelper");
            newGlobalObjectRef = mediaKitAndroidHelperClass.getDeclaredMethod("newGlobalObjectRef", Object.class);
            deleteGlobalObjectRef = mediaKitAndroidHelperClass.getDeclaredMethod("deleteGlobalObjectRef", long.class);
            newGlobalObjectRef.setAccessible(true);
            deleteGlobalObjectRef.setAccessible(true);
        } catch (Throwable e) {
            Log.i("media_kit", "package:media_kit_libs_android_video missing. Make sure you have added it to pubspec.yaml.");
            throw new RuntimeException("Failed to initialize com.alexmercerind.media_kit_video.VideoOutput.", e);
        }

        surfaceProducer = textureRegistryReference.createSurfaceProducer();
        // Initialize Choreographer FrameCallback for frame updates
        frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                synchronized (lock) {
                    try {
                        if (!waitUntilFirstFrameRenderedNotify) {
                            waitUntilFirstFrameRenderedNotify = true;
                            final HashMap<String, Object> data = new HashMap<>();
                            data.put("handle", handle);
                            channelReference.invokeMethod("VideoOutput.WaitUntilFirstFrameRenderedNotify", data);
                            Log.i("media_kit", String.format(Locale.ENGLISH, "VideoOutput.WaitUntilFirstFrameRenderedNotify = %d", handle));
                        }
                        FlutterJNI flutterJNI = null;
                        while (flutterJNI == null) {
                            flutterJNI = getFlutterJNIReference();
                            flutterJNI.markTextureFrameAvailable(id);
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }
                Choreographer.getInstance().postFrameCallback(this); // Post callback again for the next frame
            }
        };

        try {
            if (!flutterJNIAPIAvailable) {
                flutterJNIAPIAvailable = getFlutterJNIReference() != null;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        Log.i("media_kit", String.format(Locale.ENGLISH, "flutterJNIAPIAvailable = %b", flutterJNIAPIAvailable));
        if (flutterJNIAPIAvailable) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            if (!waitUntilFirstFrameRenderedNotify) {
                waitUntilFirstFrameRenderedNotify = true;
                final HashMap<String, Object> data = new HashMap<>();
                data.put("id", id);
                data.put("wid", wid);
                data.put("handle", handle);
                channelReference.invokeMethod("VideoOutput.WaitUntilFirstFrameRenderedNotify", data);
            }
        }

        try {
            id = surfaceProducer.id();
            Log.i("media_kit", String.format(Locale.ENGLISH, "com.alexmercerind.media_kit_video.VideoOutput: id = %d", id));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public void dispose() {
        synchronized (lock) {
            try {
                surfaceProducer.release();
            } catch (Throwable e) {
                e.printStackTrace();
            }
            try {
                if (surface != null) {
                    surface.release();
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
            try {
                final Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.postDelayed(() -> {
                    try {
                        // Invoke DeleteGlobalRef after a voluntary delay to eliminate possibility of libmpv referencing it sometime in the near future.
                        deleteGlobalObjectRef.invoke(null, wid);
                        Log.i("media_kit", String.format(Locale.ENGLISH, "com.alexmercerind.mediakitandroidhelper.MediaKitAndroidHelper.deleteGlobalObjectRef: %d", wid));
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }, 5000);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            // Remove Choreographer callback
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
    }

    public long createSurface() {
        synchronized (lock) {
            // Delete previous android.view.Surface & object reference.
            try {
                if (surface != null) {
                    clearSurface();
                    surface.release();
                    surface = null;
                }
                if (wid != 0) {
                    deleteGlobalObjectRef.invoke(null, wid);
                    wid = 0;
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
            // Create new android.view.Surface & object reference.
            try {
                surface = surfaceProducer.getSurface();
                wid = (long) newGlobalObjectRef.invoke(null, surface);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return wid;
        }
    }

    public void setSurfaceTextureSize(int width, int height) {
        try {
            surfaceProducer.setSize(width, height);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void clearSurface() {
        synchronized (lock) {
            if (surface == null || !surface.isValid()) {
                Log.w("media_kit", "Attempt to clear an invalid or null Surface.");
                return;
            }
            try {
                final Canvas canvas = surface.lockCanvas(null);
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                surface.unlockCanvasAndPost(canvas);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private FlutterJNI getFlutterJNIReference() {
        try {
            FlutterView view = null;
            // io.flutter.embedding.android.FlutterActivity
            if (view == null) {
                view = MediaKitVideoPlugin.activity.findViewById(FlutterActivity.FLUTTER_VIEW_ID);
            }
            // io.flutter.embedding.android.FlutterFragmentActivity
            if (view == null) {
                final FrameLayout layout = (FrameLayout) MediaKitVideoPlugin.activity.findViewById(FlutterFragmentActivity.FRAGMENT_CONTAINER_ID);
                for (int i = 0; i < layout.getChildCount(); i++) {
                    final View child = layout.getChildAt(i);
                    if (child instanceof FlutterView) {
                        view = (FlutterView) child;
                        break;
                    }
                }
            }
            if (view == null) {
                Log.w("media_kit", "FlutterView not found.");
                return null;
            }
            final FlutterEngine engine = view.getAttachedFlutterEngine();
            final Field field = engine.getClass().getDeclaredField("flutterJNI");
            field.setAccessible(true);
            return (FlutterJNI) field.get(engine);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }
}
