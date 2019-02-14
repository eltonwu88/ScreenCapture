package com.example.eltonwu.screencapture;

import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

public class FloatingWindow {
    private WeakReference<Context> mContext;
    private WindowManager          mWindowManager;
    private static FloatingWindow sInstance;

    private SurfaceView            mSurfaceView;
    private float         mScreenDensity;

    private FloatingWindow(Context context){
        mContext = new WeakReference<>(context);
        mScreenDensity = context.getResources().getDisplayMetrics().density;
    }

    public static FloatingWindow getInstacne(Context context){
        if(sInstance == null){
            sInstance = new FloatingWindow(context);
        }
        return sInstance;
    }

    public SurfaceHolder getSurfaceHolder(){
        return mSurfaceView.getHolder();
    }

    public void createWindows(){
        Context context = mContext.get();
        if(context == null){
            Log.e("TAG","host has been destroyed");
            return;
        }

        //赋值WindowManager&LayoutParam.
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        //设置type.系统提示型窗口，一般都在应用程序窗口之上.
        params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        //设置效果为背景透明.
        //params.format = PixelFormat.RGBA_8888;
        //设置flags.不可聚焦及不可使用按钮对悬浮窗进行操控.
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        //设置窗口初始停靠位置.
        params.gravity = Gravity.LEFT | Gravity.TOP;
        params.x = 0;
        params.y = 0;

        //设置悬浮窗口长宽数据.
        //注意，这里的width和height均使用px而非dp.这里我偷了个懒
        //如果你想完全对应布局设置，需要先获取到机器的dpi
        //px与dp的换算为px = dp * (dpi / 160).
        params.width = (int) (90 * mScreenDensity);
        params.height = (int) (160 * mScreenDensity);

//        params.width = (int) (972);
//        params.height = (int) (1728);

        LayoutInflater inflater = LayoutInflater.from(context);
        //获取浮动窗口视图所在布局.
        View root =  inflater.inflate(R.layout.float_window_layout,null);
        mSurfaceView = root.findViewById(R.id.test_surface);
        mWindowManager.addView(root,params);
        //用于检测状态栏高度.
        /**
         int resourceId = getResources().getIdentifier("status_bar_height","dimen","android");
         if (resourceId > 0)
         {
         statusBarHeight = getResources().getDimensionPixelSize(resourceId);
         }
         Log.i(TAG,"状态栏高度为:" + statusBarHeight);
         */
    }
}
