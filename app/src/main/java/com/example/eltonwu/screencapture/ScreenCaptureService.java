package com.example.eltonwu.screencapture;

import android.app.Activity;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";

    private int dpi;

    private VirtualDisplay mVirtualDisplay;
    private MediaProjection mMediaProjection;
    private FloatingWindow  mFloatingWindow;

    private static final int CAPTURE_WIDTH  = 540;
    private static final int CAPTURE_HEIGHT = 960;

    private HandlerThread ImageHandlerThread;
    private Handler       mImageHandler;
    private Surface       mScreenSurface;
    private ImageReader   mImageReader;

    private byte[]        mData = new byte[CAPTURE_WIDTH * CAPTURE_HEIGHT * 4];
    private byte[]        mPaddingData;

    private TransferThread mTransferThread;
    private final Object mClientLock = new Object();

    private Socket mClient;

    private static final int MSG_CLIENT_IN    = 100;
    private static final int MSG_CLIENT_OUT   = 101;
    private static final int MSG_CLIENT_READY = 102;
    private static final int MSG_CLIENT_WAIT  = 103;
    private static final int MSG_CLIENT_NEXT  = 104;
    private static final int STATE_PRAMA  = 0;
    private static final int STATE_STREAM = 1;
    private static final int STATE_WAIT   = 2;

    public int mState = 0;

    private Handler.Callback mImageCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what){
                case MSG_CLIENT_IN:{
                    Log.i(TAG,"client in");
                    mState = STATE_PRAMA;
                    mClient = (Socket) msg.obj;
                    OutputStream os;
                    String param = String.valueOf(CAPTURE_WIDTH)+"x"+CAPTURE_HEIGHT;
                    try {
                        os = mClient.getOutputStream();
                        PrintWriter pw = new PrintWriter(os,true);
                        pw.println(param);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                break;
                case MSG_CLIENT_OUT:{
                    Log.i(TAG,"client out");
                    mClient = null;
                }
                break;
                case MSG_CLIENT_READY:{
                    Log.i(TAG,"client ready");
                    mState = STATE_STREAM;
                }
                break;
                case MSG_CLIENT_NEXT:{
                    Log.i(TAG,"client next");
                    mState = STATE_STREAM;
                }
                break;


            }
            return true;
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("SCRCAT","service is created");
        startForeground(233,new Notification());
        dpi = getResources().getConfiguration().densityDpi;
//        mFloatingWindow = FloatingWindow.getInstacne(getApplicationContext());
//        mFloatingWindow.createWindows();
        ImageHandlerThread = new HandlerThread("Image Handler");
        ImageHandlerThread.start();
        mImageHandler = new Handler(ImageHandlerThread.getLooper(),mImageCallback);
        mScreenSurface = createSurface();

        mTransferThread = new TransferThread();
        mTransferThread.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i("SCRCAT","service is killed");
        if(mVirtualDisplay != null){
            mVirtualDisplay.release();
        }

        if(mMediaProjection != null){
            mMediaProjection.stop();
        }
        ImageHandlerThread.quit();
        mImageReader.close();

        mPaddingData = null;

        try {
            mTransferThread.running = false;
            mTransferThread.interrupt();
            mTransferThread.mServerSocket.close();
            mTransferThread.join();
        } catch (IOException e) {
            Log.w(TAG,"close server failed :"+e.getMessage());
        } catch (InterruptedException e) {
            Log.w(TAG,"waiting server close interrupted :"+e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null){
            String cmd = intent.getStringExtra("cmd");
            if(cmd != null && cmd.equals("start")){
                MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                if (mediaProjectionManager != null) {
                    Log.i(TAG,"create virtual display");
                    mMediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK,intent);
                    mVirtualDisplay = mMediaProjection.createVirtualDisplay("Screen Capture",
                            CAPTURE_WIDTH,CAPTURE_HEIGHT, dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            mScreenSurface/*window surface*/,
                            mCallback,
                            null);
                    synchronized (TAG){
                        TAG.notifyAll();
                    }
                }
            }
        }

        return START_REDELIVER_INTENT;
    }

    private Surface createSurface(){
        mImageReader = ImageReader.newInstance(CAPTURE_WIDTH,CAPTURE_HEIGHT, PixelFormat.RGBA_8888,2);
        mImageReader.setOnImageAvailableListener(mImageAvailableListener,mImageHandler);
        return mImageReader.getSurface();
    }

    private ImageReader.OnImageAvailableListener mImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        private BufferedOutputStream mBufferedOutputStream;
        private PrintWriter          mPrintWriter;

        private void transfer(Bitmap rgba){
            if(mClient != null){
                if(mState == STATE_STREAM){
                    if(mBufferedOutputStream == null){
                        OutputStream os = null;
                        try {
                            os = mClient.getOutputStream();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        mBufferedOutputStream = new BufferedOutputStream(os);
                    }
                    if(mPrintWriter == null){
                        OutputStream os = null;
                        try {
                            os = mClient.getOutputStream();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        mPrintWriter = new PrintWriter(os);
                    }
                    OutputStream os = null;
                    try {
                        os = mClient.getOutputStream();
                        rgba.compress(Bitmap.CompressFormat.JPEG,80,os);
                        mPrintWriter.print('A');
                        mPrintWriter.print('B');
                        mPrintWriter.print('C');
                        mPrintWriter.print('D');
                        mPrintWriter.print('E');
                        mPrintWriter.print('F');
                        mPrintWriter.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    mState = STATE_WAIT;
                }
            }else{
                mBufferedOutputStream = null;
                mPrintWriter          = null;
            }
        }

//        private void transfer(byte[] rgba){
//            if(mClient != null){
//                OutputStream os = null;
//                try {
//                    os = mClient.getOutputStream();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//                if(mBufferedOutputStream == null){
//                    mBufferedOutputStream = new BufferedOutputStream(os);
//                }
//                if(mState == STATE_STREAM){
//                    try {
//                        mBufferedOutputStream.write(rgba);
//                        mBufferedOutputStream.flush();
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }
//            }else{
//                mBufferedOutputStream = null;
//            }
//        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            try{
                Image image = reader.acquireLatestImage();
                if(image != null){
                    Image.Plane[] rgb = image.getPlanes();
                    if(rgb.length == 1){
                        int width = image.getWidth();
                        int height= image.getHeight();

                        if(mPaddingData == null){
                            mPaddingData = new byte[rgb[0].getRowStride() * height];
                        }

//                        SurfaceHolder holder = mFloatingWindow.getSurfaceHolder();
//                        long now = System.currentTimeMillis();
                        removeBitmapPadding(image,mPaddingData,mData);
//                        long delta = System.currentTimeMillis() - now;
//                        Log.i(TAG,"encode use:"+delta);
                        Bitmap bitmap = Bitmap.createBitmap(width,height, Bitmap.Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(mData));
//                        if(holder != null){
//                            Canvas canvas = holder.lockCanvas();
//                            if(canvas != null){
//                                canvas.drawBitmap(bitmap,0,0,null);
//                            }else{
//                                try {
//                                    Thread.sleep(200);
//                                } catch (InterruptedException ignored) {}
//                            }
//                            if(canvas != null){
//                                holder.unlockCanvasAndPost(canvas);
//                            }
//                        }
                        transfer(bitmap);
                        bitmap.recycle();
                    }else{
                        Log.w(TAG,"not rgb format");
                    }

                    image.close();
                }
            }catch (IllegalStateException e){
                Log.w(TAG,"no more images");
            }

        }
    };

    private void removeBitmapPadding(Image image,byte[] working,byte[] pixelData){
        Image.Plane[] planes = image.getPlanes();
        int width = image.getWidth();//设置的宽
        int height = image.getHeight();//设置的高
        int pixelStride = planes[0].getPixelStride();//像素个数，RGBA为4
        int rowStride = planes[0].getRowStride();//这里除pixelStride就是真实宽度
        int rowPadding = rowStride - pixelStride * width;//计算多余宽度

        byte[] data = working;//创建byte
        ByteBuffer buffer = planes[0].getBuffer();//获得buffer
        buffer.get(data);//将buffer数据写入byte中

        int offset = 0;
        int index = 0;
        for (int i = 0; i < height; ++i) {
            for (int j = 0; j < width; ++j) {
//                int pixel = 0;
//                pixel |= (data[offset] & 0xff) << 16;     // R
//                pixel |= (data[offset + 1] & 0xff) << 8;  // G
//                pixel |= (data[offset + 2] & 0xff);       // B
//                pixel |= (data[offset + 3] & 0xff) << 24; // A
//                pixelData[index++] = pixel;
                pixelData[index++] = data[offset];
                pixelData[index++] = data[offset+1];
                pixelData[index++] = data[offset+2];
                pixelData[index++] = data[offset+3];
                offset += pixelStride;
            }
            offset += rowPadding;
        }
    }

    private VirtualDisplay.Callback mCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            super.onPaused();
            Log.i(TAG,"on scrcat pause");
        }

        @Override
        public void onResumed() {
            super.onResumed();
            Log.i(TAG,"on scrcat resume");
        }

        @Override
        public void onStopped() {
            super.onStopped();
            Log.i(TAG,"on scrcat stop");
        }
    };

    private class TransferThread extends Thread {
        ServerSocket mServerSocket;
        volatile boolean running = true;

        @Override
        public void run() {
            try {
                synchronized (TAG){
                    TAG.wait();
                }
                mServerSocket = new ServerSocket(56668);
                Log.i(TAG,"server on");
                while (running){
                    InputStream is;
                    Socket client = mServerSocket.accept();
                    Message message = Message.obtain();
                    message.what = MSG_CLIENT_IN;
                    message.obj  = client;
                    mImageHandler.sendMessage(message);
                    is = client.getInputStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(is));
                    String line = null;
                    Log.i(TAG,"reading...");
                    try{
                        while((line = br.readLine()) != null){
                            Log.i(TAG,"read :"+line);
                            if(line.equals("ready")){
                                mImageHandler.sendEmptyMessage(MSG_CLIENT_READY);
                            }else if(line.equals("quit")){
                                mImageHandler.sendEmptyMessage(MSG_CLIENT_OUT);
                                break;
                            }else if(line.equals("next")){
                                mImageHandler.sendEmptyMessage(MSG_CLIENT_NEXT);
                            }
                        }
                    }catch (IOException e){
                        Log.i(TAG,"client exception :"+e.getMessage());
                    }
                    mImageHandler.sendEmptyMessage(MSG_CLIENT_OUT);
                    Log.i(TAG,"client closed");
                    client.close();
                }
            } catch (IOException e) {
                Log.w(TAG,"server closed, e:"+e.getMessage());
            } catch (InterruptedException e) {
                Log.i(TAG,"receive quit cmd :"+e.getMessage());
            }
        }
    }
}
