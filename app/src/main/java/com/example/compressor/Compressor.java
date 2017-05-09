package com.example.compressor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Luban压缩库修改版
 */
public final class Compressor {
    public static final int FIRST_GEAR = 1;
    public static final int THIRD_GEAR = 3;
    private static final String DEFAULT_DISK_CACHE_DIR = "lu_ban_disk_cache";
    private volatile static Compressor instance;
    private File photoCompressCacheDir;
    private int gear = THIRD_GEAR;
    private long maxSizeUpperLimit = 1 * 1024 * 1024;
    private final Handler handler;
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);
    private final CopyOnWriteArrayList<File> pendingCompressFileList = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<File, CompressListener> map = new ConcurrentHashMap<>();

    private Compressor(File photoCompressCacheDir) {
        this.photoCompressCacheDir = photoCompressCacheDir;
        handler = new Handler(Looper.getMainLooper());
    }

    public static Compressor get(Context context) {
        Compressor tempInstance = instance;
        if (tempInstance == null) {
            synchronized (Compressor.class) {
                tempInstance = instance;
                if (tempInstance == null) {
                    tempInstance = instance = new Compressor(Compressor.getPhotoCacheDir(context));
                }
            }
        }
        return tempInstance;
    }

    public final static synchronized File getPhotoCacheDir(Context context) {
        return getPhotoCacheDir(context, DEFAULT_DISK_CACHE_DIR);
    }

    private final static File getPhotoCacheDir(Context context, String cacheName) {
        File cacheDir = context.getCacheDir();
        if (cacheDir == null) {
            return null;
        }
        File result = new File(cacheDir, cacheName);
        if (!result.mkdirs() && (!result.exists() || !result.isDirectory())) {
            return null;
        }
        File noMedia = new File(cacheDir + "/.nomedia");
        if (!noMedia.mkdirs() && (!noMedia.exists() || !noMedia.isDirectory())) {
            return null;
        }
        return result;
    }

    public Compressor load(Context context, Bitmap bitmap, CompressListener listener) {
        File file = saveBitmap(context, bitmap);
        return load(file, listener);
    }

    public Compressor load(String filePath, CompressListener listener) {
        return load(new File(filePath), listener);
    }

    public Compressor load(File file, CompressListener listener) {
        pendingCompressFileList.add(file);
        map.put(file, listener);
        return this;
    }

    public Compressor putGear(int gear) {
        this.gear = gear;
        return this;
    }

    public Compressor maxSize(long maxSize) {
        this.maxSizeUpperLimit = maxSize;
        return this;
    }

    private File saveBitmap(Context context, Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        FileOutputStream fos = null;
        File file = new File(Compressor.getPhotoCacheDir(context), System.currentTimeMillis() + "" +
                ".jpg");
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try {
                    fos.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return file;
    }

    public synchronized void launch() {
        while (pendingCompressFileList.size() > 0) {
            int listSize = pendingCompressFileList.size();
            final File pendingCompressFile = pendingCompressFileList.remove(listSize - 1);
            final CompressListener listener = map.get(pendingCompressFile);
            if (listener == null) {
                return;
            }
            if (pendingCompressFile == null || !pendingCompressFile.exists() ||
                    pendingCompressFile.isDirectory()) {
                listener.onFailed(new FileNotFoundException("file null or file is directory"));
                return;
            }
            listener.onStart();
            EXECUTOR_SERVICE.execute(new Runnable() {
                @Override
                public void run() {
                    File result = null;
                    if (gear == Compressor.FIRST_GEAR) {
                        result = firstCompress(pendingCompressFile);
                    } else if (gear == Compressor.THIRD_GEAR) {
                        result = thirdCompress(pendingCompressFile);
                    }
                    final Runnable call;
                    if (result == null || !result.exists()) {
                        call = new Runnable() {
                            @Override
                            public void run() {
                                listener.onFailed(new Exception("compress failed"));
                            }
                        };
                    } else {
                        final File file = result;
                        call = new Runnable() {
                            @Override
                            public void run() {
                                listener.onSuccess(file);
                            }
                        };
                    }
                    handler.post(call);
                }
            });
        }
    }

    private File firstCompress(@NonNull File file) {
        int minSize = 500;
        int longSide = 720;
        int shortSide = 1280;

        String filePath = file.getAbsolutePath();
        String thumbFilePath = photoCompressCacheDir.getAbsolutePath() + File.separator + System
                .currentTimeMillis() + ".jpg";
        long size = 0;
        long maxSize = Math.min(maxSizeUpperLimit, file.length() / 5);
        int angle = getImageSpinAngle(filePath);
        int[] imgSize = getImageSize(filePath);
        int width = 0, height = 0;
        if (imgSize[0] <= imgSize[1]) {
            double scale = (double) imgSize[0] / (double) imgSize[1];
            if (scale <= 1.0 && scale > 0.5625) {
                width = imgSize[0] > shortSide ? shortSide : imgSize[0];
                height = width * imgSize[1] / imgSize[0];
                size = minSize;
            } else if (scale <= 0.5625) {
                height = imgSize[1] > longSide ? longSide : imgSize[1];
                width = height * imgSize[0] / imgSize[1];
                size = maxSize;
            }
        } else {
            double scale = (double) imgSize[1] / (double) imgSize[0];
            if (scale <= 1.0 && scale > 0.5625) {
                height = imgSize[1] > shortSide ? shortSide : imgSize[1];
                width = height * imgSize[0] / imgSize[1];
                size = minSize;
            } else if (scale <= 0.5625) {
                width = imgSize[0] > longSide ? longSide : imgSize[0];
                height = width * imgSize[1] / imgSize[0];
                size = maxSize;
            }
        }
        return compress(filePath, thumbFilePath, width, height, angle, size);
    }

    private File thirdCompress(@NonNull File file) {
        String thumb = photoCompressCacheDir.getAbsolutePath() +
                System.currentTimeMillis() + ".jpg";
        double size;
        String filePath = file.getAbsolutePath();
        int angle = getImageSpinAngle(filePath);
        int[] imageSize = getImageSize(filePath);
        int width = imageSize[0];
        int height = imageSize[1];
        int thumbW = width % 2 == 1 ? width + 1 : width;
        int thumbH = height % 2 == 1 ? height + 1 : height;

        width = thumbW > thumbH ? thumbH : thumbW;
        height = thumbW > thumbH ? thumbW : thumbH;

        double scale = ((double) width / height);

        if (scale <= 1 && scale > 0.5625) {
            if (height < 1664) {
                if (file.length() / 1024 < 150) {
                    return file;
                }
                size = (width * height) / Math.pow(1664, 2) * 150;
                size = size < 60 ? 60 : size;
            } else if (height >= 1664 && height < 4990) {
                thumbW = width / 2;
                thumbH = height / 2;
                size = (thumbW * thumbH) / Math.pow(2495, 2) * 300;
                size = size < 60 ? 60 : size;
            } else if (height >= 4990 && height < 10240) {
                thumbW = width / 4;
                thumbH = height / 4;
                size = (thumbW * thumbH) / Math.pow(2560, 2) * 300;
                size = size < 100 ? 100 : size;
            } else {
                int multiple = height / 1280 == 0 ? 1 : height / 1280;
                thumbW = width / multiple;
                thumbH = height / multiple;
                size = (thumbW * thumbH) / Math.pow(2560, 2) * 300;
                size = size < 100 ? 100 : size;
            }
        } else if (scale <= 0.5625 && scale > 0.5) {
            if (height < 1280 && file.length() / 1024 < 200) {
                return file;
            }
            int multiple = height / 1280 == 0 ? 1 : height / 1280;
            thumbW = width / multiple;
            thumbH = height / multiple;
            size = (thumbW * thumbH) / (1440.0 * 2560.0) * 400;
            size = size < 100 ? 100 : size;
        } else {
            int multiple = (int) Math.ceil(height / (1280.0 / scale));
            thumbW = width / multiple;
            thumbH = height / multiple;
            size = ((thumbW * thumbH) / (1280.0 * (1280 / scale))) * 500;
            size = size < 100 ? 100 : size;
        }
        return compress(filePath, thumb, thumbW, thumbH, angle, (long) size);
    }

    /**
     * obtain the image's width and height
     * @param imagePath the path of image
     */
    public int[] getImageSize(String imagePath) {
        int[] res = new int[2];
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        options.inSampleSize = 1;
        BitmapFactory.decodeFile(imagePath, options);
        res[0] = options.outWidth;
        res[1] = options.outHeight;
        return res;
    }

    /**
     * obtain the thumbnail that specify the size
     * @param imagePath the target image path
     * @param width the width of thumbnail
     * @param height the height of thumbnail
     * @return {@link Bitmap}
     */
    private Bitmap compress(String imagePath, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imagePath, options);
        int outH = options.outHeight;
        int outW = options.outWidth;
        int inSampleSize = 1;
        if (outH > height || outW > width) {
            int halfH = outH / 2;
            int halfW = outW / 2;
            while ((halfH / inSampleSize) > height && (halfW / inSampleSize) > width) {
                inSampleSize *= 2;
            }
        }
        options.inSampleSize = inSampleSize;
        options.inJustDecodeBounds = false;
        int heightRatio = (int) Math.ceil(options.outHeight / (float) height);
        int widthRatio = (int) Math.ceil(options.outWidth / (float) width);
        if (heightRatio > 1 || widthRatio > 1) {
            if (heightRatio > widthRatio) {
                options.inSampleSize = heightRatio;
            } else {
                options.inSampleSize = widthRatio;
            }
        }
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(imagePath, options);
    }

    /**
     * obtain the image rotation angle
     * @param path path of target image
     */
    private int getImageSpinAngle(String path) {
        int degree = 0;
        try {
            ExifInterface exifInterface = new ExifInterface(path);
            int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    degree = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    degree = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    degree = 270;
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return degree;
    }

    /**
     * 指定参数压缩图片
     * create the thumbnail with the true rotate angle
     * @param largeImagePath the big image path
     * @param thumbFilePath the thumbnail path
     * @param width width of thumbnail
     * @param height height of thumbnail
     * @param angle rotation angle of thumbnail
     * @param size the file size of image
     */
    private File compress(String largeImagePath, String thumbFilePath, int width, int height, int
            angle, long size) {
        Bitmap thbBitmap = compress(largeImagePath, width, height);
        thbBitmap = rotatingImage(angle, thbBitmap);
        return saveImage(thumbFilePath, thbBitmap, size);
    }

    /**
     * 旋转图片
     * rotate the image with specified angle
     * @param angle the angle will be rotating 旋转的角度
     * @param bitmap target image               目标图片
     */
    private static Bitmap rotatingImage(int angle, Bitmap bitmap) {
        //rotate image
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        //create a new image
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix,
                true);
    }

    /**
     * 保存图片到指定路径
     * Save image with specified size
     * @param filePath the image file save path 储存路径
     * @param bitmap the image what be save   目标图片
     * @param size the file size of image   期望大小
     */
    private File saveImage(String filePath, Bitmap bitmap, long size) {
        File result = new File(filePath.substring(0, filePath.lastIndexOf("/")));
        if (!result.exists() && !result.mkdirs()) {
            return null;
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        int options = 100;
        bitmap.compress(Bitmap.CompressFormat.JPEG, options, stream);
        while (stream.toByteArray().length / 1024 > size && options > 6) {
            stream.reset();
            options -= 6;
            bitmap.compress(Bitmap.CompressFormat.JPEG, options, stream);
        }
        bitmap.recycle();
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(filePath);
            fos.write(stream.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try {
                    fos.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try {
                stream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return new File(filePath);
    }

    public static abstract class CompressListener {
        public void onStart() {
        }

        public void onFailed(Exception e) {
        }

        public abstract void onSuccess(File file);
    }
}
